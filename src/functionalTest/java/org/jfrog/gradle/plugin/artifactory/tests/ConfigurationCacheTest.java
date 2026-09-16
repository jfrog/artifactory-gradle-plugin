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
import static org.testng.Assert.assertTrue;

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

    /**
     * Test that 'artifactoryPublish' produces correct build-info on a configuration cache REUSE (second run).
     * The first run stores the cache; the second run must reuse it and still publish build-info with the
     * complete dependency graph. Dependency data collected via config-time listeners does NOT survive a
     * cache hit (the configuration phase is skipped), so this asserts the reuse run's dependencies are intact.
     *
     * No 'clean' between runs: cleaning deletes generated files and triggers "file system entry created"
     * cache invalidation, which would silently turn run 2 into another store instead of a reuse.
     */
    @Test(dataProvider = "gradleVersions")
    public void configurationCacheReusePublishTest(String gradleVersion) throws IOException {
        Utils.createTestDir(TestConsts.GRADLE_EXAMPLE_PUBLISH);

        java.util.List<String> args = Arrays.asList(
                "build",
                Constant.ARTIFACTORY_PUBLISH_TASK_NAME,
                "--configuration-cache",
                "--stacktrace");

        // Run 1: store the configuration cache.
        GradleRunner.create()
                .withGradleVersion(gradleVersion)
                .withProjectDir(TestConsts.TEST_DIR)
                .withPluginClasspath()
                .withArguments(new ArrayList<>(args))
                .withEnvironment(envVars)
                .build();

        // Run 2: must reuse the cache.
        BuildResult reuseResult = GradleRunner.create()
                .withGradleVersion(gradleVersion)
                .withProjectDir(TestConsts.TEST_DIR)
                .withPluginClasspath()
                .withArguments(new ArrayList<>(args))
                .withEnvironment(envVars)
                .build();

        assertTrue(reuseResult.getOutput().contains("Reusing configuration cache"),
                "Second run must reuse the configuration cache. Output:\n" + reuseResult.getOutput());

        // Validate the build-info produced by the REUSE run - this is where missing-dependency bugs surface.
        ValidationUtils.checkBuildResults(artifactoryManager, reuseResult, localRepo);

        var buildDetails = Utils.getBuildDetails(reuseResult);
        Utils.cleanTestBuilds(artifactoryManager, buildDetails.getLeft(), buildDetails.getRight(), null);
    }
}
