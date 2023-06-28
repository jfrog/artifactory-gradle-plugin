package org.jfrog.gradle.plugin.artifactory.config;

import groovy.lang.Closure;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.util.ConfigureUtil;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.gradle.plugin.artifactory.utils.GradleClientLogger;

/**
 * Main configuration object for the plugin. a DSL object that controls all the plugin configurations.
 * This object is defined at the user build script ander 'artifactory' closure.
 */
public class ArtifactoryPluginConvention {

    private final Project project;

    private final ArtifactoryClientConfiguration clientConfig;

    private PublisherConfig publisherConfig;

    public ArtifactoryPluginConvention(Project project) {
        this.project = project;
        clientConfig = new ArtifactoryClientConfiguration(new GradleClientLogger(project.getLogger()));
    }

    public void publish(Closure<PublisherConfig> closure) {
        publish(ConfigureUtil.configureUsing(closure));
    }

    public void publish(Action<PublisherConfig> publishAction) {
        publisherConfig = new PublisherConfig(this);
        publishAction.execute(publisherConfig);
    }

    @SuppressWarnings("unused")
    public void buildInfo(Closure<ArtifactoryClientConfiguration.BuildInfoHandler> closure) {
        buildInfo(ConfigureUtil.configureUsing(closure));
    }

    public void buildInfo(Action<ArtifactoryClientConfiguration.BuildInfoHandler> buildInfoAction) {
        buildInfoAction.execute(clientConfig.info);
    }

    @SuppressWarnings("unused")
    public void proxy(Closure<ArtifactoryClientConfiguration.ProxyHandler> closure) {
        proxy(ConfigureUtil.configureUsing(closure));
    }

    public void proxy(Action<ArtifactoryClientConfiguration.ProxyHandler> buildInfoAction) {
        buildInfoAction.execute(clientConfig.proxy);
    }

    public Project getProject() {
        return project;
    }

    public ArtifactoryClientConfiguration getClientConfig() {
        return clientConfig;
    }

    public PublisherConfig getPublisherConfig() {
        return publisherConfig;
    }
}
