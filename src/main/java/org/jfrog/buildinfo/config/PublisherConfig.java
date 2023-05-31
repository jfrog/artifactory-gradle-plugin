package org.jfrog.buildinfo.config;

import groovy.lang.Closure;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.util.ConfigureUtil;
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

    public void defaults(Closure<CollectDeployDetailsTask> closure) {
        defaults(ConfigureUtil.configureUsing(closure));
    }

    public void defaults(Action<CollectDeployDetailsTask> defaultsAction) {
        this.defaultsAction = defaultsAction;
    }

    public void repository(Closure<Repository> closure) { repository(ConfigureUtil.configureUsing(closure)); }

    public void repository(Action<Repository> repositoryAction) { repositoryAction.execute(repository); }

    public Action<CollectDeployDetailsTask> getDefaultsAction() {
        return defaultsAction;
    }

    public String getContextUrl() {
        return this.publisher.getContextUrl();
    }

    public void setContextUrl(String contextUrl) {
        this.publisher.setContextUrl(contextUrl);
    }

    public Repository getRepository() {
        return repository;
    }

    public static class Repository {
        private final ArtifactoryClientConfiguration.PublisherHandler publisher;
        public Repository(ArtifactoryClientConfiguration.PublisherHandler publisher) {
            this.publisher = publisher;
        }

        public String getRepoKey() {
            return publisher.getRepoKey();
        }

        public void setRepoKey(String repoKey) {
            this.publisher.setRepoKey(repoKey);
        }

        public String getUsername() {
            return publisher.getUsername();
        }

        public void setUsername(String username) {
            this.publisher.setUsername(username);
        }

        public String getPassword() {
            return publisher.getPassword();
        }

        public void setPassword(String password) {
            this.publisher.setPassword(password);
        }
    }
}
