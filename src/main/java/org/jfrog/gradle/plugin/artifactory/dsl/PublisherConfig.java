package org.jfrog.gradle.plugin.artifactory.dsl;

import org.gradle.api.Action;
import org.gradle.api.model.ObjectFactory;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.gradle.plugin.artifactory.task.ArtifactoryTask;

import javax.inject.Inject;

/**
 * Main publish configuration object for the plugin. a DSL object that controls all the plugin publishing configurations.
 * This object is defined and used at the user build script ander 'publish' in the 'artifactory' closure.
 */
public class PublisherConfig {

    private final ArtifactoryClientConfiguration.PublisherHandler publisher;
    private final Repository repository;

    // Configure global task that will be applied to all the projects
    Action<ArtifactoryTask> defaultsAction;

    @Inject
    public PublisherConfig(ObjectFactory objectFactory, ArtifactoryPluginConvention extension) {
        this.publisher = extension.getClientConfig().publisher;
        repository = objectFactory.newInstance(Repository.class, publisher);
    }

    @SuppressWarnings("unused")
    public String getContextUrl() {
        return this.publisher.getContextUrl();
    }

    @SuppressWarnings("unused")
    public void setContextUrl(String contextUrl) {
        this.publisher.setContextUrl(contextUrl);
    }

    public void defaults(Action<ArtifactoryTask> defaultsAction) {
        this.defaultsAction = defaultsAction;
    }

    public Action<ArtifactoryTask> getDefaultsAction() {
        return defaultsAction;
    }

    @SuppressWarnings("unused")
    public boolean isPublishBuildInfo() {
        return this.publisher.isPublishBuildInfo();
    }

    @SuppressWarnings("unused")
    public void setPublishBuildInfo(boolean publishBuildInfo) {
        this.publisher.setPublishBuildInfo(publishBuildInfo);
    }

    @SuppressWarnings("unused")
    public int getForkCount() {
        return this.publisher.getPublishForkCount();
    }

    @SuppressWarnings("unused")
    public void setForkCount(int forkCount) {
        this.publisher.setPublishForkCount(forkCount);
    }

    @SuppressWarnings("unused")
    public void repository(Action<Repository> repositoryAction) {
        repositoryAction.execute(repository);
    }

    @SuppressWarnings("unused")
    public Repository getRepository() {
        return repository;
    }

    public static class Repository {
        private final ArtifactoryClientConfiguration.PublisherHandler publisher;
        private final IvyPublishInfo ivyPublishInfo;

        @Inject
        public Repository(ArtifactoryClientConfiguration.PublisherHandler publisher) {
            this.publisher = publisher;
            this.ivyPublishInfo = new IvyPublishInfo(publisher);
        }

        @SuppressWarnings("unused")
        public String getRepoKey() {
            return publisher.getRepoKey();
        }

        @SuppressWarnings("unused")
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

        @SuppressWarnings("unused")
        public void ivy(Action<IvyPublishInfo> ivyAction) {
            ivyAction.execute(ivyPublishInfo);
        }

        @SuppressWarnings("unused")
        public IvyPublishInfo getIvy() {
            return ivyPublishInfo;
        }
    }

    public static class IvyPublishInfo {
        private final ArtifactoryClientConfiguration.PublisherHandler publisher;

        public IvyPublishInfo(ArtifactoryClientConfiguration.PublisherHandler publisher) {
            this.publisher = publisher;
        }

        @SuppressWarnings("unused")
        public void setIvyLayout(String ivyLayout) {
            publisher.setIvy(true);
            publisher.setIvyPattern(ivyLayout);
        }

        @SuppressWarnings("unused")
        public String getIvyLayout() {
            return publisher.getIvyPattern();
        }

        @SuppressWarnings("unused")
        public void setArtifactLayout(String artifactLayout) {
            publisher.setIvyArtifactPattern(artifactLayout);
        }

        @SuppressWarnings("unused")
        public String getArtifactLayout() {
            return publisher.getIvyArtifactPattern();
        }

        @SuppressWarnings("unused")
        public boolean getMavenCompatible() {
            return publisher.isM2Compatible();
        }

        @SuppressWarnings("unused")
        public void setMavenCompatible(boolean mavenCompatible) {
            this.publisher.setM2Compatible(mavenCompatible);
        }
    }

}
