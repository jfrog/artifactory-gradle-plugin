package org.jfrog.gradle.plugin.artifactory.tests;

import org.gradle.testkit.runner.BuildResult;
import org.jfrog.build.api.util.CommonUtils;
import org.jfrog.build.client.Version;
import org.jfrog.build.extractor.ci.BuildInfo;
import org.jfrog.build.extractor.ci.Dependency;
import org.jfrog.gradle.plugin.artifactory.GradleFunctionalTestBase;
import org.jfrog.gradle.plugin.artifactory.TestConsts;
import org.jfrog.gradle.plugin.artifactory.utils.Utils;
import org.jfrog.gradle.plugin.artifactory.utils.ValidationUtils;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.gradle.testkit.runner.TaskOutcome.FAILED;
import static org.jfrog.build.extractor.BuildInfoExtractorUtils.jsonStringToBuildInfo;
import static org.jfrog.gradle.plugin.artifactory.TestConsts.*;
import static org.testng.Assert.*;

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
                (buildResult, deployableArtifacts) -> ValidationUtils.checkArchivesBuildResults(artifactoryManager, buildResult, localRepo)
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
                (buildResult, deployableArtifacts) -> checkBuildResultsFlatDir(buildResult, TestConsts.BUILD_INFO_JSON.toFile())
        );
    }

    /**
     * Check the results of CI server with flatDir test.
     * Aim of the test is to verify flatDir repositories are still respected when applying the Artifactory plugin which shouldn't override them.
     *
     * @param buildResult - The build results
     * @throws IOException In case of any IO error.
     */
    public void checkBuildResultsFlatDir(BuildResult buildResult, File buildInfoJson) throws IOException {
        // Assert all tasks ended with success outcome
        buildResult.getTasks().forEach(buildTask -> assertNotEquals(buildTask.getOutcome(), FAILED));

        // Assert build info contains both the remote and the local dependencies.
        assertTrue(buildInfoJson.exists());
        BuildInfo buildInfo = jsonStringToBuildInfo(CommonUtils.readByCharset(buildInfoJson, StandardCharsets.UTF_8));
        assertEquals(buildInfo.getModules().size(), 1);
        assertEquals(buildInfo.getModules().get(0).getDependencies().size(), 3);
        String[] dependenciesIds = buildInfo.getModules().get(0).getDependencies().stream().map(Dependency::getId).toArray(String[]::new);
        assertEqualsNoOrder(dependenciesIds, EXPECTED_FLAT_DIR_DEPENDENCIES_IDS);
    }
}
