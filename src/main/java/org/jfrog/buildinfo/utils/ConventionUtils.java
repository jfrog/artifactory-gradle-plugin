package org.jfrog.buildinfo.utils;

import org.gradle.api.Project;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.buildinfo.config.ArtifactoryPluginConvention;

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
}
