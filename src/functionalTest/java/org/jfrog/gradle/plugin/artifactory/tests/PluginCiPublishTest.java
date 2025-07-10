package org.jfrog.gradle.plugin.artifactory.tests;

import org.jfrog.build.client.Version;
import org.jfrog.gradle.plugin.artifactory.GradleFunctionalTestBase;
import org.jfrog.gradle.plugin.artifactory.TestConsts;
import org.jfrog.gradle.plugin.artifactory.utils.Utils;
import org.jfrog.gradle.plugin.artifactory.utils.ValidationUtils;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.io.IOException;

import static org.jfrog.gradle.plugin.artifactory.TestConsts.EXPECTED_VERSION_CATALOG_PRODUCER_ARTIFACTS;
import static org.jfrog.gradle.plugin.artifactory.TestConsts.MIN_GRADLE_VERSION_CATALOG_VERSION;

public class PluginCiPublishTest extends GradleFunctionalTestBase {
    @Test(dataProvider = "gradleVersions")
    public void ciServerTest(String gradleVersion) throws IOException {
        runPublishCITest(gradleVersion, TestConsts.GRADLE_EXAMPLE_CI_SERVER, true,
                (deployableArtifacts) -> Utils.generateBuildInfoProperties(this, "", true, true, deployableArtifacts),
                (buildResult, deployableArtifacts) -> ValidationUtils.checkBuildResults(artifactoryManager, buildResult, localRepo, deployableArtifacts)
        );
    }

    @Test(dataProvider = "gradleVersions")
    public void ciServerResolverOnlyTest(String gradleVersion) throws IOException {
        runPublishCITest(gradleVersion, TestConsts.GRADLE_EXAMPLE_CI_SERVER, false,
                (deployableArtifacts) -> Utils.generateBuildInfoProperties(this, "", false, false, ""),
                (buildResult, deployableArtifacts) -> ValidationUtils.checkLocalBuild(buildResult, TestConsts.BUILD_INFO_JSON.toFile(), 2, 0)
        );
    }

    @Test(dataProvider = "gradleVersions")
    public void ciServerPublicationsTest(String gradleVersion) throws IOException {
        runPublishCITest(gradleVersion, TestConsts.GRADLE_EXAMPLE_CI_SERVER, true,
                (deployableArtifacts) -> Utils.generateBuildInfoProperties(this, "mavenJava,customIvyPublication", true, true, deployableArtifacts),
                (buildResult, deployableArtifacts) -> ValidationUtils.checkBuildResults(artifactoryManager, buildResult, localRepo, deployableArtifacts)
        );
    }

    @Test(dataProvider = "gradleVersions")
    public void ciRequestedByTest(String gradleVersion) throws IOException {
        runPublishCITest(gradleVersion, TestConsts.GRADLE_EXAMPLE_CI_SERVER, false,
                (deployableArtifacts) -> Utils.generateBuildInfoProperties(this, "mavenJava,customIvyPublication", false, true, ""),
                (buildResult, deployableArtifacts) -> ValidationUtils.checkLocalBuild(buildResult, TestConsts.BUILD_INFO_JSON.toFile(), 3, 5)
        );
    }

    @Test(dataProvider = "gradleVersions")
    public void ciServerArchivesTest(String gradleVersion) throws IOException {
        runPublishCITest(gradleVersion, TestConsts.GRADLE_EXAMPLE_CI_SERVER_ARCHIVES, true,
                (deployableArtifacts) -> Utils.generateBuildInfoProperties(this, "", true, true, ""),
                (buildResult, deployableArtifacts) -> ValidationUtils.checkArchivesBuildResults(artifactoryManager, buildResult, localRepo, gradleVersion)
        );
    }

    @Test(dataProvider = "gradleVersions")
    public void versionCatalogTest(String gradleVersion) throws IOException {
        if (!new Version(gradleVersion).isAtLeast(new Version(MIN_GRADLE_VERSION_CATALOG_VERSION))) {
            throw new SkipException("Version catalog test requires at least Gradle version " + MIN_GRADLE_VERSION_CATALOG_VERSION);
        }
        runPublishCITest(gradleVersion, TestConsts.GRADLE_EXAMPLE_VERSION_CATALOG_PRODUCER, false,
                (deployableArtifacts) -> Utils.generateBuildInfoProperties(this, "versionCatalogProducer", false, true, ""),
                (buildResult, deployableArtifacts) -> ValidationUtils.verifyArtifacts(artifactoryManager, localRepo + "/", EXPECTED_VERSION_CATALOG_PRODUCER_ARTIFACTS)
        );

        runPublishCITest(gradleVersion, TestConsts.GRADLE_EXAMPLE_VERSION_CATALOG_CONSUMER, true,
                (deployableArtifacts) -> Utils.generateBuildInfoProperties(this, "versionCatalogConsumer", true, true, ""),
                (buildResult, deployableArtifacts) -> ValidationUtils.checkVersionCatalogResults(artifactoryManager, buildResult, virtualRepo)
        );
    }

    @Test(dataProvider = "gradleVersions")
    public void ciServerFlatDirTest(String gradleVersion) throws IOException {
        runPublishCITest(gradleVersion, TestConsts.GRADLE_EXAMPLE_CI_SERVER_FLAT, false,
                (deployableArtifacts) -> Utils.generateBuildInfoProperties(this, "", false, false, ""),
                (buildResult, deployableArtifacts) -> ValidationUtils.checkBuildResultsFlatDir(buildResult, TestConsts.BUILD_INFO_JSON.toFile())
        );
    }
}
