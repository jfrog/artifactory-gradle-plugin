package org.jfrog.gradle.plugin.artifactory.utils;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.BuildTask;
import org.jfrog.build.api.dependency.PropertySearchResult;
import org.jfrog.build.api.util.CommonUtils;
import org.jfrog.build.extractor.ci.Artifact;
import org.jfrog.build.extractor.ci.BuildInfo;
import org.jfrog.build.extractor.ci.Dependency;
import org.jfrog.build.extractor.ci.Module;
import org.jfrog.build.extractor.clientConfiguration.client.artifactory.ArtifactoryManager;
import org.jfrog.gradle.plugin.artifactory.TestConstant;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.apache.commons.lang3.StringUtils.equalsAny;
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;
import static org.jfrog.build.extractor.BuildInfoExtractorUtils.jsonStringToBuildInfo;
import static org.testng.Assert.*;

public class ValidationUtils {
    public interface BuildResultValidation {
        void validate(BuildResult buildResult) throws IOException;
    }

    /**
     * Check build results of a Gradle project with publications.
     *
     * @param artifactoryManager - The ArtifactoryManager client
     * @param buildResult        - The build results
     * @param localRepo          - Local Maven repository in Artifactory
     * @throws IOException - In case of any IO error
     */
    public static void checkBuildResults(ArtifactoryManager artifactoryManager, BuildResult buildResult, String localRepo) throws IOException {
        checkBuildResults(artifactoryManager, buildResult, localRepo, TestConstant.EXPECTED_MODULE_ARTIFACTS, 5);
    }

    /**
     * Check build results of a Gradle project without publications.
     *
     * @param artifactoryManager - The ArtifactoryManager client
     * @param buildResult        - The build results
     * @param localRepo          - Local Maven repository in Artifactory
     * @throws IOException - In case of any IO error
     */
    public static void checkArchivesBuildResults(ArtifactoryManager artifactoryManager, BuildResult buildResult, String localRepo) throws IOException {
        checkBuildResults(artifactoryManager, buildResult, localRepo, TestConstant.EXPECTED_ARCHIVE_ARTIFACTS, 1);
    }

    private static void checkBuildResults(ArtifactoryManager artifactoryManager, BuildResult buildResult, String localRepo,
                                          String[] expectedArtifacts, int expectedArtifactsPerModule) throws IOException {
        // Assert all tasks ended with success outcome
        assertProjectsSuccess(buildResult);

        // Check that all expected artifacts uploaded to Artifactory
        for (String expectedArtifact : expectedArtifacts) {
            artifactoryManager.downloadHeaders(localRepo + TestConstant.ARTIFACTS_GROUP_ID + expectedArtifact);
        }

        // Check buildInfo info
        BuildInfo buildInfo = getBuildInfo(artifactoryManager, buildResult);
        assertNotNull(buildInfo);
        checkBuildInfoModules(buildInfo, 3, expectedArtifactsPerModule);

        // Check build info properties on published Artifacts
        PropertySearchResult artifacts = artifactoryManager.searchArtifactsByProperties(String.format("build.name=%s;build.number=%s", buildInfo.getName(), buildInfo.getNumber()));
        assertTrue(artifacts.getResults().size() >= expectedArtifacts.length);
    }

    private static void assertProjectsSuccess(BuildResult buildResult) {
        assertSuccess(buildResult, ":api:artifactoryPublish");
        assertSuccess(buildResult, ":shared:artifactoryPublish");
        assertSuccess(buildResult, ":services:webservice:artifactoryPublish");
        assertSuccess(buildResult, ":artifactoryPublish");
    }

    /**
     * Assert build success for task.
     *
     * @param buildResult - The build results
     * @param taskName    - The task name
     */
    private static void assertSuccess(BuildResult buildResult, String taskName) {
        BuildTask buildTask = buildResult.task(taskName);
        assertNotNull(buildTask);
        assertEquals(buildTask.getOutcome(), SUCCESS);
    }

    /**
     * Get build info from the build info URL.
     *
     * @param artifactoryManager - The ArtifactoryManager client
     * @param buildResult        - The build results
     * @return build info or null
     * @throws IOException - In case of any IO error
     */
    public static BuildInfo getBuildInfo(ArtifactoryManager artifactoryManager, BuildResult buildResult) throws IOException {
        Pair<String, String> buildDetails = Utils.getBuildDetails(buildResult);
        return artifactoryManager.getBuildInfo(buildDetails.getLeft(), buildDetails.getRight(), null);
    }

    /**
     * Check expected build info modules.
     *
     * @param buildInfo                  - The build info
     * @param expectedModules            - Number of expected modules.
     * @param expectedArtifactsPerModule - Number of expected artifacts in each module.
     */
    private static void checkBuildInfoModules(BuildInfo buildInfo, int expectedModules, int expectedArtifactsPerModule) {
        List<Module> modules = buildInfo.getModules();
        assertEquals(modules.size(), expectedModules);
        for (Module module : modules) {
            if (expectedArtifactsPerModule > 0) {
                assertEquals(module.getArtifacts().size(), expectedArtifactsPerModule);
            } else {
                assertNull(module.getArtifacts());
            }

            switch (module.getId()) {
                case "org.jfrog.test.gradle.publish:webservice:1.0-SNAPSHOT":
                    assertEquals(module.getDependencies().size(), 7);
                    if (expectedArtifactsPerModule > 0) {
                        checkWebserviceArtifact(module);
                    }
                    checkWebserviceDependency(module);
                    break;
                case "org.jfrog.test.gradle.publish:api:1.0-SNAPSHOT":
                    assertEquals(module.getDependencies().size(), 5);
                    break;
                case "org.jfrog.test.gradle.publish:shared:1.0-SNAPSHOT":
                    assertEquals(module.getDependencies().size(), 0);
                    break;
                default:
                    fail("Unexpected module ID: " + module.getId());
            }
        }
    }

