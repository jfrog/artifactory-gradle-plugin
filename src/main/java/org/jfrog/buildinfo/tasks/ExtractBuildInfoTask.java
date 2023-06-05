package org.jfrog.buildinfo.tasks;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.TaskAction;
import org.jfrog.buildinfo.extractor.ModuleInfoFileProducer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ExtractBuildInfoTask extends DefaultTask {

    private static final Logger log = Logging.getLogger(ExtractBuildInfoTask.class);

    private final List<ModuleInfoFileProducer> moduleInfoFileProducers = new ArrayList<>();

    @TaskAction
    public void taskAction() throws IOException {
        log.debug("<ASSAF> Task '{}' activated", getPath());
    }

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
}
