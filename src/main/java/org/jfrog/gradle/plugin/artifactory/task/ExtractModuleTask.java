package org.jfrog.gradle.plugin.artifactory.task;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
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
import org.jfrog.gradle.plugin.artifactory.extractor.PreCollectedDependency;

import javax.inject.Inject;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ExtractModuleTask extends DefaultTask {

    private static final Logger log = Logging.getLogger(ExtractModuleTask.class);
    private final RegularFileProperty moduleFile;

    private String storedProjectPath;
    private String storedProjectName;
    private String storedProjectGroup;
    private String storedProjectVersion;
    private Map<String, String> configSnapshot;

    // BuildService for inter-task communication
    private final Property<ArtifactoryBuildService> buildService;

    // Dependency records captured lazily from each resolvable configuration's resolution result.
    // Populated at configuration time via provider transforms; the resolved values are serialized into
    // the configuration cache and reloaded on a cache hit (unlike a config-time resolution listener).
    private final ListProperty<PreCollectedDependency> preCollectedDependencies;

    @Inject
    public ExtractModuleTask(ObjectFactory objectFactory) {
        this.moduleFile = objectFactory.fileProperty();
        this.buildService = objectFactory.property(ArtifactoryBuildService.class);
        this.preCollectedDependencies = objectFactory.listProperty(PreCollectedDependency.class);
    }

    @Internal
    public ListProperty<PreCollectedDependency> getPreCollectedDependencies() {
        return preCollectedDependencies;
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
        // Get deploy details from BuildService (populated at execution time by ArtifactoryTask)
        ArtifactoryBuildService.TaskData taskData = null;
        if (buildService != null && buildService.isPresent()) {
            ArtifactoryBuildService service = buildService.get();
            for (ArtifactoryBuildService.TaskData data : service.getAllTaskData()) {
                if (data.getProjectPath().equals(storedProjectPath)) {
                    taskData = data;
                    break;
                }
            }
        }

        String effectiveVersion = (buildService != null && buildService.isPresent() && buildService.get().getProjectVersion() != null)
                ? buildService.get().getProjectVersion()
                : storedProjectVersion;

        // Dependency records captured from each configuration's resolution result via lazy providers.
        // The values are serialized into the configuration cache and reloaded on a cache hit.
        List<PreCollectedDependency> deps = preCollectedDependencies.getOrElse(Collections.emptyList());

        Module module = new GradleModuleExtractor().extractModule(
                storedProjectPath, storedProjectName, storedProjectGroup, effectiveVersion,
                configSnapshot, taskData, deps);
        try {
            ModuleExtractorUtils.saveModuleToFile(module, moduleFile.getAsFile().get());
        } catch (IOException e) {
            throw new RuntimeException("Could not save module file", e);
        }
    }
}
