package org.jfrog.gradle.plugin.artifactory.tests;

import org.jfrog.build.extractor.ci.BuildInfo;
import org.jfrog.build.extractor.ci.Module;
import org.jfrog.gradle.plugin.artifactory.GradleFunctionalTestBase;
import org.jfrog.gradle.plugin.artifactory.TestConsts;
import org.jfrog.gradle.plugin.artifactory.utils.ValidationUtils;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * End-to-end check that a dependency declared only in a shared convention plugin
 * (buildSrc or an included build-logic build) still shows up in the published
 * build-info for the subproject that applies it, rather than only being usable
 * at compile time.
 */
public class SharedBuildLogicSlf4jTest extends GradleFunctionalTestBase {

    @DataProvider
    public Object[][] sharedBuildLogicProjects() {
        return new Object[][]{
                {TestConsts.PROJECTS_ROOT.resolve("gradle-example-buildsrc-dependency")},
                {TestConsts.PROJECTS_ROOT.resolve("gradle-example-build-logic-dependency")}
        };
    }

    @Test(dataProvider = "sharedBuildLogicProjects")
    public void sharedConventionSlf4jPropagationTest(Path projectDir) throws IOException {
        runPublishTest("9.0.0-milestone-9", projectDir, buildResult -> {
            BuildInfo buildInfo = ValidationUtils.getBuildInfo(artifactoryManager, buildResult);
            assertNotNull(buildInfo);
            Module apiModule = buildInfo.getModule("com.example:api:1.0.0");
            assertNotNull(apiModule, "api module missing from build-info");
            assertTrue(apiModule.getDependencies().stream().anyMatch(d -> d.getId().startsWith("org.slf4j:slf4j-api")),
                    "slf4j-api (declared only in the shared convention plugin) missing from api module's dependencies");
        });
    }
}
