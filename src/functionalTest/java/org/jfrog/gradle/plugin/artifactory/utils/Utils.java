package org.jfrog.gradle.plugin.artifactory.utils;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.text.StringSubstitutor;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.jfrog.build.extractor.clientConfiguration.ClientConfigurationFields;
import org.jfrog.build.extractor.clientConfiguration.client.artifactory.ArtifactoryManager;
import org.jfrog.build.extractor.clientConfiguration.client.response.GetAllBuildNumbersResponse;
import org.jfrog.gradle.plugin.artifactory.Constant;
import org.jfrog.gradle.plugin.artifactory.GradleFunctionalTestBase;
import org.jfrog.gradle.plugin.artifactory.TestConsts;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;

import static org.jfrog.build.api.BuildInfoFields.DEPLOYABLE_ARTIFACTS;
import static org.jfrog.build.extractor.BuildInfoExtractorUtils.BUILD_BROWSE_URL;
import static org.jfrog.build.extractor.ci.BuildInfoProperties.BUILD_INFO_PREFIX;
import static org.testng.Assert.assertTrue;

public class Utils {

    /**
     * Reads a value from environment variable base on a given suffix paramName: 'BITESTS_PLATFORM_' + paramName
     * If the var not exists return the given default value
     *
     * @param paramName    - suffix for environment variable name
     * @param defaultValue - value if env var not exists
     */
    public static String readParam(String paramName, String defaultValue) {
        String paramValue = System.getenv(TestConsts.BITESTS_ENV_VAR_PREFIX + paramName.toUpperCase());
        return StringUtils.defaultIfBlank(paramValue, defaultValue);
    }

    /**
     * Copy the test project from sourceDir to TEST_DIR.
     *
     * @param sourceDir - The Gradle project
     * @throws IOException - In case of any IO error
     */
    public static void createTestDir(Path sourceDir) throws IOException {
        // Validate source directory path to prevent path traversal
        Path normalizedSourcePath = sourceDir.toAbsolutePath().normalize();
        Path projectsRoot = TestConsts.PROJECTS_ROOT.toAbsolutePath().normalize();
        
        // Validate destination directory path to prevent path traversal
        // By checking that the folder still starts with the predefined prefix after normalization,
        // we make sure that the attacker is not able to back-path outside of the allowed folder
        Path normalizedDestPath = TestConsts.TEST_DIR.toPath().toAbsolutePath().normalize();
        Path tempDirRoot = Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        
        if (normalizedSourcePath.startsWith(projectsRoot) && normalizedDestPath.startsWith(tempDirRoot)) {
            File sourceDirFile = normalizedSourcePath.toFile();
            File destDirFile = normalizedDestPath.toFile();
            FileUtils.copyDirectory(sourceDirFile, destDirFile);
        }
    }

    /**
     * Create the deployable artifacts file.
     *
     * @return the path to the generated deployable artifacts file.
     * @throws IOException - In case of any IO error
     */
    public static Path createDeployableArtifactsFile() throws IOException {
        // Ensure test directory exists and normalize paths to prevent traversal
        Path testDirPath = TestConsts.TEST_DIR.toPath().toAbsolutePath().normalize();
        Files.createDirectories(testDirPath);
        
        Path artifactsPath = testDirPath.resolve("deployable.artifacts").normalize();
        
        // Ensure the resolved path is still within TEST_DIR
        if (!artifactsPath.startsWith(testDirPath)) {
            throw new SecurityException("Artifacts file must be within test directory");
        }
        
        return Files.createFile(artifactsPath);
    }

    /**
     * Run 'ArtifactoryPublish' task with specific context.
     *
     * @param gradleVersion   - Run the tasks with this given gradle version
     * @param envVars         - Environment variable that will be used in the task
     * @param applyInitScript - Apply the template init script to add the plugin
     * @return result of the task
     */
    public static BuildResult runGradleArtifactoryPublish(String gradleVersion, Map<String, String> envVars, boolean applyInitScript) throws IOException {
        List<String> arguments = new ArrayList<>(Arrays.asList("clean", "build", Constant.ARTIFACTORY_PUBLISH_TASK_NAME, "--stacktrace"));
        return runPluginTasks(gradleVersion, arguments, envVars, applyInitScript);
    }

