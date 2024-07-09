package org.jfrog.gradle.plugin.artifactory;

import org.apache.commons.lang3.StringUtils;
import org.gradle.api.Plugin;
import org.gradle.api.initialization.Settings;
import org.gradle.api.logging.Logging;
import org.jfrog.build.api.util.Log;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.gradle.plugin.artifactory.utils.GradleClientLogger;
import org.jfrog.gradle.plugin.artifactory.utils.PluginUtils;

@SuppressWarnings("unused")
public class ArtifactoryPluginSettings implements Plugin<Settings> {
    private static final Log log = new GradleClientLogger(Logging.getLogger(ArtifactoryPluginSettings.class));

    @Override
    public void apply(Settings settings) {
        ArtifactoryClientConfiguration.ResolverHandler resolver = PluginUtils.getResolverHandler(log);
        if (resolver == null || StringUtils.isAnyBlank(resolver.getContextUrl(), resolver.getRepoKey())) {
            // If there's no configured Artifactory URL or repository, there's no need to include the resolution repository
            return;
        }
        String contextUrl = StringUtils.appendIfMissing(resolver.getContextUrl(), "/");
        // Add the Artifactory resolution repository.
        settings.getDependencyResolutionManagement().getRepositories().maven(mavenArtifactRepository -> PluginUtils.addArtifactoryResolutionRepositoryAction(mavenArtifactRepository, contextUrl, resolver));
    }
}
