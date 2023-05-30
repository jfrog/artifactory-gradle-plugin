package org.jfrog.buildinfo.utils;

import org.gradle.StartParameter;
import org.gradle.api.Project;
import org.jfrog.build.api.BuildInfoFields;
import org.jfrog.build.api.BuildInfoProperties;
import org.jfrog.build.api.util.CommonUtils;
import org.jfrog.build.extractor.BuildInfoExtractorUtils;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.buildinfo.Constant;
import org.jfrog.buildinfo.config.ArtifactoryPluginConvention;

import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration.addDefaultPublisherAttributes;

public class ConventionUtils {
    public static ArtifactoryPluginConvention getOrCreateArtifactoryConvention(Project project) {
        ArtifactoryPluginConvention con = project.getConvention().findPlugin(ArtifactoryPluginConvention.class);
        if (con == null) {
            con = project.getExtensions().create(Constant.ARTIFACTORY, ArtifactoryPluginConvention.class, project);
        }
        return con;
    }

    public static ArtifactoryPluginConvention getArtifactoryConvention(Project project) {
        while (project != null) {
            ArtifactoryPluginConvention con = project.getConvention().findPlugin(ArtifactoryPluginConvention.class);
            if (con != null) {
                return con;
            }
            project = project.getParent();
        }
        return null;
    }

    public static ArtifactoryPluginConvention getPublisherConvention(Project project) {
        while (project != null) {
            ArtifactoryPluginConvention acc = project.getConvention().findPlugin(ArtifactoryPluginConvention.class);
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
        Map<String , ?> projectProperties = project.getExtensions().getExtraProperties().getProperties();
        props.putAll(BuildInfoExtractorUtils.filterStringEntries(projectProperties));
    }
}
