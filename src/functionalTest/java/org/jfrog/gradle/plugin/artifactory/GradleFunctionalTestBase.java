package org.jfrog.gradle.plugin.artifactory;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.text.StringSubstitutor;
import org.gradle.testkit.runner.BuildResult;
import org.jfrog.build.api.BuildInfoConfigProperties;
import org.jfrog.build.api.util.Log;
import org.jfrog.build.extractor.clientConfiguration.client.artifactory.ArtifactoryManager;
import org.jfrog.gradle.plugin.artifactory.utils.TestingLog;
import org.jfrog.gradle.plugin.artifactory.utils.Utils;
import org.jfrog.gradle.plugin.artifactory.utils.ValidationUtils;
import org.testng.annotations.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;

import static org.jfrog.gradle.plugin.artifactory.Constant.*;

public class GradleFunctionalTestBase {
    // ArtifactoryManager
    protected ArtifactoryManager artifactoryManager;
    protected static final Log log = new TestingLog();
    private String username;
    private String adminToken;
    private String platformUrl;
    private String artifactoryUrl;

    // Test repositories
    public final String localRepo = getKeyWithTimestamp(TestConstant.GRADLE_LOCAL_REPO);
    public final String virtualRepo = getKeyWithTimestamp(TestConstant.GRADLE_VIRTUAL_REPO);
    protected String remoteRepo = getKeyWithTimestamp(TestConstant.GRADLE_REMOTE_REPO);

    // Test specific attributes
    private StringSubstitutor stringSubstitutor;
    public static final long CURRENT_TIME = System.currentTimeMillis();
    protected Map<String, String> envVars;

    @DataProvider
    public Object[][] gradleVersions() {
        return new Object[][]{{"6.8.1"}, {"7.5.1"}, {"7.6"}, {"8.1"}};
    }

    @BeforeClass
    public void init() throws IOException {
        initArtifactoryManager();
        createTestRepositories();
        initGradleCmdEnvVars();
    }

    @BeforeMethod
    @AfterMethod
    protected void cleanup() throws IOException {
        deleteTestDir();
        deleteContentFromRepo(localRepo);
    }

    @AfterClass
    protected void terminate() throws IOException {
        // Delete the virtual first.
        if (StringUtils.isNotEmpty(virtualRepo)) {
            deleteTestRepo(virtualRepo);
        }
        if (StringUtils.isNotEmpty(remoteRepo)) {
            deleteTestRepo(remoteRepo);
        }
        if (StringUtils.isNotEmpty(localRepo)) {
            deleteTestRepo(localRepo);
        }
        artifactoryManager.close();
    }

    public void runPublishTest(String gradleVersion, Path sourceDir, ValidationUtils.BuildResultValidation validation) throws IOException {
        // Create test environment
        Utils.createTestDir(sourceDir);
        // Run Gradle
        BuildResult buildResult = Utils.runGradleArtifactoryPublish(gradleVersion, envVars, false);
        validation.validate(buildResult);
        // Cleanup
        Pair<String, String> buildDetails = Utils.getBuildDetails(buildResult);
        Utils.cleanTestBuilds(artifactoryManager, buildDetails.getLeft(), buildDetails.getRight(), null);
    }

    public interface TestEnvCreator {
        void create() throws IOException;
    }

    public void runPublishCITest(String gradleVersion, Path sourceDir, boolean cleanUp, TestEnvCreator testEnvCreator, ValidationUtils.BuildResultValidation validation) throws IOException {
        // Create test environment
        Utils.createTestDir(sourceDir);
        testEnvCreator.create();
        Map<String, String> extendedEnv = new HashMap<>(envVars) {{
            put(BuildInfoConfigProperties.PROP_PROPS_FILE, TestConstant.BUILD_INFO_PROPERTIES_TARGET.toString());
            put(RESOLUTION_URL_ENV, getArtifactoryUrl() + virtualRepo);
            put(RESOLUTION_USERNAME_ENV, getUsername());
            put(RESOLUTION_PASSWORD_ENV, getAdminToken());
        }};
        // Run Gradle
        BuildResult buildResult = Utils.runGradleArtifactoryPublish(gradleVersion, extendedEnv, true);
        validation.validate(buildResult);
        // Cleanup
        if (cleanUp) {
            Pair<String, String> buildDetails = Utils.getBuildDetails(buildResult);
            Utils.cleanTestBuilds(artifactoryManager, buildDetails.getLeft(), buildDetails.getRight(), null);
        }
    }

