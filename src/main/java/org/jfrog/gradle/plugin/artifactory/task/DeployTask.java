package org.jfrog.gradle.plugin.artifactory.task;

import org.apache.commons.lang3.StringUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;
import org.jfrog.build.extractor.BuildInfoExtractorUtils;
import org.jfrog.build.extractor.ci.BuildInfo;
import org.jfrog.build.extractor.ci.BuildInfoConfigProperties;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.build.extractor.clientConfiguration.deploy.DeployDetails;
import org.jfrog.gradle.plugin.artifactory.dsl.ArtifactoryPluginConvention;
import org.jfrog.gradle.plugin.artifactory.extractor.GradleBuildInfoExtractor;
import org.jfrog.gradle.plugin.artifactory.extractor.ModuleInfoFileProducer;
import org.jfrog.gradle.plugin.artifactory.extractor.SubprocessModuleInfoFileProducer;
import org.jfrog.gradle.plugin.artifactory.Constant;
import org.jfrog.gradle.plugin.artifactory.utils.ExtensionsUtils;
import org.jfrog.gradle.plugin.artifactory.utils.DeployUtils;
import org.jfrog.gradle.plugin.artifactory.utils.SharedBuildCollector;
import org.jfrog.gradle.plugin.artifactory.utils.SharedBuildLogicUtils;
import org.jfrog.gradle.plugin.artifactory.utils.TaskUtils;
import org.jfrog.build.api.util.FileChecksumCalculator;
import java.io.File;

import static org.jfrog.build.api.util.FileChecksumCalculator.MD5_ALGORITHM;
import static org.jfrog.build.api.util.FileChecksumCalculator.SHA1_ALGORITHM;
import static org.jfrog.build.api.util.FileChecksumCalculator.SHA256_ALGORITHM;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

import static org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration.addDefaultPublisherAttributes;

@UntrackedTask(because = "Deployment task publishes artifacts to Artifactory and generates build-info - not cacheable")
public class DeployTask extends DefaultTask {
    private static final Logger log = Logging.getLogger(DeployTask.class);

    private final List<ModuleInfoFileProducer> moduleInfoFileProducers = new ArrayList<>();

    public void registerModuleInfoProducer(ModuleInfoFileProducer moduleInfoFileProducer) {
        this.moduleInfoFileProducers.add(moduleInfoFileProducer);
    }

    @InputFiles
    public FileCollection getModuleInfoFiles() {
        ConfigurableFileCollection moduleInfoFiles = getProject().files();
        moduleInfoFileProducers.forEach(moduleInfoFileProducer -> {
            moduleInfoFiles.from(moduleInfoFileProducer.getModuleInfoFiles());
            moduleInfoFiles.builtBy(moduleInfoFileProducer.getModuleInfoFiles().getBuildDependencies());
        });
        return moduleInfoFiles;
    }

    /**
     * Write module-info files during task execution so they survive a preceding :clean.
     */
    private void ensureModuleInfoFilesAreWritten() {
        moduleInfoFileProducers.forEach(ModuleInfoFileProducer::ensureModuleInfoFilesAreWritten);
    }

    @TaskAction
    public void extractBuildInfoAndDeploy() throws IOException {
        log.debug("Extracting build-info and deploying build details in task '{}'", getPath());
        ArtifactoryClientConfiguration accRoot = ExtensionsUtils.getArtifactoryExtension(getProject()).getClientConfig();
        Map<String, Set<DeployDetails>> allDeployedDetails = deployArtifactsFromTasks(accRoot);
        if (SharedBuildLogicUtils.isIncludeSharedBuildEnabled(getProject())) {
            SharedBuildCollector.collectUsed(getProject().getRootProject(), this);
            ensureModuleInfoFilesAreWritten();
            publishNestedBuildArtifacts(accRoot, allDeployedDetails);
        }
        handleBuildInfoOperations(accRoot, allDeployedDetails);
        deleteBuildInfoPropertiesFile();
    }

