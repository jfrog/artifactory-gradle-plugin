package org.jfrog.buildinfo;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ArtifactoryPlugin implements Plugin<Project> {
    private static final Logger log = LoggerFactory.getLogger(ArtifactoryPlugin.class);
    private static final String TEST_TASK_NAME = "helloWorld";

    @Override
    public void apply(Project project) {
        Task task = project.getTasks().create(TEST_TASK_NAME);
        task.doLast(taskToExe -> {
            task.getLogger().debug("Test Test Test.....");
            log.debug("Hello World Test - log");
            System.out.println("Hello World Test - syso");
        });
    }
}