    private void initArtifactoryManager() {
        // URL
        platformUrl = Utils.readParam(TestConstant.URL, TestConstant.DEFAULT_URL);
        if (!platformUrl.endsWith("/")) {
            platformUrl += "/";
        }
        artifactoryUrl = platformUrl + Constant.ARTIFACTORY + "/";
        // Credentials
        username = Utils.readParam(TestConstant.USERNAME, TestConstant.DEFAULT_USERNAME);
        adminToken = Utils.readParam(TestConstant.ADMIN_TOKEN, TestConstant.DEFAULT_PASS);
        // Create
        artifactoryManager = createArtifactoryManager();
    }

    private ArtifactoryManager createArtifactoryManager() {
        return new ArtifactoryManager(artifactoryUrl, username, adminToken, log);
    }

    private void createTestRepositories() throws IOException {
        createStringSubstitutor();
        if (StringUtils.isNotEmpty(localRepo)) {
            createTestRepo(localRepo);
        }
        if (StringUtils.isNotEmpty(remoteRepo)) {
            createTestRepo(remoteRepo);
        }
        if (StringUtils.isNotEmpty(virtualRepo)) {
            createTestRepo(virtualRepo);
        }
    }

    private void createStringSubstitutor() {
        Map<String, Object> textParameters = new HashMap<>();
        textParameters.put(TestConstant.LOCAL_REPO, localRepo);
        textParameters.put(TestConstant.REMOTE_REPO, remoteRepo);
        stringSubstitutor = new StringSubstitutor(textParameters);
    }

    protected void createTestRepo(String repoKey) throws IOException {
        if (artifactoryManager.isRepositoryExist(repoKey)) {
            return;
        }
        String path = "/settings/" + StringUtils.substringBeforeLast(repoKey, "-") + ".json";
        try (InputStream repoConfigInputStream = this.getClass().getResourceAsStream(path)) {
            if (repoConfigInputStream == null) {
                throw new IOException("Couldn't find repository settings in " + path);
            }
            String json = IOUtils.toString(repoConfigInputStream, StandardCharsets.UTF_8);
            artifactoryManager.createRepository(repoKey, stringSubstitutor.replace(json));
        }
    }

    /**
     * Get repository key with timestamp: key-[timestamp]
     *
     * @param key - The raw key
     * @return key with timestamp
     */
    protected static String getKeyWithTimestamp(String key) {
        return key + "-" + CURRENT_TIME;
    }

    /**
     * Return true if the build was created more than 24 hours ago.
     *
     * @param buildMatcher - Build regex matcher on BUILD_NUMBER_PATTERN
     * @return true if the Build was created more than 24 hours ago
     */
    public static boolean isOldBuild(Matcher buildMatcher) {
        long repoTimestamp = Long.parseLong(buildMatcher.group(1));
        return TimeUnit.MILLISECONDS.toHours(CURRENT_TIME - repoTimestamp) >= 24;
    }

    private void initGradleCmdEnvVars() {
        // Create env vars to pass for running gradle commands (var replacement in build.gradle files?
        envVars = new HashMap<>(System.getenv()) {{
            putIfAbsent(TestConstant.BITESTS_ENV_VAR_PREFIX + TestConstant.URL, getPlatformUrl());
            putIfAbsent(TestConstant.BITESTS_ENV_VAR_PREFIX + TestConstant.USERNAME, getUsername());
            putIfAbsent(TestConstant.BITESTS_ENV_VAR_PREFIX + TestConstant.ADMIN_TOKEN, getAdminToken());
            putIfAbsent(TestConstant.BITESTS_ARTIFACTORY_ENV_VAR_PREFIX + TestConstant.LOCAL_REPO, localRepo);
            putIfAbsent(TestConstant.BITESTS_ARTIFACTORY_ENV_VAR_PREFIX + TestConstant.VIRTUAL_REPO, virtualRepo);
        }};
    }

    /**
     * Delete all content from the given repository.
     *
     * @param repoKey - repository key
     */
    protected void deleteContentFromRepo(String repoKey) throws IOException {
        if (!artifactoryManager.isRepositoryExist(repoKey)) {
            return;
        }
        artifactoryManager.deleteRepositoryContent(repoKey);
    }

    /**
     * Delete the tests directories
     *
     * @throws IOException - In case of any IO error
     */
    protected static void deleteTestDir() throws IOException {
        FileUtils.deleteDirectory(TestConstant.TEST_DIR);
    }

    /**
     * Delete repository.
     *
     * @param repo - repository name
     * @throws IOException in case of any I/O error.
     */
    protected void deleteTestRepo(String repo) throws IOException {
        artifactoryManager.deleteRepository(repo);
    }

    public String getUsername() {
        return this.username;
    }

    public String getAdminToken() {
        return this.adminToken;
    }

    public String getPlatformUrl() {
        return this.platformUrl;
    }

    public String getArtifactoryUrl() {
        return this.artifactoryUrl;
    }

}
