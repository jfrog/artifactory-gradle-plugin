package org.jfrog.gradle.plugin.artifactory.utils;

import org.gradle.StartParameter;
import org.gradle.api.Project;
import org.jfrog.build.api.BuildInfoFields;
import org.jfrog.build.api.BuildInfoProperties;
import org.jfrog.build.api.util.CommonUtils;
import org.jfrog.build.extractor.BuildInfoExtractorUtils;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.gradle.plugin.artifactory.Constant;
import org.jfrog.gradle.plugin.artifactory.dsl.ArtifactoryPluginConvention;

import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration.addDefaultPublisherAttributes;

public class ExtensionsUtils {

    /**
     * Get or create if not exists an artifactory extension for a given project
     *
     * @param project - the project to fetch/create its extension
     * @return project extension
     */
    public static ArtifactoryPluginConvention getOrCreateArtifactoryExtension(Project project) {
        ArtifactoryPluginConvention con = project.getExtensions().findByType(ArtifactoryPluginConvention.class);
        if (con == null) {
            con = project.getExtensions().create(Constant.ARTIFACTORY, ArtifactoryPluginConvention.class, project);
        }
        return con;
    }

    /**
     * Get the Artifactory extension that is defined at the root project of a given project
     *
     * @param project - the project that will get its root's extension
     * @return Artifactory's extension defined at the root project if exists
     */
    public static ArtifactoryPluginConvention getArtifactoryExtension(Project project) {
        return project.getRootProject().getExtensions().findByType(ArtifactoryPluginConvention.class);
    }

    /**
     * Get an extension of a given project that configured a publisher with: contextUrl and repoKey/snapshotRepoKey
     * If the current project didn't configure a publisher tries the parent until one is found
     *
     * @param project - the project to fetch its publisher configurations
     * @return an Artifactory extension with publisher configured or null if not found
     */
    public static ArtifactoryPluginConvention getExtensionWithPublisher(Project project) {
        while (project != null) {
            ArtifactoryPluginConvention acc = project.getExtensions().findByType(ArtifactoryPluginConvention.class);
            if (acc != null) {
                ArtifactoryClientConfiguration.PublisherHandler publisher = acc.getClientConfig().publisher;
                if (publisher.getContextUrl() != null && (publisher.getRepoKey() != null || publisher.getSnapshotRepoKey() != null)) {
                    return acc;
                }
            }
            project = project.getParent();
        }
        return null;
    }

    /**
     * Get the configured (contextUrl and repoKey/snapshotRepoKey) publisher handler of a given project
     *
     * @param project - the project to fetch its publisher configurations
     * @return a configured publisher handler or null if not found
     */
    public static ArtifactoryClientConfiguration.PublisherHandler getPublisherHandler(Project project) {
        ArtifactoryPluginConvention extension = getExtensionWithPublisher(project);
        if (extension == null) {
            return null;
        }
        return extension.getClientConfig().publisher;
    }

    /**
     * Update and fill a given client configuration with information from:
     * 1) Start parameters
     * 2) System properties
     * 3) special buildInfo properties
     * 4) default publisher attributes
     *
     * @param configuration - configuration to update
     * @param project       - project to get parent information and start parameters
     */
    public static void updateConfig(ArtifactoryClientConfiguration configuration, Project project) {
        Properties props = new Properties();
        // Aggregate properties from parent to child
        fillProperties(project, props);
        // Add start parameters
        StartParameter startParameter = project.getGradle().getStartParameter();
        Map<String, String> startProps = startParameter.getProjectProperties();
        props.putAll(BuildInfoExtractorUtils.filterStringEntries(startProps));
        // Add System properties
        Properties mergedProps = BuildInfoExtractorUtils.mergePropertiesWithSystemAndPropertyFile(props, configuration.info.getLog());
        // Add special buildInfo properties
        Properties buildInfoProps = BuildInfoExtractorUtils.filterDynamicProperties(mergedProps, BuildInfoExtractorUtils.BUILD_INFO_PROP_PREDICATE);
        buildInfoProps = BuildInfoExtractorUtils.stripPrefixFromProperties(buildInfoProps, BuildInfoProperties.BUILD_INFO_PROP_PREFIX);
        mergedProps.putAll(buildInfoProps);

        // Add the properties to the Artifactory client configuration.
        // In case the build name and build number have already been added to the configuration
        // from inside the gradle script, we do not want to override them by the values sent from
        // the CI server plugin.
        String prefix = BuildInfoProperties.BUILD_INFO_PREFIX;
        Set<String> excludeIfExist = CommonUtils.newHashSet(prefix + BuildInfoFields.BUILD_NUMBER, prefix + BuildInfoFields.BUILD_NAME, prefix + BuildInfoFields.BUILD_STARTED);
        configuration.fillFromProperties(mergedProps, excludeIfExist);

        // After props are set, apply missing (default) project props (only if not set by CI-plugin generated props)
        addDefaultPublisherAttributes(configuration, project.getRootProject().getName(), Constant.GRADLE, project.getGradle().getGradleVersion());
    }

    private static void fillProperties(Project project, Properties props) {
        Project parent = project.getParent();
        if (parent != null) {
            fillProperties(parent, props);
        }
        Map<String, ?> projectProperties = project.getExtensions().getExtraProperties().getProperties();
        props.putAll(BuildInfoExtractorUtils.filterStringEntries(projectProperties));
    }
}
