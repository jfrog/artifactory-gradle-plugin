package org.jfrog.gradle.plugin.artifactory.tests;

import org.apache.commons.io.FileUtils;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.BuildTask;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.jfrog.build.extractor.ci.BuildInfo;
import org.jfrog.build.extractor.ci.Module;
import org.jfrog.gradle.plugin.artifactory.TestConsts;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.jfrog.build.extractor.BuildInfoExtractorUtils.jsonStringToBuildInfo;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;

/**
 * Publishing only a subproject of a multi-project build ('gradle :api:artifactoryPublish') must succeed and
 * produce a build-info that contains only the published subproject (issue #175).
 * <p>
 * Publishing to Artifactory is disabled in the test project, so no Artifactory instance is needed: the
 * build-info is only exported to build/build-info.json.
 */
public class PluginPublishSubprojectTest {
    private static final Path PROJECT = TestConsts.PROJECTS_ROOT.resolve("gradle-example-publish-subproject");
    private static final String API_MODULE = "org.jfrog.test.gradle.publish:api:1.0-SNAPSHOT";
    private static final String LIB_MODULE = "org.jfrog.test.gradle.publish:lib:1.0-SNAPSHOT";

    private File projectDir;

    @DataProvider
    public Object[][] gradleVersions() {
        return new Object[][]{{"9.0.0-milestone-9"}, {"8.8"}};
    }

    @BeforeMethod
    public void createProjectDir() throws IOException {
        projectDir = Files.createTempDirectory("gradle_publish_subproject").toFile();
        FileUtils.copyDirectory(PROJECT.toFile(), projectDir);
    }

    @AfterMethod
    public void deleteProjectDir() throws IOException {
        FileUtils.deleteDirectory(projectDir);
    }

    @Test(dataProvider = "gradleVersions")
    public void publishingSubprojectOnlyContainsSubprojectModule(String gradleVersion) throws IOException {
        BuildResult result = run(gradleVersion, ":api:artifactoryPublish");

        assertNoTaskFailed(result);
        assertEquals(buildInfoModuleIds(), List.of(API_MODULE));
    }

    @Test(dataProvider = "gradleVersions")
    public void publishingSubprojectIgnoresModuleInfoOfPreviousBuild(String gradleVersion) throws IOException {
        run(gradleVersion, "artifactoryPublish");
        assertEquals(buildInfoModuleIds(), List.of(API_MODULE, LIB_MODULE));

        BuildResult result = run(gradleVersion, ":api:artifactoryPublish");

        assertNoTaskFailed(result);
        assertEquals(buildInfoModuleIds(), List.of(API_MODULE));
        assertFalse(new File(projectDir, "lib/build/moduleInfo.json").exists(),
                "Stale module info of the not published subproject must be deleted");
    }

    @Test(dataProvider = "gradleVersions")
    public void publishingSubprojectWorksWithConfigurationCache(String gradleVersion) throws IOException {
        run(gradleVersion, ":api:artifactoryPublish", "--configuration-cache", "--configuration-cache-problems=fail");
        BuildResult reuseResult = run(gradleVersion, ":api:artifactoryPublish", "--configuration-cache", "--configuration-cache-problems=fail");

        assertTrue(reuseResult.getOutput().contains("Reusing configuration cache"),
                "Second run must reuse the configuration cache. Output:\n" + reuseResult.getOutput());
        assertNoTaskFailed(reuseResult);
        assertEquals(buildInfoModuleIds(), List.of(API_MODULE));
    }

    private BuildResult run(String gradleVersion, String... tasks) {
        List<String> arguments = new ArrayList<>(Arrays.asList(tasks));
        arguments.add("--stacktrace");
        //noinspection UnstableApiUsage
        return GradleRunner.create()
                .withGradleVersion(gradleVersion)
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments(arguments)
                .build();
    }

    private static void assertNoTaskFailed(BuildResult result) {
        for (BuildTask task : result.getTasks()) {
            assertNotEquals(task.getOutcome(), TaskOutcome.FAILED, "Task " + task.getPath() + " failed");
        }
    }

    private List<String> buildInfoModuleIds() throws IOException {
        String json = FileUtils.readFileToString(new File(projectDir, "build/build-info.json"), StandardCharsets.UTF_8);
        BuildInfo buildInfo = jsonStringToBuildInfo(json);
        return buildInfo.getModules().stream().map(Module::getId).sorted().collect(Collectors.toList());
    }
}
