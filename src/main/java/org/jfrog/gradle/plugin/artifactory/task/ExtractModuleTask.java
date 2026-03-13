package org.jfrog.gradle.plugin.artifactory.task;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.jfrog.build.extractor.ModuleExtractorUtils;
import org.jfrog.build.extractor.ci.Module;
import org.jfrog.gradle.plugin.artifactory.ArtifactoryBuildService;
import org.jfrog.gradle.plugin.artifactory.extractor.GradleModuleExtractor;
import org.jfrog.gradle.plugin.artifactory.listener.ArtifactoryDependencyResolutionListener;
import javax.inject.Inject;
import java.io.IOException;
import java.util.*;

public class ExtractModuleTask extends DefaultTask {

    private static final Logger log = Logging.getLogger(ExtractModuleTask.class);
    private final RegularFileProperty moduleFile;

    private String storedProjectPath;
    private String storedProjectName;
    private String storedProjectGroup;
    private String storedProjectVersion;
    private Map<String, String> configSnapshot;

    /**
     * Pre-collected dependency data.
     */
    public static class PreCollectedDependency {
        private final String id;
        private final String type;
        private final Set<String> scopes;
        private final String md5;
        private final String sha1;
        private final String sha256;
        private final String[][] requestedBy;

        public PreCollectedDependency(String id, String type, Set<String> scopes, String md5, String sha1, String sha256, String[][] requestedBy) {
            this.id = id;
            this.type = type;
            this.scopes = scopes;
            this.md5 = md5;
            this.sha1 = sha1;
            this.sha256 = sha256;
            this.requestedBy = requestedBy;
        }

        public String getId() { return id; }
        public String getType() { return type; }
        public Set<String> getScopes() { return scopes; }
        public String getMd5() { return md5; }
        public String getSha1() { return sha1; }
        public String getSha256() { return sha256; }
        public String[][] getRequestedBy() { return requestedBy; }
    }

    // BuildService for inter-task communication
    private final Property<ArtifactoryBuildService> buildService;

    @Inject
    public ExtractModuleTask(ObjectFactory objectFactory) {
        this.moduleFile = objectFactory.fileProperty();
        this.buildService = objectFactory.property(ArtifactoryBuildService.class);
    }

    @OutputFile
    public RegularFileProperty getModuleFile() {
        return moduleFile;
    }

    public void setProjectInfo(String path, String name, String group, String version) {
        this.storedProjectPath = path;
        this.storedProjectName = name;
        this.storedProjectGroup = group;
        this.storedProjectVersion = version;
    }

    public void setConfigSnapshot(Map<String, String> snapshot) {
        this.configSnapshot = snapshot;
    }

    @Input
    @Optional
    public String getStoredProjectPath() {
        return storedProjectPath;
    }

    @Input
    @Optional
    public String getStoredProjectName() {
        return storedProjectName;
    }

    @Input
    @Optional
    public String getStoredProjectGroup() {
        return storedProjectGroup;
    }

    @Input
    @Optional
    public String getStoredProjectVersion() {
        return storedProjectVersion;
    }

    @Input
    @Optional
    public Map<String, String> getConfigSnapshot() {
        return configSnapshot;
    }

    @Internal
    public Property<ArtifactoryBuildService> getBuildServiceProperty() {
        return buildService;
    }

    @TaskAction
    public void extractModule() {
        log.info("Extracting details for {}", getPath());
        // Get data from BuildService (populated at execution time by ArtifactoryTask and resolution listener)
        ArtifactoryBuildService.TaskData taskData = null;
        Map<String, Map<String, String[][]>> modulesHierarchyMap = null;
        List<PreCollectedDependency> deps = null;

        if (buildService != null && buildService.isPresent()) {
            ArtifactoryBuildService service = buildService.get();
            // Find the task data for our project path
            for (ArtifactoryBuildService.TaskData data : service.getAllTaskData()) {
                if (data.getProjectPath().equals(storedProjectPath)) {
                    taskData = data;
                    break;
                }
            }
        }

        // Get dependency data from static maps (populated by afterResolve listener during execution)
        modulesHierarchyMap = ArtifactoryDependencyResolutionListener.getModulesHierarchyMap();
        String moduleId = storedProjectGroup + ":" + storedProjectName + ":" + storedProjectVersion;
        deps = ArtifactoryDependencyResolutionListener.getModulesDependenciesMap().get(moduleId);

        Module module = new GradleModuleExtractor().extractModule(
                storedProjectPath, storedProjectName, storedProjectGroup, storedProjectVersion,
                configSnapshot, taskData, modulesHierarchyMap, deps);
        try {
            // Export
            ModuleExtractorUtils.saveModuleToFile(module, moduleFile.getAsFile().get());
        } catch (IOException e) {
            throw new RuntimeException("Could not save module file", e);
        }
    }

}
