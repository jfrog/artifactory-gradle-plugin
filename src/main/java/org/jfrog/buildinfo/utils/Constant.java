package org.jfrog.buildinfo.utils;

public class Constant {
    public static final String ARTIFACTORY = "artifactory";
    public static final String PUBLISH_TASK_GROUP = "publishing";

    // Minimum Gradle version to use the plugin
    public static final String MIN_GRADLE_VERSION = "6.9";

    // Plugin tasks names
    public static final String ARTIFACTORY_PUBLISH_TASK_NAME = "artifactoryPublish";
    public static final String EXTRACT_MODULE_TASK_NAME = "extractModuleInfo";
    public static final String EXTRACT_BUILD_INFO_TASK_NAME = "extractBuildInfo";
    public static final String DEPLOY_TASK_NAME = "artifactoryDeploy";

    // Plugin generated file names (intermediate)
    public static final String MODULE_INFO_FILE_NAME = "moduleInfo.json";
    public static final String BUILD_INFO_FILE_NAME = "build-info.json";
}
