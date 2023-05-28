package org.jfrog.buildinfo.config;

import org.gradle.api.Project;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.buildinfo.utils.GradleClientLogger;

public class ArtifactoryPluginConvention {

    private final Project project;

    private final ArtifactoryClientConfiguration clientConfig;

    public ArtifactoryPluginConvention(Project project) {
        this.project = project;
        clientConfig = new ArtifactoryClientConfiguration(new GradleClientLogger(project.getLogger()));
    }


    public Project getProject() {
        return project;
    }

    public ArtifactoryClientConfiguration getClientConfig() {
        return clientConfig;
    }
}
