package org.jfrog.buildinfo.tasks;

import org.gradle.testkit.runner.BuildResult;
import org.jfrog.buildinfo.FunctionalTestBase;
import org.jfrog.buildinfo.Utils;
import org.testng.annotations.Test;

public class HelloWorldTaskTest extends FunctionalTestBase {
    @Test(dataProvider = "gradleVersions")
    public void testHelloWorld(String gradleVersion) {
        BuildResult result = Utils.runGradle(gradleVersion, FunctionalTestBase.EMPTY_PROJECT,"helloWorld", "clean");
        Utils.assertSuccess(result);
    }
}