    /**
     * Run 'gradle --configuration-cache' with specific context.
     *
     * @param gradleVersion   - Run the tasks with this given gradle version
     * @param envVars         - Environment variable that will be used in the task
     * @param applyInitScript - Apply the template init script to add the plugin
     * @return result of the task
     * @throws IOException in case of any IO error
     */
    public static BuildResult runConfigurationCache(String gradleVersion, Map<String, String> envVars, boolean applyInitScript) throws IOException {
        List<String> arguments = new ArrayList<>(Collections.singletonList("--configuration-cache"));
        return runPluginTasks(gradleVersion, arguments, envVars, applyInitScript);
    }

    /**
     * Run Gradle task with specific context.
     *
     * @param gradleVersion   - Run the tasks with this given gradle version
     * @param arguments       - Arguments to run
     * @param envVars         - Environment variable that will be used in the task
     * @param applyInitScript - Apply the template init script to add the plugin
     * @return result of the task
     */
    public static BuildResult runPluginTasks(String gradleVersion, List<String> arguments, Map<String, String> envVars, boolean applyInitScript) throws IOException {
        if (applyInitScript) {
            generateInitScript();
            arguments.add("--init-script=gradle.init");
        }
        //noinspection UnstableApiUsage
        return GradleRunner.create()
                .withGradleVersion(gradleVersion)
                .withProjectDir(TestConsts.TEST_DIR)
                .withPluginClasspath()
                .withArguments(arguments)
                .withEnvironment(envVars)
                .build();
    }

