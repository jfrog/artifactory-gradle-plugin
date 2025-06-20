package org.jfrog.gradle.plugin.artifactory;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.text.StringSubstitutor;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.BuildTask;
import org.gradle.testkit.runner.TaskOutcome;
import org.jfrog.build.api.BuildInfoConfigProperties;
import org.jfrog.build.api.util.Log;
import org.jfrog.build.client.Version;
import org.jfrog.build.extractor.clientConfiguration.client.artifactory.ArtifactoryManager;
import org.jfrog.gradle.plugin.artifactory.utils.TestingLog;
import org.jfrog.gradle.plugin.artifactory.utils.Utils;
import org.jfrog.gradle.plugin.artifactory.utils.ValidationUtils;
import org.testng.annotations.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;

import static org.jfrog.gradle.plugin.artifactory.TestConsts.MIN_GRADLE_VERSION_CONFIG_CACHE;
import static org.jfrog.gradle.plugin.artifactory.utils.Utils.createDeployableArtifactsFile;
import static org.testng.Assert.assertEquals;

public class GradleFunctionalTestBase {
    // ArtifactoryManager
    protected ArtifactoryManager artifactoryManager;
    protected static final Log log = new TestingLog();
    private String username;
    private String adminToken;
    private String platformUrl;
    private String artifactoryUrl;

    // Test repositories
    public final String localRepo = getKeyWithTimestamp(TestConsts.GRADLE_LOCAL_REPO);
    public final String virtualRepo = getKeyWithTimestamp(TestConsts.GRADLE_VIRTUAL_REPO);
    protected String remoteRepo = getKeyWithTimestamp(TestConsts.GRADLE_REMOTE_REPO);
    protected String remoteGoogleRepo = getKeyWithTimestamp(TestConsts.GRADLE_REMOTE_GOOGLE_REPO);

    // Test specific attributes
    private StringSubstitutor stringSubstitutor;
    public static final long CURRENT_TIME = System.currentTimeMillis();
    protected Map<String, String> envVars;

    @DataProvider
    public Object[][] gradleVersions() {
        return new Object[][]{{"9.0.0-milestone-9"}};
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
        if (StringUtils.isNotEmpty(remoteGoogleRepo)) {
            deleteTestRepo(remoteGoogleRepo);
        }
        if (StringUtils.isNotEmpty(localRepo)) {
            deleteTestRepo(localRepo);
        }
        artifactoryManager.close();
    }

    public void runPublishTest(String gradleVersion, Path sourceDir, ValidationUtils.BuildResultValidation validation) throws IOException {
        // Create test environment
        Utils.createTestDir(sourceDir);
        // Run configuration cache
        runConfigCacheIfSupported(gradleVersion, envVars, false);
        // Run Gradle
        BuildResult buildResult = Utils.runGradleArtifactoryPublish(gradleVersion, envVars, false);
        validation.validate(buildResult);
        // Cleanup
        Pair<String, String> buildDetails = Utils.getBuildDetails(buildResult);
        Utils.cleanTestBuilds(artifactoryManager, buildDetails.getLeft(), buildDetails.getRight(), null);
    }

    public interface TestEnvCreator {
        void create(String deployableArtifacts) throws IOException;
    }

    public void runPublishCITest(String gradleVersion, Path sourceDir, boolean cleanUp, TestEnvCreator testEnvCreator, ValidationUtils.CiBuildResultValidation validation) throws IOException {
        // Create test environment
        Utils.createTestDir(sourceDir);
        // Create build info properties file
        Path deployableArtifacts = createDeployableArtifactsFile();
        testEnvCreator.create(deployableArtifacts.toString());
        Map<String, String> extendedEnv = new HashMap<String, String>(envVars) {{
            put(BuildInfoConfigProperties.PROP_PROPS_FILE, TestConsts.BUILD_INFO_PROPERTIES_TARGET.toString());
        }};
        // Run configuration cache
        runConfigCacheIfSupported(gradleVersion, extendedEnv, true);
        // Run Gradle
        BuildResult buildResult = Utils.runGradleArtifactoryPublish(gradleVersion, extendedEnv, true);
        validation.validate(buildResult, deployableArtifacts);
        // Cleanup
        if (cleanUp) {
            Pair<String, String> buildDetails = Utils.getBuildDetails(buildResult);
            Utils.cleanTestBuilds(artifactoryManager, buildDetails.getLeft(), buildDetails.getRight(), null);
        }
        Files.deleteIfExists(deployableArtifacts);
    }

