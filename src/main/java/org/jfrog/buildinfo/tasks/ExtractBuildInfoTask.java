package org.jfrog.buildinfo.tasks;

import org.gradle.api.DefaultTask;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.TaskAction;
import org.jfrog.buildinfo.extractor.ModuleInfoFileProducer;

import java.io.IOException;

public class ExtractBuildInfoTask extends DefaultTask {

    private static final Logger log = Logging.getLogger(ExtractBuildInfoTask.class);

    @TaskAction
    public void taskAction() throws IOException {
        log.debug("<ASSAF> Task '{}' activated", getPath());
    }

    public void registerModuleInfoProducer(ModuleInfoFileProducer moduleInfoFileProducer) {

    }
}