    /**
     * Generate Gradle init script.
     *
     * @throws IOException - In case of any IO error
     */
    private static void generateInitScript() throws IOException {
        String content = FileUtils.readFileToString(TestConsts.INIT_SCRIPT.toFile(), StandardCharsets.UTF_8);
        // Insert the path to lib (Escape "/" in Windows machines)
        String libsDir = TestConsts.LIBS_DIR.toString().replaceAll("\\\\", "\\\\\\\\");
        content = content.replace("${pluginLibDir}", libsDir);
        // Write gradle.init file with the content
        Path testDirPath = TestConsts.TEST_DIR.toPath().toAbsolutePath().normalize();
        Path target = testDirPath.resolve("gradle.init").normalize();
        
        // Validate that target is within TEST_DIR
        if (!target.startsWith(testDirPath)) {
            throw new SecurityException("Init script must be within test directory");
        }
        
        Files.write(target, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Generate buildinfo.properties file with publisher and other properties base on the given inputs.
     *
     * @param testBase            - the test that hold the Artifactory properties that the user/CI server needs to provide
     * @param publications        - the publications to add into the properties
     * @param publishBuildInfo    - property that decide if to publish the build info
     * @param setDeployer         - if true it will set the deployer properties for the build info
     * @param deployableArtifacts - path to deployable artifacts file, or empty if not necessary
     * @throws IOException - In case of any IO error
     */
    public static void generateBuildInfoProperties(GradleFunctionalTestBase testBase, String publications, boolean publishBuildInfo, boolean setDeployer, String deployableArtifacts) throws IOException {
        String content = generateBuildInfoPropertiesForServer(testBase, publications, publishBuildInfo, TestConsts.BUILD_INFO_PROPERTIES_SOURCE_RESOLVER);
        if (setDeployer) {
            content += "\n";
            content += generateBuildInfoPropertiesForServer(testBase, publications, publishBuildInfo, TestConsts.BUILD_INFO_PROPERTIES_SOURCE_DEPLOYER);
        }
        if (StringUtils.isNotBlank(deployableArtifacts)) {
            content += String.format("\n%s%s=%s", BUILD_INFO_PREFIX, DEPLOYABLE_ARTIFACTS, deployableArtifacts.replaceAll("\\\\", "\\\\\\\\"));
        }
        // Validate the target path before writing
        Path targetPath = TestConsts.BUILD_INFO_PROPERTIES_TARGET.toAbsolutePath().normalize();
        Path testDirPath = TestConsts.TEST_DIR.toPath().toAbsolutePath().normalize();
        
        // Ensure target is within TEST_DIR
        if (!targetPath.startsWith(testDirPath)) {
            throw new SecurityException("Build info properties file must be within test directory");
        }
        
        Files.write(targetPath, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Generate buildinfo.properties section from source template.
     *
     * @param testBase         - the test function class to provide credentials and platform information to run test with
     * @param publications     - comma seperated list of publications to include
     * @param publishBuildInfo - Publish build info
     * @param source           - Path to server specific buildinfo.properties template.
     * @return Generated buildinfo.properties according to the given input
     * @throws IOException - In case of any IO error
     */
    private static String generateBuildInfoPropertiesForServer(GradleFunctionalTestBase testBase, String publications, boolean publishBuildInfo, Path source) throws IOException {
        String content = FileUtils.readFileToString(source.toFile(), StandardCharsets.UTF_8);
        Map<String, String> valuesMap = new HashMap<String, String>() {{
            put(ClientConfigurationFields.PUBLICATIONS, publications);
            put(ClientConfigurationFields.CONTEXT_URL, testBase.getArtifactoryUrl());
            put(ClientConfigurationFields.USERNAME, testBase.getUsername());
            put(ClientConfigurationFields.PASSWORD, testBase.getAdminToken());
            put(ClientConfigurationFields.PUBLISH_BUILD_INFO, String.valueOf(publishBuildInfo));
            put("localRepo", testBase.localRepo);
            put("virtualRepo", testBase.virtualRepo);
        }};
        StringSubstitutor sub = new StringSubstitutor(valuesMap);
        return sub.replace(content);
    }

    /**
     * @param buildResult - Details of the build
     * @return A pair of: Left item - buildResult's build name, Right item - buildResult's build number
     */
    public static Pair<String, String> getBuildDetails(BuildResult buildResult) {
        // Get build info URL
        String[] res = StringUtils.substringAfter(buildResult.getOutput(), BUILD_BROWSE_URL).split("/");
        assertTrue(ArrayUtils.getLength(res) >= 3, "Couldn't find build info URL link");

        // Extract build name and number from build info URL
        String buildName = res[1];
        String buildNumber = StringUtils.substringBefore(res[2], System.lineSeparator());
        return Pair.of(buildName, buildNumber);
    }

    public static void cleanTestBuilds(ArtifactoryManager artifactoryManager, String buildName, String buildNumber, String project) throws IOException {
        artifactoryManager.deleteBuilds(buildName, project, true, buildNumber);
        cleanOldBuilds(artifactoryManager, buildName, project);
    }

    /**
     * Clean up old build runs which have been created more than 24 hours.
     *
     * @param buildName - The build name to be cleaned.
     */
    private static void cleanOldBuilds(ArtifactoryManager artifactoryManager, String buildName, String project) throws IOException {
        // Get build numbers for deletion
        String[] oldBuildNumbers = artifactoryManager.getAllBuildNumbers(buildName, project).buildsNumbers.stream()

                // Get build numbers.
                .map(GetAllBuildNumbersResponse.BuildsNumberDetails::getUri)

                //  Remove duplicates.
                .distinct()

                // Match build number pattern.
                .map(TestConsts.BUILD_NUMBER_PATTERN::matcher)
                .filter(Matcher::matches)

                // Filter build numbers newer than 24 hours.
                .filter(GradleFunctionalTestBase::isOldBuild)

                // Get build number.
                .map(matcher -> matcher.group(1))
                .toArray(String[]::new);

        if (oldBuildNumbers.length > 0) {
            artifactoryManager.deleteBuilds(buildName, project, true, oldBuildNumbers);
        }
    }
}
