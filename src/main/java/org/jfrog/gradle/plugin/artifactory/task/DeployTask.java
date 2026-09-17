package org.jfrog.gradle.plugin.artifactory.task;

import org.apache.commons.lang3.StringUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileCollection;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.jfrog.build.extractor.BuildInfoExtractorUtils;
import org.jfrog.build.extractor.ci.BuildInfo;
import org.jfrog.build.extractor.ci.BuildInfoConfigProperties;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.build.extractor.clientConfiguration.deploy.DeployDetails;
import org.jfrog.gradle.plugin.artifactory.ArtifactoryBuildService;
import org.jfrog.gradle.plugin.artifactory.Constant;
import org.jfrog.gradle.plugin.artifactory.extractor.GradleBuildInfoExtractor;
import org.jfrog.gradle.plugin.artifactory.utils.ClientConfigHelper;
import org.jfrog.gradle.plugin.artifactory.utils.DeployUtils;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

import static org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration.addDefaultPublisherAttributes;

public class DeployTask extends DefaultTask {
    private static final Logger log = Logging.getLogger(DeployTask.class);

    private final ConfigurableFileCollection moduleInfoFiles;

    private String rootProjectName;
    private String gradleVersion;
    private Map<String, String> rootConfigSnapshot;
    private final DirectoryProperty rootBuildDirectory;

    // BuildService for inter-task communication
    private final Property<ArtifactoryBuildService> buildService;

    @Inject
    public DeployTask(ObjectFactory objectFactory) {
        this.moduleInfoFiles = objectFactory.fileCollection();
        this.rootBuildDirectory = objectFactory.directoryProperty();
        this.buildService = objectFactory.property(ArtifactoryBuildService.class);
    }

    /**
     * Add module info files to the input file collection.
     */
    public void addModuleInfoFiles(FileCollection files) {
        this.moduleInfoFiles.from(files);
    }

    @InputFiles
    public FileCollection getModuleInfoFiles() {
        return moduleInfoFiles;
    }

    public void setRootProjectName(String rootProjectName) {
        this.rootProjectName = rootProjectName;
    }

    public void setGradleVersion(String gradleVersion) {
        this.gradleVersion = gradleVersion;
    }

    public void setRootConfigSnapshot(Map<String, String> rootConfigSnapshot) {
        this.rootConfigSnapshot = rootConfigSnapshot;
    }

    @Internal
    public DirectoryProperty getRootBuildDirectory() {
        return rootBuildDirectory;
    }

    @Input
    @Optional
    public String getRootProjectName() {
        return rootProjectName;
    }

    @Input
    @Optional
    public String getGradleVersion() {
        return gradleVersion;
    }

    @Input
    @Optional
    public Map<String, String> getRootConfigSnapshot() {
        return rootConfigSnapshot;
    }

    @Internal
    public Property<ArtifactoryBuildService> getBuildServiceProperty() {
        return buildService;
    }

    @TaskAction
    public void extractBuildInfoAndDeploy() throws IOException {
        log.debug("Extracting build-info and deploying build details in task '{}'", getPath());
        if (rootConfigSnapshot == null) {
            log.warn("No root config snapshot available for deploy task");
            return;
        }
        ArtifactoryClientConfiguration accRoot = ClientConfigHelper.restoreConfig(rootConfigSnapshot);
        Map<String, Set<DeployDetails>> allDeployedDetails = deployArtifactsFromTasks(accRoot);
        // Deploy Artifacts to artifactory
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
        addDefaultPublisherAttributes(accRoot, rootProjectName, Constant.GRADLE, gradleVersion);

        Map<String, Set<DeployDetails>> allDeployDetails = new ConcurrentHashMap<>();

        // Get task data from BuildService instead of task graph
        List<ArtifactoryBuildService.TaskData> allTaskData;
        if (buildService != null && buildService.isPresent()) {
            allTaskData = buildService.get().getAllTaskData();
        } else {
            allTaskData = Collections.emptyList();
        }

        // Deploy
        int publishForkCount = accRoot.publisher.getPublishForkCount();
        if (publishForkCount <= 1) {
            for (ArtifactoryBuildService.TaskData taskData : allTaskData) {
                if (taskData.getConfigSnapshot() != null) {
                    ArtifactoryClientConfiguration taskConfig = ClientConfigHelper.restoreConfig(taskData.getConfigSnapshot());
                    DeployUtils.deployTaskArtifacts(taskConfig, propsRoot, allDeployDetails, taskData, null);
                }
            }
        } else {
            try {
                ExecutorService executor = Executors.newFixedThreadPool(publishForkCount);
                CompletableFuture<Void> allUploads = CompletableFuture.allOf(allTaskData.stream()
                        .map(taskData -> CompletableFuture.runAsync(() -> {
                            if (taskData.getConfigSnapshot() != null) {
                                ArtifactoryClientConfiguration taskConfig = ClientConfigHelper.restoreConfig(taskData.getConfigSnapshot());
                                DeployUtils.deployTaskArtifacts(taskConfig, propsRoot, allDeployDetails, taskData, "[" + Thread.currentThread().getName() + "]");
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
     * Extract build-info, export it to the file-system and Deploy it to Artifactory.
     * (optional: export an additional file with the deployed artifacts)
     */
    private void handleBuildInfoOperations(ArtifactoryClientConfiguration accRoot, Map<String, Set<DeployDetails>> allDeployedDetails) throws IOException {
        // Extract build-info
        GradleBuildInfoExtractor gbie = new GradleBuildInfoExtractor(accRoot, moduleInfoFiles);
        BuildInfo buildInfo = gbie.extract(null);
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
        // Default path - use stored rootBuildDirectory
        return rootBuildDirectory.file(Constant.BUILD_INFO_FILE_NAME).get().getAsFile();
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
            Path buildDir = rootBuildDirectory.get().getAsFile().toPath().toAbsolutePath().normalize();
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
