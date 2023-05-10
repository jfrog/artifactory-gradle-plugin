package org.jfrog.buildinfo;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.BuildTask;
import org.gradle.testkit.runner.GradleRunner;

import java.io.File;

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;
import static org.testng.Assert.assertEquals;

public class Utils {

    /**
     * Run Gradle tasks in a given context.
     * @param gradleVersion - run the tasks with this given gradle version
     * @param projectDir - the gradle project to run the tasks on
     * @param args - tasks to run
     * @return the build result of each task
     */
    public static BuildResult runGradle(String gradleVersion, File projectDir, String... args) {
        return GradleRunner.create()
                .withGradleVersion(gradleVersion)
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withDebug(true)
                .withArguments(args)
                .build();
    }

    /**
     * Assert if the given gradle run results ended with success.
     * Skips the clean tasks.
     * @param buildResult - the result to assert
     */
    public static void assertSuccess(BuildResult buildResult) {
        for (BuildTask buildTask : buildResult.getTasks()) {
            if (buildTask.getPath().contains("clean")) {
                continue;
            }
            assertEquals(buildTask.getOutcome(), SUCCESS);
        }
    }
}
