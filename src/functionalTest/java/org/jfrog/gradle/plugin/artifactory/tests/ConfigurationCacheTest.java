package org.jfrog.gradle.plugin.artifactory.tests;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.jfrog.gradle.plugin.artifactory.Constant;
import org.jfrog.gradle.plugin.artifactory.GradleFunctionalTestBase;
import org.jfrog.gradle.plugin.artifactory.TestConsts;
import org.jfrog.gradle.plugin.artifactory.utils.Utils;
import org.jfrog.gradle.plugin.artifactory.utils.ValidationUtils;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

import static org.testng.Assert.assertNotNull;

/**
 * Tests that artifactoryPublish works correctly with Gradle's configuration cache.
 *
 * Configuration cache requires that:
 * 1. All task fields are serializable (no Project or Task object references)
 * 2. No Task.getProject() calls happen at execution time
 * 3. Inter-task communication uses files or BuildServices, not direct task references
 */
public class ConfigurationCacheTest extends GradleFunctionalTestBase {

    /**
     * Test that 'artifactoryPublish' succeeds with --configuration-cache.
     */
    @Test(dataProvider = "gradleVersions")
    public void configurationCachePublishTest(String gradleVersion) throws IOException {
        Utils.createTestDir(TestConsts.GRADLE_EXAMPLE_PUBLISH);

        BuildResult buildResult = GradleRunner.create()
                .withGradleVersion(gradleVersion)
                .withProjectDir(TestConsts.TEST_DIR)
                .withPluginClasspath()
                .withArguments(new ArrayList<>(Arrays.asList(
                        "clean", "build",
                        Constant.ARTIFACTORY_PUBLISH_TASK_NAME,
                        "--configuration-cache",
                        "--stacktrace"
                )))
                .withEnvironment(envVars)
                .build();

        assertNotNull(buildResult, "Build with --configuration-cache should succeed");
        ValidationUtils.checkBuildResults(artifactoryManager, buildResult, localRepo);

        var buildDetails = Utils.getBuildDetails(buildResult);
        Utils.cleanTestBuilds(artifactoryManager, buildDetails.getLeft(), buildDetails.getRight(), null);
    }
}
