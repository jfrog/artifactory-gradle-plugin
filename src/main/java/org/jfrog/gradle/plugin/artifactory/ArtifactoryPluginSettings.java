package org.jfrog.gradle.plugin.artifactory;

import org.apache.commons.lang3.StringUtils;
import org.gradle.api.Plugin;
import org.gradle.api.credentials.PasswordCredentials;
import org.gradle.api.initialization.Settings;

import static org.jfrog.gradle.plugin.artifactory.Constant.*;

@SuppressWarnings("unused")
public class ArtifactoryPluginSettings implements Plugin<Settings> {
    @Override
    public void apply(Settings settings) {
        String resolveContextUrl = System.getenv(RESOLUTION_URL_ENV);
        if (resolveContextUrl == null) {
            return;
        }
        settings.getDependencyResolutionManagement().getRepositories().maven(mavenArtifactRepository -> {
            mavenArtifactRepository.setName("artifactoryResolutionRepository");
            mavenArtifactRepository.setUrl(resolveContextUrl);
            if (StringUtils.isNoneBlank(System.getenv(RESOLUTION_USERNAME_ENV), System.getenv(RESOLUTION_PASSWORD_ENV))) {
                mavenArtifactRepository.credentials(PasswordCredentials.class);
            }
        });
    }
}
