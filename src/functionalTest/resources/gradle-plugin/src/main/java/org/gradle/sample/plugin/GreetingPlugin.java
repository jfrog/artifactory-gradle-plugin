package org.gradle.sample.plugin;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

public class GreetingPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getLogger().quiet("applying greeting plugin");

        project.getTasks().register("hello", task -> {
            task.doLast(t -> {
                project.getLogger().quiet("Hi Bob!");
            });
        });
    }
}