package org.jfrog.gradle.plugin.artifactory.utils;

import org.apache.commons.lang3.StringUtils;
import org.gradle.api.GradleException;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.invocation.Gradle;
import org.gradle.authentication.http.BasicAuthentication;
import org.jfrog.build.api.builder.ModuleType;
import org.jfrog.build.api.util.Log;
import org.jfrog.build.client.Version;
import org.jfrog.build.extractor.BuildInfoExtractorUtils;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.gradle.plugin.artifactory.Constant;

import java.util.Arrays;
import java.util.Properties;

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

    /**
     * Adds an Artifactory resolution repository to a given MavenArtifactRepository.
     * This method configures the repository's name and URL based on the provided context URL and resolver information.
     * If credentials (username and password) are provided in the resolver, they are set for the repository.
     *
     * @param mavenArtifactRepository The MavenArtifactRepository to configure.
     * @param contextUrl              The base URL for the Artifactory instance.
     * @param resolver                The resolver handler containing the repository key and optional credentials.
     */
    public static void addArtifactoryResolutionRepositoryAction(MavenArtifactRepository mavenArtifactRepository, String contextUrl, ArtifactoryClientConfiguration.ResolverHandler resolver) {
        mavenArtifactRepository.setName("artifactoryResolutionRepository");
        mavenArtifactRepository.setUrl(contextUrl + resolver.getRepoKey());

        // Set credentials if provided
        String username = resolver.getUsername();
        String password = resolver.getPassword();
        if (StringUtils.isNoneBlank(username, password)) {
            mavenArtifactRepository.credentials((credentials) -> {
                credentials.setUsername(username);
                credentials.setPassword(password);
            });

            // Before resolving an artifact from Artifactory, Gradle typically sends a preemptive challenge request and
            // expects a 401 response from the server.
            // However, when 'Hide Existence of Unauthorized Resources' is enabled, Artifactory returns a 404 instead.
            // Adding BasicAuthentication ensures that credentials are sent directly, bypassing this challenge.
            mavenArtifactRepository.authentication(authentications -> authentications.create("basic", BasicAuthentication.class));
        }
    }

    /**
     * Extract the resolver information from the build-info.properties file generated by the JFrog CLI or by the
     * Jenkins Artifactory plugin.
     *
     * @param log - The logger.
     * @return resolver handler.
     */
    public static ArtifactoryClientConfiguration.ResolverHandler getResolverHandler(Log log) {
        Properties allProps = BuildInfoExtractorUtils.mergePropertiesWithSystemAndPropertyFile(new Properties(), log);
        ArtifactoryClientConfiguration configuration = new ArtifactoryClientConfiguration(log);
        configuration.fillFromProperties(allProps);
        return configuration.resolver;
    }
}
