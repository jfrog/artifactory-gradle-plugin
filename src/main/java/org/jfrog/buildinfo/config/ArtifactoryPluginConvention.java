package org.jfrog.buildinfo.config;

import groovy.lang.Closure;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.util.ConfigureUtil;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.buildinfo.utils.GradleClientLogger;

public class ArtifactoryPluginConvention {

    private final Project project;

    private final ArtifactoryClientConfiguration clientConfig;

    private PublisherConfig publisherConfig;

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

    public void publish(Closure closure) {
        publish(ConfigureUtil.configureUsing(closure));
    }

    public void publish(Action<? extends PublisherConfig> publishAction) {
        publisherConfig = new PublisherConfig(this);
    }
}