    /**
     * Deploy to artifactory all the applicable artifacts collected in all the Artifactory tasks.
     *
     * @param accRoot - client configuration that defined at the root project
     * @return Map of module to deployed artifact details.
     */
    private Map<String, Set<DeployDetails>> deployArtifactsFromTasks(ArtifactoryClientConfiguration accRoot) {
        // Reset the default properties, they may have changed
        Map<String, String> propsRoot = accRoot.publisher.getProps();
        addDefaultPublisherAttributes(accRoot, getProject().getRootProject().getName(), Constant.GRADLE, getProject().getGradle().getGradleVersion());

        Map<String, Set<DeployDetails>> allDeployDetails = new ConcurrentHashMap<>();
        List<ArtifactoryTask> orderedTasks = TaskUtils.getAllArtifactoryPublishTasks(getProject());

        // Deploy
        int publishForkCount = accRoot.publisher.getPublishForkCount();
        if (publishForkCount <= 1) {
            orderedTasks.forEach(t -> {
                ArtifactoryPluginConvention convention = ExtensionsUtils.getExtensionWithPublisher(t.getProject());
                if (convention != null) {
                    DeployUtils.deployTaskArtifacts(convention.getClientConfig(), propsRoot, allDeployDetails, t, null);
                }
            });
        } else {
            try {
                ExecutorService executor = Executors.newFixedThreadPool(publishForkCount);
                CompletableFuture<Void> allUploads = CompletableFuture.allOf(orderedTasks.stream()
                        .map(t -> CompletableFuture.runAsync(() -> {
                            ArtifactoryPluginConvention convention = ExtensionsUtils.getExtensionWithPublisher(t.getProject());
                            if (convention != null) {
                                DeployUtils.deployTaskArtifacts(convention.getClientConfig(), propsRoot, allDeployDetails, t, "[" + Thread.currentThread().getName() + "]");
                            }
                        }, executor))
                        .toArray(CompletableFuture[]::new));
                allUploads.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }
        return allDeployDetails;
    }

    /**
     * Deploy shared-build files with Maven paths and build.name / build.number / build.timestamp
     * so Artifactory and Xray can correlate them with build-info.
     */
    private void publishNestedBuildArtifacts(ArtifactoryClientConfiguration accRoot, Map<String, Set<DeployDetails>> allDeployedDetails) {
        String repoKey = accRoot.publisher.getRepoKey();
        if (StringUtils.isBlank(repoKey) || StringUtils.isBlank(accRoot.publisher.getContextUrl())) {
            log.debug("Skipping shared-build artifact publishing: repository or context URL is not configured");
            return;
        }

        Map<String, String> artifactProps = new HashMap<>();
        if (accRoot.publisher.getMatrixParams() != null) {
            artifactProps.putAll(accRoot.publisher.getMatrixParams());
        }
        artifactProps.putAll(SharedBuildLogicUtils.buildArtifactProperties(
                accRoot.info.getBuildName(), accRoot.info.getBuildNumber(), accRoot.info.getBuildTimestamp()));

        for (ModuleInfoFileProducer producer : moduleInfoFileProducers) {
            if (!(producer instanceof SubprocessModuleInfoFileProducer)) {
                continue;
            }
            SubprocessModuleInfoFileProducer subprocess = (SubprocessModuleInfoFileProducer) producer;
            List<SubprocessModuleInfoFileProducer.ArtifactToPublish> artifacts = subprocess.getArtifactsToPublish();
            if (artifacts.isEmpty()) {
                continue;
            }
            for (SubprocessModuleInfoFileProducer.ArtifactToPublish artifact : artifacts) {
                try {
                    Map<String, String> checksums = FileChecksumCalculator.calculateChecksums(
                            artifact.sourceFile, MD5_ALGORITHM, SHA1_ALGORITHM, SHA256_ALGORITHM);
                    DeployDetails deployDetails = new DeployDetails.Builder()
                            .file(artifact.sourceFile)
                            .targetRepository(repoKey)
                            .artifactPath(artifact.artifactPath)
                            .packageType(DeployDetails.PackageType.GRADLE)
                            .md5(checksums.get(MD5_ALGORITHM))
                            .sha1(checksums.get(SHA1_ALGORITHM))
                            .sha256(checksums.get(SHA256_ALGORITHM))
                            .addProperties(artifactProps)
                            .build();
                    DeployUtils.deployFile(accRoot, deployDetails);
                    log.info("Published shared-build artifact {} to {}/{}",
                            artifact.sourceFile.getName(), repoKey, artifact.artifactPath);
                    allDeployedDetails.computeIfAbsent(subprocess.getModuleId(),
                                    k -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
                            .add(deployDetails);
                } catch (Exception e) {
                    log.error("Failed to publish shared-build artifact {}: {}",
                            artifact.sourceFile.getAbsolutePath(), e.getMessage());
                }
            }
        }
    }

    /**
     * Extract build-info, export it to the file-system and Deploy it to Artifactory.
     * (optional: export an additional file with the deployed artifacts)
     */
    private void handleBuildInfoOperations(ArtifactoryClientConfiguration accRoot, Map<String, Set<DeployDetails>> allDeployedDetails) throws IOException {
        // Extract build-info
        GradleBuildInfoExtractor gbie = new GradleBuildInfoExtractor(accRoot, moduleInfoFileProducers);
        BuildInfo buildInfo = gbie.extract(getProject().getRootProject());
        // Export in Json format to file system
        exportBuildInfoToFileSystem(accRoot, buildInfo);
        // Deploy build-info file and export deployed artifacts
        DeployUtils.deployBuildInfo(accRoot, buildInfo, allDeployedDetails);
    }

    private void exportBuildInfoToFileSystem(ArtifactoryClientConfiguration accRoot, BuildInfo buildInfo) throws IOException {
        try {
            exportBuildInfo(buildInfo, getExportFile(accRoot));
            // We offer option to create a copy in additional place if requested
            if (!StringUtils.isEmpty(accRoot.info.getGeneratedBuildInfoFilePath())) {
                exportBuildInfo(buildInfo, new File(accRoot.info.getGeneratedBuildInfoFilePath()));
            }
        } catch (Exception e) {
            log.error("Failed writing build info to file: ", e);
            throw new IOException("Failed writing build info to file", e);
        }
    }

    private File getExportFile(ArtifactoryClientConfiguration clientConf) {
        // Configured path
        String fileExportPath = clientConf.getExportFile();
        if (StringUtils.isNotBlank(fileExportPath)) {
            return new File(fileExportPath);
        }
        // Default path
        Project rootProject = getProject().getRootProject();
        return rootProject.getLayout().getBuildDirectory().file(Constant.BUILD_INFO_FILE_NAME).get().getAsFile();
    }

    private void exportBuildInfo(BuildInfo buildInfo, File toFile) throws IOException {
        log.debug("Exporting generated build info to '{}'", toFile.getAbsolutePath());
        BuildInfoExtractorUtils.saveBuildInfoToFile(buildInfo, toFile);
    }

    private void deleteBuildInfoPropertiesFile() {
        String propertyFilePath = System.getenv(BuildInfoConfigProperties.PROP_PROPS_FILE);
        if (StringUtils.isBlank(propertyFilePath)) {
            propertyFilePath = System.getenv(BuildInfoConfigProperties.ENV_BUILDINFO_PROPFILE);
        }
        if (StringUtils.isBlank(propertyFilePath)) {
            log.warn("No build-info config properties file path provided.");
            return;
        }

        try {
            Path buildDir = getProject().getRootProject().getLayout().getBuildDirectory().get().getAsFile().toPath().toAbsolutePath().normalize();
            Path filePath = Paths.get(propertyFilePath).toAbsolutePath().normalize();

            // Ensure file is in build dir and named build-info.json
            if (!filePath.startsWith(buildDir)) {
                log.error("Attempt to access unauthorized file path: {}", filePath);
                return;
            }
            if (!"build-info.json".equals(filePath.getFileName().toString())) {
                log.error("Invalid filename: {}", filePath.getFileName());
                return;
            }

            if (Files.exists(filePath) && !Files.deleteIfExists(filePath)) {
                log.warn("Can't delete build-info config properties file at {}", filePath);
            }
        } catch (IOException e) {
            log.error("Error processing file path: {}", propertyFilePath, e);
        }
    }
}
