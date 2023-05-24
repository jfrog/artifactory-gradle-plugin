package org.jfrog.buildinfo.tasks;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

public class ExtractModuleTask extends DefaultTask {

    private static final Logger log = Logging.getLogger(ExtractModuleTask.class);
    private final RegularFileProperty moduleFile = getProject().getObjects().fileProperty();

    @OutputFile
    public RegularFileProperty getModuleFile() {
        return moduleFile;
    }

    @TaskAction
    public void extractModule() {
        log.debug("<ASSAF> Task '{}' activated", getPath());
    }

}