    /**
     * Check webservice-1.0-SNAPSHOT.jar artifact under webservice module in publications mode.
     * Check webservice-1.0-SNAPSHOT.war artifact under webservice module in archives mode.
     *
     * @param webservice - The webservice module
     */
    private static void checkWebserviceArtifact(Module webservice) {
        Artifact webServiceJar = webservice.getArtifacts().stream()
                .filter(artifact -> equalsAny(artifact.getName(), "webservice-1.0-SNAPSHOT.jar", "webservice-1.0-SNAPSHOT.war"))
                .findAny().orElse(null);
        assertNotNull(webServiceJar);
        equalsAny(webServiceJar.getType(), "jar", "war");
        equalsAny(webServiceJar.getRemotePath(), "org/jfrog/test/gradle/publish/webservice/1.0-SNAPSHOT/webservice-1.0-SNAPSHOT.jar",
                "org/jfrog/test/gradle/publish/webservice/1.0-SNAPSHOT/webservice-1.0-SNAPSHOT.war");
        assertTrue(StringUtils.isNotBlank(webServiceJar.getMd5()));
        assertTrue(StringUtils.isNotBlank(webServiceJar.getSha1()));
        assertTrue(StringUtils.isNotBlank(webServiceJar.getSha256()));
    }

    /**
     * Check commons-collections:commons-collections:3.2 dependency under webservice module.
     *
     * @param webservice - The webservice module
     */
    private static void checkWebserviceDependency(Module webservice) {
        Dependency commonsCollections = webservice.getDependencies().stream()
                .filter(dependency -> StringUtils.equals(dependency.getId(), "commons-collections:commons-collections:3.2"))
                .findAny().orElse(null);
        assertNotNull(commonsCollections);
        assertEquals(commonsCollections.getType(), "jar");
        assertEquals(commonsCollections.getMd5(), "7b9216b608d550787bdf43a63d88bf3b");
        assertEquals(commonsCollections.getSha1(), "f951934aa5ae5a88d7e6dfaa6d32307d834a88be");
        assertEquals(commonsCollections.getSha256(), "093fea360752de55afcb80cf713403eb1a66cb7dc0d529955b6f4a96f975df5c");
        assertEquals(commonsCollections.getRequestedBy(), new String[][]{{"org.jfrog.test.gradle.publish:webservice:1.0-SNAPSHOT"}});
    }

    public static void checkArtifactsProps(ArtifactoryManager artifactoryManager) throws IOException {
        // Test single value prop
        PropertySearchResult artifacts = artifactoryManager.searchArtifactsByProperties("gradle.test.single.value.key=basic");
        assertTrue(artifacts.getResults().size() >= 12);
        // Test multi value props
        artifacts = artifactoryManager.searchArtifactsByProperties("gradle.test.multi.values.key=val1");
        assertTrue(artifacts.getResults().size() >= 12);
        artifacts = artifactoryManager.searchArtifactsByProperties("gradle.test.multi.values.key=val2");
        assertTrue(artifacts.getResults().size() >= 12);
        artifacts = artifactoryManager.searchArtifactsByProperties("gradle.test.multi.values.key=val3");
        assertTrue(artifacts.getResults().size() >= 12);
    }

    public static void checkLocalBuild(BuildResult buildResult, File buildInfoJson, int expectedModules, int expectedArtifactsPerModule) throws IOException {
        assertProjectsSuccess(buildResult);

        // Assert build info contains requestedBy information.
        assertTrue(buildInfoJson.exists());
        BuildInfo buildInfo = jsonStringToBuildInfo(CommonUtils.readByCharset(buildInfoJson, StandardCharsets.UTF_8));
        checkBuildInfoModules(buildInfo, expectedModules, expectedArtifactsPerModule);
        assertRequestedBy(buildInfo);
    }

    private static void assertRequestedBy(BuildInfo buildInfo) {
        List<Dependency> apiDependencies = buildInfo.getModule("org.jfrog.test.gradle.publish:api:1.0-SNAPSHOT").getDependencies();
        assertEquals(apiDependencies.size(), 5);
        for (Dependency dependency : apiDependencies) {
            if (dependency.getId().equals("commons-io:commons-io:1.2")) {
                String[][] requestedBy = dependency.getRequestedBy();
                assertNotNull(requestedBy);
                assertEquals(requestedBy.length, 1);
                assertEquals(requestedBy[0].length, 2);
                assertEquals(requestedBy[0][0], "org.apache.commons:commons-lang3:3.12.0");
                assertEquals(requestedBy[0][1], "org.jfrog.test.gradle.publish:api:1.0-SNAPSHOT");
            }
        }
    }

    public static void checkBomBuild(BuildResult buildResult, File buildInfoJson, int expectedArtifacts) throws IOException {
        assertSuccess(buildResult, ":artifactoryPublish");

        // Assert build info.
        assertTrue(buildInfoJson.exists());
        BuildInfo buildInfo = jsonStringToBuildInfo(CommonUtils.readByCharset(buildInfoJson, StandardCharsets.UTF_8));
        Module module = buildInfo.getModule("org.jfrog.test.gradle:gradle_tests_space:1.0-SNAPSHOT");
        assertNotNull(module);
        assertEquals(module.getArtifacts().size(), expectedArtifacts);
        assertTrue(module.getArtifacts().stream().map(Artifact::getName)
                .anyMatch(artifactName -> artifactName.equals("gradle_tests_space-1.0-SNAPSHOT.pom")));
    }

}
