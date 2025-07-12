package org.jfrog.gradle.plugin.artifactory.tests;

import org.apache.commons.lang3.tuple.Pair;
import org.gradle.testkit.runner.BuildResult;
import org.jfrog.build.api.BuildInfoConfigProperties;
import org.jfrog.build.api.dependency.PropertySearchResult;
import org.jfrog.build.extractor.ci.BuildInfo;
import org.jfrog.build.extractor.ci.Module;
import org.jfrog.gradle.plugin.artifactory.GradleFunctionalTestBase;
import org.jfrog.gradle.plugin.artifactory.TestConsts;
import org.jfrog.gradle.plugin.artifactory.utils.Utils;
import org.jfrog.gradle.plugin.artifactory.utils.ValidationUtils;
import org.testng.SkipException;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.gradle.testkit.runner.TaskOutcome.FAILED;
import static org.jfrog.gradle.plugin.artifactory.TestConsts.EXPECTED_ANDROID_ARTIFACTS;
import static org.jfrog.gradle.plugin.artifactory.TestConsts.GRADLE_ANDROID_VERSION;
import static org.jfrog.gradle.plugin.artifactory.utils.Utils.createDeployableArtifactsFile;
import static org.jfrog.gradle.plugin.artifactory.utils.ValidationUtils.getBuildInfo;
import static org.testng.Assert.*;

public class PluginAndroidTest extends GradleFunctionalTestBase {

    @BeforeMethod
    public void checkJavaVersion() {
        String javaVersion = System.getProperty("java.version");
        if (javaVersion.startsWith("1.8")) {
            throw new SkipException("Skipping Android test on Java 8");
        }
    }

    @Test
    public void androidTest() throws IOException {
        runAndroidPublishTest(GRADLE_ANDROID_VERSION, TestConsts.ANDROID_GRADLE_EXAMPLE, this::checkBuildResults);
    }

    @Test
    public void androidCiTest() throws IOException {
        runAndroidPublishCITest(GRADLE_ANDROID_VERSION, TestConsts.ANDROID_GRADLE_CI_EXAMPLE, true, (deployableArtifacts) -> Utils.generateBuildInfoProperties(this, "", true, true, ""),
                (buildResult, deployableArtifacts) -> checkBuildResults(buildResult));
    }

    /**
     * Android-specific publish test that skips configuration cache due to compatibility issues.
     */
    public void runAndroidPublishTest(String gradleVersion, Path sourceDir, ValidationUtils.BuildResultValidation validation) throws IOException {
        // Create test environment
        Utils.createTestDir(sourceDir);
        // Skip configuration cache for Android tests due to known compatibility issues
        // Run Gradle
        BuildResult buildResult = Utils.runGradleArtifactoryPublish(gradleVersion, envVars, false);
        validation.validate(buildResult);
        // Cleanup
        Pair<String, String> buildDetails = Utils.getBuildDetails(buildResult);
        Utils.cleanTestBuilds(artifactoryManager, buildDetails.getLeft(), buildDetails.getRight(), null);
    }

    /**
     * Android-specific CI publish test that skips init script due to Java 21 compatibility issues.
     */
    public void runAndroidPublishCITest(String gradleVersion, Path sourceDir, boolean cleanUp, TestEnvCreator testEnvCreator, ValidationUtils.CiBuildResultValidation validation) throws IOException {
        // Create test environment
        Utils.createTestDir(sourceDir);
        // Create build info properties file
        Path deployableArtifacts = createDeployableArtifactsFile();
        testEnvCreator.create(deployableArtifacts.toString());
        Map<String, String> extendedEnv = new HashMap<String, String>(envVars) {{
            put(BuildInfoConfigProperties.PROP_PROPS_FILE, TestConsts.BUILD_INFO_PROPERTIES_TARGET.toString());
        }};
        // Skip init script for Android tests due to Java 21 compatibility issues with jackson-core
        // Run Gradle without init script
        BuildResult buildResult = Utils.runGradleArtifactoryPublish(gradleVersion, extendedEnv, false);
        validation.validate(buildResult, deployableArtifacts);
        // Cleanup
        if (cleanUp) {
            Pair<String, String> buildDetails = Utils.getBuildDetails(buildResult);
            Utils.cleanTestBuilds(artifactoryManager, buildDetails.getLeft(), buildDetails.getRight(), null);
        }
        Files.deleteIfExists(deployableArtifacts);
    }

    /**
     * Check Gradle Android build results.
     *
     * @param buildResult - The build results
     * @throws IOException In case of any IO error.
     */
    public void checkBuildResults(BuildResult buildResult) throws IOException {
        // Assert all tasks ended with success outcome
        buildResult.getTasks().forEach(buildTask -> assertNotEquals(buildTask.getOutcome(), FAILED));

        // Check that all expected artifacts uploaded to Artifactory
        for (String expectedArtifact : EXPECTED_ANDROID_ARTIFACTS) {
            artifactoryManager.downloadHeaders(localRepo + "/gradle_tests_space/" + expectedArtifact);
        }

        // Check buildInfo info
        BuildInfo buildInfo = getBuildInfo(artifactoryManager, buildResult);
        assertNotNull(buildInfo);
        checkBuildInfoModules(buildInfo);

        // Check build info properties on published Artifacts
        PropertySearchResult artifacts = artifactoryManager.searchArtifactsByProperties(String.format("build.name=%s;build.number=%s", buildInfo.getName(), buildInfo.getNumber()));
        assertTrue(artifacts.getResults().size() >= 4);
    }

    /**
     * Check expected build info modules.
     *
     * @param buildInfo - The build info
     */
    public void checkBuildInfoModules(BuildInfo buildInfo) {
        List<Module> modules = buildInfo.getModules();
        assertEquals(modules.size(), 2);
        for (Module module : modules) {
            switch (module.getId()) {
                case "gradle_tests_space:app:1.0-SNAPSHOT":
                    assertEquals(module.getArtifacts().size(), 2);
                    assertEquals(module.getDependencies().size(), 44);
                    break;
                case "gradle_tests_space:library:1.0-SNAPSHOT":
                    assertEquals(module.getArtifacts().size(), 2);
                    assertEquals(module.getDependencies().size(), 41);
                    break;
                default:
                    fail("Unexpected module ID: " + module.getId());
            }
        }
    }
}
