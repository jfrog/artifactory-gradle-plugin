package org.jfrog.buildinfo;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.jfrog.buildinfo.tasks.HelloWorldTask;

public class ArtifactoryPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        if (isGradleVersionNotSupported(project)) {
            System.out.println("Can't apply on Gradle version " + project.getGradle().getGradleVersion());
            return;
        }

        for (Project proj : project.getAllprojects()) {
            addPluginTasks(proj);
        }
    }

    private void addPluginTasks(Project project) {
        project.getTasks().maybeCreate(HelloWorldTask.TASK_NAME, HelloWorldTask.class);
    }

    public boolean isGradleVersionNotSupported(Project project) {
        return compareVersions(Constant.MIN_GRADLE_VERSION, project.getGradle().getGradleVersion()) > 0;
    }

    private  int compareVersions(String version1, String version2) {
        String[] segments1 = version1.split("\\.");
        String[] segments2 = version2.split("\\.");

        int length = Math.max(segments1.length, segments2.length);

        for (int i = 0; i < length; i++) {
            int segment1 = i < segments1.length ? Integer.parseInt(segments1[i]) : 0;
            int segment2 = i < segments2.length ? Integer.parseInt(segments2[i]) : 0;

            if (segment1 < segment2) {
                return -1;
            } else if (segment1 > segment2) {
                return 1;
            }
        }

        return 0; // Versions are equal
    }
}
