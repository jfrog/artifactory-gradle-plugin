package org.jfrog.gradle.plugin.artifactory.dsl;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.gradle.plugin.artifactory.utils.GradleClientLogger;

/**
 * Main configuration object for the plugin. a DSL object that controls all the plugin configurations.
 * This object is defined at the user build script ander 'artifactory' closure.
 */
public class ArtifactoryPluginExtension {

    private final Project project;

    private final ArtifactoryClientConfiguration clientConfig;

    private PublisherConfig publisherConfig;

    public ArtifactoryPluginExtension(Project project) {
        this.project = project;
        clientConfig = new ArtifactoryClientConfiguration(new GradleClientLogger(project.getLogger()));
    }

    @SuppressWarnings("unused")
    public void setContextUrl(String contextUrl) {
        clientConfig.publisher.setContextUrl(contextUrl);
    }

    public void publish(Action<PublisherConfig> publishAction) {
        publisherConfig = project.getObjects().newInstance(PublisherConfig.class, project.getObjects(), this);
        publishAction.execute(publisherConfig);
    }

    @SuppressWarnings("unused")
    public void buildInfo(Action<ArtifactoryClientConfiguration.BuildInfoHandler> buildInfoAction) {
        buildInfoAction.execute(clientConfig.info);
    }

    @SuppressWarnings("unused")
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
