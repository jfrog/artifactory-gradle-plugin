package org.jfrog.buildinfo;

import org.testng.annotations.DataProvider;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

public class FunctionalTestBase {
    // Root directories
    private static final Path PROJECTS_ROOT = Paths.get("src", "functionalTest", "resources");
    public static final File EMPTY_PROJECT = PROJECTS_ROOT.resolve("empty").toFile();

    @DataProvider
    public Object[][] gradleVersions() {
        return new Object[][]{{"6.9"}, {"7.4.2"}, {"7.6"}, {"8.1"}};
    }

}
