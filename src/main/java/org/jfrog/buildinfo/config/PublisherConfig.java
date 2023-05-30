package org.jfrog.buildinfo.config;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.buildinfo.tasks.CollectDeployDetailsTask;

public class PublisherConfig {

    private final Project project;
    private final ArtifactoryClientConfiguration.PublisherHandler publisher;
    private final Repository repository;

    // Configure global CollectDeployDetailsTask that will be applied to all the projects
    Action<CollectDeployDetailsTask> defaultsAction;

    public PublisherConfig(ArtifactoryPluginConvention convention) {
        this.project = convention.getProject();
        this.publisher = convention.getClientConfig().publisher;
        repository = new Repository(this.publisher);
    }

    public class Repository {
        private final ArtifactoryClientConfiguration.PublisherHandler publisher;
        public Repository(ArtifactoryClientConfiguration.PublisherHandler publisher) {
            this.publisher = publisher;
        }
    }
}
