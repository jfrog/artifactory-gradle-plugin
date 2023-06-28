package org.jfrog.gradle.plugin.artifactory.task;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.jfrog.build.extractor.ModuleExtractorUtils;
import org.jfrog.build.extractor.ci.Module;
import org.jfrog.gradle.plugin.artifactory.extractor.GradleModuleExtractor;

import java.io.IOException;

public class ExtractModuleTask extends DefaultTask {

    private static final Logger log = Logging.getLogger(ExtractModuleTask.class);
    private final RegularFileProperty moduleFile = getProject().getObjects().fileProperty();

    @OutputFile
    public RegularFileProperty getModuleFile() {
        return moduleFile;
    }

    @TaskAction
    public void extractModule() {
        log.info("Extracting details for {}", getPath());
        // Extract
        Module module = new GradleModuleExtractor().extractModule(getProject());
        try {
            // Export
            ModuleExtractorUtils.saveModuleToFile(module, moduleFile.getAsFile().get());
        } catch (IOException e) {
            throw new RuntimeException("Could not save module file", e);
        }
    }

}
