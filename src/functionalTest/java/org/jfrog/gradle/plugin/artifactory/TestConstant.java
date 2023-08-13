package org.jfrog.gradle.plugin.artifactory;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class TestConstant {
    // Root paths
    public static final Path GRADLE_EXTRACTOR = Paths.get(".").normalize().toAbsolutePath();
    public static final Path GRADLE_EXTRACTOR_SRC = GRADLE_EXTRACTOR.resolve("src");
    public static final Path PROJECTS_ROOT = GRADLE_EXTRACTOR_SRC.resolve(Paths.get("functionalTest", "resources"));
    public static final File TEST_DIR = new File(System.getProperty("java.io.tmpdir"), "gradle_tests_space");

    // CI paths
    public static final Path LIBS_DIR = GRADLE_EXTRACTOR.resolve(Paths.get("build", "libs"));
    public static final Path INIT_SCRIPT = GRADLE_EXTRACTOR_SRC.resolve(Paths.get("main", "resources", "initscripttemplate.gradle"));
    public static final Path BUILD_INFO_PROPERTIES_SOURCE_DEPLOYER = PROJECTS_ROOT.resolve(Paths.get("settings", "buildinfo.properties.deployer"));
    public static final Path BUILD_INFO_PROPERTIES_SOURCE_RESOLVER = PROJECTS_ROOT.resolve(Paths.get("settings", "buildinfo.properties.resolver"));

    // Test/Example Projects
    public static final Path ANDROID_GRADLE_EXAMPLE = PROJECTS_ROOT.resolve("gradle-android-example");
    public static final Path ANDROID_GRADLE_CI_EXAMPLE = PROJECTS_ROOT.resolve("gradle-android-ci-example");
    public static final Path GRADLE_EXAMPLE_PUBLISH = PROJECTS_ROOT.resolve("gradle-example-publish");
    public static final Path GRADLE_KTS_EXAMPLE_PUBLISH = PROJECTS_ROOT.resolve("gradle-kts-example-publish");
    public static final Path GRADLE_EXAMPLE_CI_SERVER = PROJECTS_ROOT.resolve("gradle-example-ci-server");
    public static final Path GRADLE_EXAMPLE_DEFAULT_BOM = PROJECTS_ROOT.resolve("gradle-example-default-bom");
    public static final Path GRADLE_EXAMPLE_CUSTOM_BOM = PROJECTS_ROOT.resolve("gradle-example-custom-bom");

    // Repositories
    public static final String LOCAL_REPO = "LOCAL_REPO";
    public static final String REMOTE_REPO = "REMOTE_REPO";
    public static final String VIRTUAL_REPO = "VIRTUAL_REPO";
    public static final String GRADLE_LOCAL_REPO = "build-info-tests-gradle-local";
    public static final String GRADLE_REMOTE_REPO = "build-info-tests-gradle-remote";
    public static final String GRADLE_VIRTUAL_REPO = "build-info-tests-gradle-virtual";

    // Env vars
    public static final String BITESTS_ENV_VAR_PREFIX = "BITESTS_PLATFORM_";
    public static final String URL = "URL";
    public static final String USERNAME = "USERNAME";
    public static final String ADMIN_TOKEN = "ADMIN_TOKEN";
    public static final String BITESTS_ARTIFACTORY_ENV_VAR_PREFIX = "BITESTS_ARTIFACTORY_";

    // Values
    public static final String DEFAULT_URL = "http://127.0.0.1:8081";
    public static final String DEFAULT_USERNAME = "admin";
    public static final String DEFAULT_PASS = "password";
    public static final Pattern BUILD_NUMBER_PATTERN = Pattern.compile("^/(\\d+)$");
    public static final Path BUILD_INFO_PROPERTIES_TARGET = TEST_DIR.toPath().resolve("buildinfo.properties");
    // Build info json path if not published
    public static final Path BUILD_INFO_JSON = TEST_DIR.toPath().resolve(Paths.get("build", "build-info.json"));

    // Android
    public static final String GRADLE_ANDROID_VERSION = "8.2.1";

    // Results
    public static final String ARTIFACTS_GROUP_ID = "/org/jfrog/test/gradle/publish/";
    public static final String[] EXPECTED_ARTIFACTS = {
            "api/ivy-1.0-SNAPSHOT.xml",
            "api/1.0-SNAPSHOT/api-1.0-SNAPSHOT.jar",
            "api/1.0-SNAPSHOT/api-1.0-SNAPSHOT.pom",
            "api/1.0-SNAPSHOT/api-1.0-SNAPSHOT.properties",
            "shared/ivy-1.0-SNAPSHOT.xml",
            "shared/1.0-SNAPSHOT/shared-1.0-SNAPSHOT.jar",
            "shared/1.0-SNAPSHOT/shared-1.0-SNAPSHOT.pom",
            "shared/1.0-SNAPSHOT/shared-1.0-SNAPSHOT.properties",
            "webservice/ivy-1.0-SNAPSHOT.xml",
            "webservice/1.0-SNAPSHOT/webservice-1.0-SNAPSHOT.jar",
            "webservice/1.0-SNAPSHOT/webservice-1.0-SNAPSHOT.pom",
            "webservice/1.0-SNAPSHOT/webservice-1.0-SNAPSHOT.properties"
    };
    public static final String[] EXPECTED_MODULE_ARTIFACTS = Stream.concat(
                    Stream.of(EXPECTED_ARTIFACTS),
                    Stream.of(
                            "api/1.0-SNAPSHOT/api-1.0-SNAPSHOT.module",
                            "shared/1.0-SNAPSHOT/shared-1.0-SNAPSHOT.module",
                            "webservice/1.0-SNAPSHOT/webservice-1.0-SNAPSHOT.module")).
            toArray(String[]::new);

    public static final String[] EXPECTED_ANDROID_ARTIFACTS = {
            "library/1.0-SNAPSHOT/library-1.0-SNAPSHOT.aar",
            "app/1.0-SNAPSHOT/app-1.0-SNAPSHOT.apk",
            "library/1.0-SNAPSHOT/library-1.0-SNAPSHOT.pom",
            "app/1.0-SNAPSHOT/app-1.0-SNAPSHOT.pom"
    };
}
