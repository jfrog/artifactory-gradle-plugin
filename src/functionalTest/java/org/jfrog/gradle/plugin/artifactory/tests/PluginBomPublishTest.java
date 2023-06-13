package org.jfrog.gradle.plugin.artifactory.tests;

import org.jfrog.gradle.plugin.artifactory.GradleFunctionalTestBase;
import org.jfrog.gradle.plugin.artifactory.TestConstant;
import org.jfrog.gradle.plugin.artifactory.utils.Utils;
import org.jfrog.gradle.plugin.artifactory.utils.ValidationUtils;
import org.testng.annotations.Test;

import java.io.IOException;

/**
 * A test for publishing bom files (pom.xml with dependencyManagement) using the default mavenJavaPlatform/customMavenJavaPlatform publication.
 */
public class PluginBomPublishTest extends GradleFunctionalTestBase {

    @Test(dataProvider = "gradleVersions")
    public void publishDefaultBomTest(String gradleVersion) throws IOException {
        runPublishCITest(gradleVersion, TestConstant.GRADLE_EXAMPLE_DEFAULT_BOM, true, () -> {
            Utils.generateBuildInfoProperties(this, "", true, true);
        }, buildResult -> {
            ValidationUtils.checkBomBuild(buildResult, TestConstant.BUILD_INFO_JSON.toFile(), 2);
        });
    }

    @Test(dataProvider = "gradleVersions")
    public void publishCustomBomTest(String gradleVersion) throws IOException {
        runPublishCITest(gradleVersion, TestConstant.GRADLE_EXAMPLE_CUSTOM_BOM, true, () -> {
            Utils.generateBuildInfoProperties(this, "customMavenJavaPlatform", true, true);
        }, buildResult -> {
            ValidationUtils.checkBomBuild(buildResult, TestConstant.BUILD_INFO_JSON.toFile(), 2);
        });
    }
}
