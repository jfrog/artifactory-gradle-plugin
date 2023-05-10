package org.jfrog.buildinfo;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.jfrog.buildinfo.tasks.HelloWorldTask;

public class ArtifactoryPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        for (Project proj : project.getAllprojects()) {
            addPluginTasks(proj);
        }
    }

    private void addPluginTasks(Project project) {
        project.getTasks().maybeCreate(HelloWorldTask.TASK_NAME, HelloWorldTask.class);
    }
}
