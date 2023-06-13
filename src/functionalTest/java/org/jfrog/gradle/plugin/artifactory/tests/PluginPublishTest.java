package org.jfrog.gradle.plugin.artifactory.tests;

import org.jfrog.gradle.plugin.artifactory.GradleFunctionalTestBase;
import org.jfrog.gradle.plugin.artifactory.TestConstant;
import org.jfrog.gradle.plugin.artifactory.utils.ValidationUtils;
import org.testng.annotations.Test;

import java.io.IOException;

public class PluginPublishTest extends GradleFunctionalTestBase {
    @Test(dataProvider = "gradleVersions")
    public void publicationsTest(String gradleVersion) throws IOException {
        runPublishTest(gradleVersion, TestConstant.GRADLE_EXAMPLE_PUBLISH, buildResult -> {
            ValidationUtils.checkBuildResults(artifactoryManager, buildResult, localRepo);
            ValidationUtils.checkArtifactsProps(artifactoryManager);
        });
    }

    @Test(dataProvider = "gradleVersions")
    public void publicationsTestKotlinDsl(String gradleVersion) throws IOException {
        runPublishTest(gradleVersion, TestConstant.GRADLE_KTS_EXAMPLE_PUBLISH, buildResult -> {
            ValidationUtils.checkBuildResults(artifactoryManager, buildResult, localRepo);
            ValidationUtils.checkArtifactsProps(artifactoryManager);
        });
    }
}
