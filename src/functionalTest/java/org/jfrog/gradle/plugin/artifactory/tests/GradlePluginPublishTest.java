package org.jfrog.gradle.plugin.artifactory.tests;

import org.gradle.testkit.runner.BuildResult;
import org.jfrog.build.api.dependency.PropertySearchResult;
import org.jfrog.build.extractor.ci.BuildInfo;
import org.jfrog.build.extractor.ci.Module;
import org.jfrog.gradle.plugin.artifactory.GradleFunctionalTestBase;
import org.jfrog.gradle.plugin.artifactory.TestConsts;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.List;

import static org.gradle.testkit.runner.TaskOutcome.FAILED;
import static org.jfrog.gradle.plugin.artifactory.TestConsts.EXPECTED_GRADLE_PLUGIN_ARTIFACTS;
import static org.jfrog.gradle.plugin.artifactory.utils.ValidationUtils.getBuildInfo;
import static org.testng.Assert.*;

public class GradlePluginPublishTest extends GradleFunctionalTestBase {
    @Test(dataProvider = "gradleVersions")
    public void gradlePluginPublishTest(String gradleVersion) throws IOException {
        runPublishTest(gradleVersion, TestConsts.GRADLE_PLUGIN_PUBLISH, this::checkBuildResults);
    }

    /**
     * Check Gradle plugin build results.
     *
     * @param buildResult - The build results
     * @throws IOException In case of any IO error.
     */
    public void checkBuildResults(BuildResult buildResult) throws IOException {
        // Assert all tasks ended with success outcome
        buildResult.getTasks().forEach(buildTask -> assertNotEquals(buildTask.getOutcome(), FAILED));

        // Check that all expected artifacts uploaded to Artifactory
        for (String expectedArtifact : EXPECTED_GRADLE_PLUGIN_ARTIFACTS) {
            artifactoryManager.downloadHeaders(localRepo + "/" + expectedArtifact);
        }

        // Check buildInfo info
        BuildInfo buildInfo = getBuildInfo(artifactoryManager, buildResult);
        assertNotNull(buildInfo);
        checkBuildInfoModules(buildInfo);

        // Check build info properties on published Artifacts
        PropertySearchResult artifacts = artifactoryManager.searchArtifactsByProperties(String.format("build.name=%s;build.number=%s", buildInfo.getName(), buildInfo.getNumber()));
        assertEquals(artifacts.getResults().size(), 4);
    }

    /**
     * Check expected build info modules.
     *
     * @param buildInfo - The build info
     */
    public void checkBuildInfoModules(BuildInfo buildInfo) {
        List<Module> modules = buildInfo.getModules();
        assertEquals(modules.size(), 1);
        Module module = buildInfo.getModule("org.example.gradle.publishing:gradle_tests_space:1.0.0");
        assertNotNull(module);
        assertEquals(module.getArtifacts().size(), 4);
        assertEquals(module.getDependencies().size(), 1);
    }
}
