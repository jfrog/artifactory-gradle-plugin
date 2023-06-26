package org.jfrog.gradle.plugin.artifactory.tests;

import org.jfrog.gradle.plugin.artifactory.GradleFunctionalTestBase;
import org.jfrog.gradle.plugin.artifactory.TestConstant;
import org.jfrog.gradle.plugin.artifactory.utils.Utils;
import org.jfrog.gradle.plugin.artifactory.utils.ValidationUtils;
import org.testng.annotations.Test;

import java.io.IOException;

public class PluginCiPublishTest extends GradleFunctionalTestBase {
    @Test(dataProvider = "gradleVersions")
    public void ciServerTest(String gradleVersion) throws IOException {
        runPublishCITest(gradleVersion, TestConstant.GRADLE_EXAMPLE_CI_SERVER, true,
                () -> Utils.generateBuildInfoProperties(this, "", true, true),
                buildResult -> ValidationUtils.checkBuildResults(artifactoryManager, buildResult, localRepo)
        );
    }

    @Test(dataProvider = "gradleVersions")
    public void ciServerResolverOnlyTest(String gradleVersion) throws IOException {
        runPublishCITest(gradleVersion, TestConstant.GRADLE_EXAMPLE_CI_SERVER, false,
                () -> Utils.generateBuildInfoProperties(this, "", false, false),
                buildResult -> ValidationUtils.checkLocalBuild(buildResult, TestConstant.BUILD_INFO_JSON.toFile(), 2, 0)
        );
    }

    @Test(dataProvider = "gradleVersions")
    public void ciServerPublicationsTest(String gradleVersion) throws IOException {
        runPublishCITest(gradleVersion, TestConstant.GRADLE_EXAMPLE_CI_SERVER, true,
                () -> Utils.generateBuildInfoProperties(this, "mavenJava,customIvyPublication", true, true),
                buildResult -> ValidationUtils.checkBuildResults(artifactoryManager, buildResult, localRepo)
        );
    }

    @Test(dataProvider = "gradleVersions")
    public void ciRequestedByTest(String gradleVersion) throws IOException {
        runPublishCITest(gradleVersion, TestConstant.GRADLE_EXAMPLE_CI_SERVER, false,
                () -> Utils.generateBuildInfoProperties(this, "mavenJava,customIvyPublication", false, true),
                buildResult -> ValidationUtils.checkLocalBuild(buildResult, TestConstant.BUILD_INFO_JSON.toFile(), 3, 5)
        );
    }
}
