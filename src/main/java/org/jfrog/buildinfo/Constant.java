package org.jfrog.buildinfo;

public class Constant {
    public static final String GRADLE = "Gradle";
    public static final String ARTIFACTORY = "artifactory";
    public static final String PUBLISH_TASK_GROUP = "publishing";

    // Minimum Gradle version to use the plugin
    public static final String MIN_GRADLE_VERSION = "6.9";

    // Plugin tasks
    public static final String ARTIFACTORY_PUBLISH_TASK_NAME = "artifactoryPublish";
    public static final String ARTIFACTORY_PUBLISH_TASK_DESCRIPTION = "Collect artifacts to be later used to generate build-info and deploy to Artifactory.";

    public static final String EXTRACT_MODULE_TASK_NAME = "extractModuleInfo";
    public static final String EXTRACT_MODULE_TASK_DESCRIPTION = "Extracts module information to an intermediate file.";

    public static final String EXTRACT_BUILD_INFO_TASK_NAME = "extractBuildInfo";
    public static final String EXTRACT_BUILD_INFO_TASK_DESCRIPTION = "Extracts build-info to a file.";

    public static final String DEPLOY_TASK_NAME = "artifactoryDeploy";
    public static final String DEPLOY_TASK_DESCRIPTION = "Deploys artifacts and build-info to Artifactory.";

    // Publications types
    public static final String ALL_PUBLICATIONS = "ALL_PUBLICATIONS";
    public static final String MAVEN_JAVA_PLATFORM = "mavenJavaPlatform";
    public static final String MAVEN_JAVA = "mavenJava";
    public static final String MAVEN_WEB = "mavenWeb";
    public static final String IVY_JAVA = "ivyJava";

    // COLLECT_PUBLISH_INFO_TASK_NAME optional flag names (can be defined as String/boolean)
    public static final String PUBLISH_ARTIFACTS = "publishArtifacts";
    public static final String PUBLISH_IVY = "publishIvy";
    public static final String PUBLISH_POM = "publishPom";

    // Plugin generated file names
    public static final String MODULE_INFO_FILE_NAME = "moduleInfo.json";
    public static final String BUILD_INFO_FILE_NAME = "build-info.json";
}
