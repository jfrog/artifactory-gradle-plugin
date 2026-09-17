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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;

import static org.jfrog.gradle.plugin.artifactory.TestConsts.MIN_GRADLE_VERSION_CONFIG_CACHE;
import static org.jfrog.gradle.plugin.artifactory.utils.Utils.createDeployableArtifactsFile;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;

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
        return new Object[][]{{"9.0.0-milestone-9"}, {"8.8"}};
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
        // Normal publish first — ensures Artifactory repos are ready and artifacts are built.
        BuildResult buildResult = Utils.runGradleArtifactoryPublish(gradleVersion, envVars, false);
        validation.validate(buildResult);
        Pair<String, String> buildDetails = Utils.getBuildDetails(buildResult);
        Utils.cleanTestBuilds(artifactoryManager, buildDetails.getLeft(), buildDetails.getRight(), null);
        // Config-cache: run twice (store + reuse), validate reuse run build-info.
        BuildResult ccReuseResult = runConfigCacheIfSupported(gradleVersion, envVars, false);
        if (ccReuseResult != null) {
            validation.validate(ccReuseResult);
            Pair<String, String> ccBuild = Utils.getBuildDetails(ccReuseResult);
            Utils.cleanTestBuilds(artifactoryManager, ccBuild.getLeft(), ccBuild.getRight(), null);
        }
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
        // Normal publish first — ensures Artifactory is ready.
        BuildResult buildResult = Utils.runGradleArtifactoryPublish(gradleVersion, extendedEnv, true);
        validation.validate(buildResult, deployableArtifacts);
        if (cleanUp) {
            Pair<String, String> buildDetails = Utils.getBuildDetails(buildResult);
            Utils.cleanTestBuilds(artifactoryManager, buildDetails.getLeft(), buildDetails.getRight(), null);
        }
        // Config-cache: run twice (store + reuse), validate reuse run build-info.
        BuildResult ccReuseResult = runConfigCacheIfSupported(gradleVersion, extendedEnv, true);
        if (ccReuseResult != null) {
            validation.validate(ccReuseResult, deployableArtifacts);
            if (cleanUp) {
                // Only builds that publish build-info print the build-info URL getBuildDetails() parses.
                Pair<String, String> ccBuild = Utils.getBuildDetails(ccReuseResult);
                Utils.cleanTestBuilds(artifactoryManager, ccBuild.getLeft(), ccBuild.getRight(), null);
            }
        }
        Files.deleteIfExists(deployableArtifacts);
    }

    /**
     * Run 'gradle artifactoryPublish --configuration-cache' twice and assert the second run reuses
     * the cache with no problems and no missing dependencies in the produced build-info.
     * Skipped on Gradle versions below {@link TestConsts#MIN_GRADLE_VERSION_CONFIG_CACHE}.
     *
     * @param gradleVersion   - The Gradle version
     * @param envVars         - The extended environment variables
     * @param applyInitScript - Apply the template init script to add the plugin
     * @throws IOException In case of any IO error.
     */
    /**
     * Run 'build artifactoryPublish --configuration-cache' twice and assert the second run reuses
     * the cache with no problems and no missing dependencies in the produced build-info.
     * Skipped on Gradle versions below {@link TestConsts#MIN_GRADLE_VERSION_CONFIG_CACHE}.
     *
     * @return the reuse-run BuildResult for caller validation, or null if skipped.
     */
    private BuildResult runConfigCacheIfSupported(String gradleVersion, Map<String, String> envVars, boolean applyInitScript) throws IOException {
        if (!new Version(gradleVersion).isAtLeast(MIN_GRADLE_VERSION_CONFIG_CACHE)) {
            return null;
        }
        // Utils.runConfigurationCache runs twice: store on run 1, reuse on run 2.
        BuildResult reuseResult = Utils.runConfigurationCache(gradleVersion, envVars, applyInitScript);
        assertTrue(reuseResult.getOutput().contains("Reusing configuration cache"),
                "Second run must reuse the configuration cache. Output:\n" + reuseResult.getOutput());
        List<BuildTask> tasks = reuseResult.getTasks();
        assertFalse(tasks.isEmpty(), "Configuration-cache reuse run executed no tasks");
        for (BuildTask buildTask : tasks) {
            assertNotEquals(buildTask.getOutcome(), TaskOutcome.FAILED,
                    "Task " + buildTask.getPath() + " failed under configuration cache");
        }
        return reuseResult;
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
        // Validate the test directory path before deletion
        Path testDirPath = TestConsts.TEST_DIR.toPath().toAbsolutePath().normalize();
        
        // Ensure we're only deleting the expected test directory
        if (!testDirPath.getFileName().toString().equals("gradle_tests_space")) {
            throw new SecurityException("Attempting to delete unexpected directory: " + testDirPath);
        }
        
        FileUtils.deleteDirectory(testDirPath.toFile());
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
