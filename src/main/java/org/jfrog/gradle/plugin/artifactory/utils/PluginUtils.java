package org.jfrog.gradle.plugin.artifactory.utils;

import org.apache.commons.lang3.StringUtils;
import org.gradle.api.GradleException;
import org.gradle.api.invocation.Gradle;
import org.jfrog.build.api.builder.ModuleType;
import org.jfrog.build.client.Version;
import org.jfrog.gradle.plugin.artifactory.Constant;

import java.util.Arrays;

public class PluginUtils {

    /**
     * Throws an exception if the Gradle version is below 6.8.1.
     *
     * @param gradle - Represents an invocation of Gradle
     * @throws GradleException if the Gradle version is below 6.8.1.
     */
    public static void assertGradleVersionSupported(Gradle gradle) throws GradleException {
        String gradleVersion = gradle.getGradleVersion();
        if (!new Version(gradleVersion).isAtLeast(Constant.MIN_GRADLE_VERSION)) {
            throw new GradleException("Can't apply Artifactory Plugin on Gradle version " + gradleVersion + ". Minimum supported Gradle version is " + Constant.MIN_GRADLE_VERSION);
        }
    }

    /**
     * Get the {@link ModuleType} object from the user input or GRADLE.
     *
     * @param moduleType - Module type input from the user
     * @return the ModuleType to set.
     */
    public static ModuleType getModuleType(String moduleType) {
        if (StringUtils.isBlank(moduleType)) {
            return ModuleType.GRADLE;
        }
        try {
            return ModuleType.valueOf(StringUtils.upperCase(moduleType));
        } catch (IllegalArgumentException illegalArgumentException) {
            throw new GradleException("moduleType can only be one of " + Arrays.toString(ModuleType.values()), illegalArgumentException);
        }
    }
}
