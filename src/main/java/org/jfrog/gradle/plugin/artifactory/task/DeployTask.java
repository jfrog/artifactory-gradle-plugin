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
import org.jfrog.build.extractor.BuildInfoExtractorUtils;
import org.jfrog.build.extractor.ci.BuildInfo;
import org.jfrog.build.extractor.ci.BuildInfoConfigProperties;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.build.extractor.clientConfiguration.deploy.DeployDetails;
import org.jfrog.gradle.plugin.artifactory.extractor.GradleBuildInfoExtractor;
import org.jfrog.gradle.plugin.artifactory.extractor.ModuleInfoFileProducer;
import org.jfrog.gradle.plugin.artifactory.Constant;
import org.jfrog.gradle.plugin.artifactory.utils.ExtensionsUtils;
import org.jfrog.gradle.plugin.artifactory.utils.DeployUtils;
import org.jfrog.gradle.plugin.artifactory.utils.TaskUtils;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

import static org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration.addDefaultPublisherAttributes;

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

    @TaskAction
    public void extractBuildInfoAndDeploy() throws IOException {
        log.debug("Extracting build-info and deploying build details in task '{}'", getPath());
        ArtifactoryClientConfiguration accRoot = ExtensionsUtils.getArtifactoryExtension(getProject()).getClientConfig();
        // Deploy Artifacts to artifactory
        Map<String, Set<DeployDetails>> allDeployedDetails = deployArtifactsFromTasks(accRoot);
        // Generate build-info and handle deployment (and artifact exports if configured)
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
            orderedTasks.forEach(t -> DeployUtils.deployTaskArtifacts(accRoot, propsRoot, allDeployDetails, t, null));
        } else {
            try {
                ExecutorService executor = Executors.newFixedThreadPool(publishForkCount);
                CompletableFuture<Void> allUploads = CompletableFuture.allOf(orderedTasks.stream()
                        .map(t -> CompletableFuture.runAsync(() -> DeployUtils.deployTaskArtifacts(accRoot, propsRoot, allDeployDetails, t, "[" + Thread.currentThread().getName() + "]"), executor))
                        .toArray(CompletableFuture[]::new));
                allUploads.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }
        return allDeployDetails;
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
        return new File(rootProject.getBuildDir(), Constant.BUILD_INFO_FILE_NAME);
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
        if (StringUtils.isNotBlank(propertyFilePath)) {
            File file = new File(propertyFilePath);
            if (file.exists() && !file.delete()) {
                log.warn("Can't delete build-info config properties file at {}", propertyFilePath);
            }
        }
    }
}
