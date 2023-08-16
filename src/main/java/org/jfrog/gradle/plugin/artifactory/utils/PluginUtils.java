package org.jfrog.gradle.plugin.artifactory.utils;

import org.gradle.api.GradleException;
import org.gradle.api.invocation.Gradle;
import org.jfrog.build.client.Version;
import org.jfrog.gradle.plugin.artifactory.Constant;

public class PluginUtils {

    /**
     * Throws an exception if the Gradle version is below 6.8.1.
     *
     * @param gradle - Represents an invocation of Gradle
     * @throws GradleException if the Gradle version is below 6.8.1.
     */
    public static void assertGradleVersionSupported(Gradle gradle) throws GradleException {
        String gradleVersion = gradle.getGradleVersion();
        if (new Version(gradleVersion).isAtLeast(Constant.MIN_GRADLE_VERSION)) {
            return;
        }
        throw new GradleException("Can't apply Artifactory Plugin on Gradle version " + gradleVersion + ". Minimum supported Gradle version is " + Constant.MIN_GRADLE_VERSION);
    }
}
