package org.jfrog.buildinfo;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.jfrog.buildinfo.config.ArtifactoryPluginConvention;
import org.jfrog.buildinfo.listener.ArtifactoryDependencyResolutionListener;
import org.jfrog.buildinfo.listener.ProjectsEvaluatedBuildListener;
import org.jfrog.buildinfo.tasks.CollectDeployDetailsTask;
import org.jfrog.buildinfo.tasks.HelloWorldTask;
import org.jfrog.buildinfo.utils.ConventionUtils;
import org.jfrog.buildinfo.utils.ProjectUtils;
import org.jfrog.buildinfo.utils.TaskUtils;

public class ArtifactoryPlugin implements Plugin<Project> {
    private static final Logger log = Logging.getLogger(ArtifactoryPlugin.class);
    private final ArtifactoryDependencyResolutionListener resolutionListener = new ArtifactoryDependencyResolutionListener();

    @Override
    public void apply(Project project) {
        if (isGradleVersionNotSupported(project)) {
            log.error("Can't apply Artifactory Plugin on Gradle version " + project.getGradle().getGradleVersion());
            return;
        }
        // Add an Artifactory plugin convention to the project module
        ArtifactoryPluginConvention convention = ConventionUtils.getOrCreateArtifactoryConvention(project);
        // Add the collect publications for deploy details and extract module-info tasks to the project module
        CollectDeployDetailsTask collectDeployDetailsTask = TaskUtils.addCollectDeployDetailsTask(project);
        TaskUtils.addExtractModuleInfoTask(collectDeployDetailsTask);
        // Add the extract build-info task to the project module
        TaskUtils.addExtractBuildInfoTask(project);

        if (ProjectUtils.isRootProject(project)) {
            // Add deploy task for the root to only deploy once and not for each submodule
            TaskUtils.addDeploymentTask(project);
            // Add a DependencyResolutionListener, to populate the dependency hierarchy map.
            project.getGradle().addListener(resolutionListener);
        } else {
            // Makes sure the plugin is applied in the root project
            project.getRootProject().getPluginManager().apply(ArtifactoryPlugin.class);
        }
        // Add project evaluation listener to allow aggregation from module to one build-info and deploy
        // Also allow config on demand (lazy)
        project.getGradle().addProjectEvaluationListener(new ProjectsEvaluatedBuildListener());

        // TODO: Remove
        project.getTasks().maybeCreate(HelloWorldTask.TASK_NAME, HelloWorldTask.class);

        // Set build started if not set
        String buildStarted = convention.getClientConfig().info.getBuildStarted();
        if (buildStarted == null || buildStarted.isEmpty()) {
            convention.getClientConfig().info.setBuildStarted(System.currentTimeMillis());
        }

        log.debug("Using Artifactory Plugin for " + project.getPath());
    }

    public boolean isGradleVersionNotSupported(Project project) {
        return Constant.MIN_GRADLE_VERSION.compareTo(project.getGradle().getGradleVersion()) > 0;
    }

    public ArtifactoryDependencyResolutionListener getResolutionListener() {
        return resolutionListener;
    }
}
