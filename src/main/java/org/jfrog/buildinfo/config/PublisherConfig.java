package org.jfrog.buildinfo.config;

import groovy.lang.Closure;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.util.ConfigureUtil;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.buildinfo.tasks.ArtifactoryTask;

public class PublisherConfig {

    private final Project project;
    private final ArtifactoryClientConfiguration.PublisherHandler publisher;
    private final Repository repository;

    // Configure global CollectDeployDetailsTask that will be applied to all the projects
    Action<ArtifactoryTask> defaultsAction;

    public PublisherConfig(ArtifactoryPluginConvention convention) {
        this.project = convention.getProject();
        this.publisher = convention.getClientConfig().publisher;
        repository = new Repository(this.publisher);
    }

    public void defaults(Closure<ArtifactoryTask> closure) {
        defaults(ConfigureUtil.configureUsing(closure));
    }

    public void defaults(Action<ArtifactoryTask> defaultsAction) {
        this.defaultsAction = defaultsAction;
    }

    public void repository(Closure<Repository> closure) { repository(ConfigureUtil.configureUsing(closure)); }

    public void repository(Action<Repository> repositoryAction) { repositoryAction.execute(repository); }

    public Action<ArtifactoryTask> getDefaultsAction() {
        return defaultsAction;
    }

    public String getContextUrl() {
        return this.publisher.getContextUrl();
    }

    public void setContextUrl(String contextUrl) {
        this.publisher.setContextUrl(contextUrl);
    }

    public boolean isPublishBuildInfo() {
        return this.publisher.isPublishBuildInfo();
    }

    public void publishBuildInfo(boolean publishBuildInfo) {
        this.publisher.setPublishBuildInfo(publishBuildInfo);
    }

    public int getForkCount() {
        return this.publisher.getPublishForkCount();
    }

    public void setForkCount(int forkCount) {
        this.publisher.setPublishForkCount(forkCount);
    }

    public Repository getRepository() {
        return repository;
    }

    public static class Repository {
        private final ArtifactoryClientConfiguration.PublisherHandler publisher;
        private final IvyPublishInfo ivyPublishInfo;

        public Repository(ArtifactoryClientConfiguration.PublisherHandler publisher) {
            this.publisher = publisher;
            this.ivyPublishInfo = new IvyPublishInfo(publisher);
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

        public void ivy(Closure<IvyPublishInfo> closure) {
            ivy(ConfigureUtil.configureUsing(closure));
        }

        public void ivy(Action<IvyPublishInfo> ivyAction) {
            ivyAction.execute(ivyPublishInfo);
        }

        public IvyPublishInfo getIvy() {
            return ivyPublishInfo;
        }
    }

    public static class IvyPublishInfo {
        private final ArtifactoryClientConfiguration.PublisherHandler publisher;
        public IvyPublishInfo(ArtifactoryClientConfiguration.PublisherHandler publisher) {
            this.publisher = publisher;
        }

        public void setIvyLayout(String ivyLayout) {
            publisher.setIvy(true);
            publisher.setIvyPattern(ivyLayout);
        }

        public String getIvyLayout() {
            return publisher.getIvyPattern();
        }

        public void setArtifactLayout(String artifactLayout) {
            publisher.setIvyArtifactPattern(artifactLayout);
        }

        public String getArtifactLayout() {
            return publisher.getIvyArtifactPattern();
        }

        public boolean getMavenCompatible() {
            return publisher.isM2Compatible();
        }

        public void setMavenCompatible(boolean mavenCompatible) {
            this.publisher.setM2Compatible(mavenCompatible);
        }
    }

}
