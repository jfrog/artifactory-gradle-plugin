package org.jfrog.gradle.plugin.artifactory.tests;

import org.gradle.testkit.runner.BuildResult;
import org.jfrog.build.extractor.ci.BuildInfo;
import org.jfrog.build.extractor.ci.Module;
import org.jfrog.gradle.plugin.artifactory.GradleFunctionalTestBase;
import org.jfrog.gradle.plugin.artifactory.TestConsts;
import org.jfrog.gradle.plugin.artifactory.utils.ValidationUtils;
import org.testng.annotations.Test;

import java.io.FileNotFoundException;
import java.io.IOException;

import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

/**
 * RTECO-2747: "publishArtifacts = false" in "defaults {}" must skip the artifact deployment,
 * while the build info is still published with its modules and dependencies.
 * Not setting publishArtifacts (the default) must keep deploying the artifacts.
 * An explicit "publishArtifacts = true" is covered by {@link PluginPublishTest}.
 * The existing example projects are reused: the flag is changed in the copied build script.
 */
public class PluginPublishArtifactsFlagTest extends GradleFunctionalTestBase {
    @Test(dataProvider = "gradleVersions")
    public void publishArtifactsFalseTest(String gradleVersion) throws IOException {
        runPublishTest(gradleVersion, TestConsts.GRADLE_EXAMPLE_PUBLISH, s -> s.replace("publishArtifacts = true", "publishArtifacts = false"), buildResult -> {
            // No artifact is deployed
            for (String artifact : TestConsts.EXPECTED_ARTIFACTS) {
                assertNotDeployed(localRepo + TestConsts.ARTIFACTS_GROUP_ID + artifact);
            }
            // The build info is published and holds the modules and their dependencies
            BuildInfo buildInfo = ValidationUtils.getBuildInfo(artifactoryManager, buildResult);
            assertNotNull(buildInfo);
            assertTrue(buildInfo.getModules().size() >= 3);
            Module webservice = buildInfo.getModule("org.jfrog.test.gradle.publish:webservice:1.0-SNAPSHOT");
            assertNotNull(webservice);
            assertTrue(webservice.getDependencies() != null && !webservice.getDependencies().isEmpty(),
                    "Missing dependencies in module " + webservice.getId());
        });
    }

    @Test(dataProvider = "gradleVersions")
    public void publishArtifactsDefaultTest(String gradleVersion) throws IOException {
        runPublishTest(gradleVersion, TestConsts.GRADLE_EXAMPLE_PUBLISH, s -> s.replace("publishArtifacts = true", ""), this::assertDeployedByDefault);
    }

    @Test(dataProvider = "gradleVersions")
    public void publishArtifactsDefaultKotlinDslTest(String gradleVersion) throws IOException {
        runPublishTest(gradleVersion, TestConsts.GRADLE_KTS_EXAMPLE_PUBLISH, s -> s.replace("setPublishArtifacts(true)", ""), this::assertDeployedByDefault);
    }

    private void assertDeployedByDefault(BuildResult buildResult) throws IOException {
        // All the artifacts are deployed
        for (String artifact : TestConsts.EXPECTED_ARTIFACTS) {
            try {
                artifactoryManager.downloadHeaders(localRepo + TestConsts.ARTIFACTS_GROUP_ID + artifact);
            } catch (IOException e) {
                fail("Artifact was not deployed although publishArtifacts is not set: " + artifact, e);
            }
        }
        BuildInfo buildInfo = ValidationUtils.getBuildInfo(artifactoryManager, buildResult);
        assertNotNull(buildInfo);
        assertTrue(buildInfo.getModules().size() >= 3);
    }

    @Test(dataProvider = "gradleVersions")
    public void publishArtifactsFalseKotlinDslTest(String gradleVersion) throws IOException {
        runPublishTest(gradleVersion, TestConsts.GRADLE_KTS_EXAMPLE_PUBLISH, s -> s.replace("setPublishArtifacts(true)", "setPublishArtifacts(false)"), buildResult -> {
            for (String artifact : TestConsts.EXPECTED_ARTIFACTS) {
                assertNotDeployed(localRepo + TestConsts.ARTIFACTS_GROUP_ID + artifact);
            }
            BuildInfo buildInfo = ValidationUtils.getBuildInfo(artifactoryManager, buildResult);
            assertNotNull(buildInfo);
            assertTrue(buildInfo.getModules().size() >= 3);
        });
    }

    private void assertNotDeployed(String path) throws IOException {
        try {
            artifactoryManager.downloadHeaders(path);
        } catch (FileNotFoundException e) {
            // Expected: the artifact does not exist (HTTP 404). Any other error (auth, server error) fails the test.
            return;
        }
        fail("Artifact was deployed although publishArtifacts = false: " + path);
    }
}