    /**
     * Execute the command "gradle --configuration-cache" in order to ensure the proper functioning of the configuration
     * cache for the project.
     * When dealing with Gradle versions that are earlier than 7.4.2, we have encountered issues related to reading system properties.
     * As a result, we have made the decision to exclude versions that are prior to 7.4.2.
     *
     * @param gradleVersion   - The Gradle version
     * @param envVars         - The extended environment variables
     * @param applyInitScript - Apply the template init script to add the plugin
     * @throws IOException In case of any IO error.
     */
    private void runConfigCacheIfSupported(String gradleVersion, Map<String, String> envVars, boolean applyInitScript) throws IOException {
        if (!new Version(gradleVersion).isAtLeast(MIN_GRADLE_VERSION_CONFIG_CACHE)) {
            return;
        }
        BuildResult buildResult = Utils.runConfigurationCache(gradleVersion, envVars, applyInitScript);
        for (BuildTask buildTask : buildResult.getTasks()) {
            assertEquals(buildTask.getOutcome(), TaskOutcome.SUCCESS);
        }
    }

    private void initArtifactoryManager() {
        // URL
        platformUrl = Utils.readParam(TestConsts.URL, TestConsts.DEFAULT_URL);
        if (!platformUrl.endsWith("/")) {
            platformUrl += "/";
        }
        artifactoryUrl = platformUrl + Constant.ARTIFACTORY + "/";
        // Credentials
        username = Utils.readParam(TestConsts.USERNAME, TestConsts.DEFAULT_USERNAME);
        adminToken = Utils.readParam(TestConsts.ADMIN_TOKEN, TestConsts.DEFAULT_PASS);
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
        if (StringUtils.isNotEmpty(remoteGoogleRepo)) {
            createTestRepo(remoteGoogleRepo);
        }
        if (StringUtils.isNotEmpty(virtualRepo)) {
            createTestRepo(virtualRepo);
        }
    }

    private void createStringSubstitutor() {
        Map<String, Object> textParameters = new HashMap<>();
        textParameters.put(TestConsts.LOCAL_REPO, localRepo);
        textParameters.put(TestConsts.REMOTE_REPO, remoteRepo);
        textParameters.put(TestConsts.REMOTE_GOOGLE_REPO, remoteGoogleRepo);
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
        // Create env vars to pass for running gradle commands (var replacement in build.gradle files?)
        envVars = new HashMap<String, String>(System.getenv()) {{
            putIfAbsent(TestConsts.BITESTS_ENV_VAR_PREFIX + TestConsts.URL, getPlatformUrl());
            putIfAbsent(TestConsts.BITESTS_ENV_VAR_PREFIX + TestConsts.USERNAME, getUsername());
            putIfAbsent(TestConsts.BITESTS_ENV_VAR_PREFIX + TestConsts.ADMIN_TOKEN, getAdminToken());
            putIfAbsent(TestConsts.BITESTS_ARTIFACTORY_ENV_VAR_PREFIX + TestConsts.LOCAL_REPO, localRepo);
            putIfAbsent(TestConsts.BITESTS_ARTIFACTORY_ENV_VAR_PREFIX + TestConsts.VIRTUAL_REPO, virtualRepo);
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
        FileUtils.deleteDirectory(TestConsts.TEST_DIR);
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
