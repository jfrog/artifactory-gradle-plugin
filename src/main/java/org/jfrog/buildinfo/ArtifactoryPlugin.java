package org.jfrog.buildinfo;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.jfrog.buildinfo.config.ArtifactoryPluginConvention;
import org.jfrog.buildinfo.extractor.listener.ArtifactoryDependencyResolutionListener;
import org.jfrog.buildinfo.extractor.listener.ProjectsEvaluatedBuildListener;
import org.jfrog.buildinfo.tasks.CollectDeployDetailsTask;
import org.jfrog.buildinfo.tasks.ExtractBuildInfoTask;
import org.jfrog.buildinfo.tasks.HelloWorldTask;
import org.jfrog.buildinfo.utils.Constant;
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
        // Then add the collect deploy details task to collect publishing information on the module
        CollectDeployDetailsTask collectDeployDetailsTask = TaskUtils.addCollectDeployDetailsTask(project);
        TaskUtils.addExtractModuleInfoTask(collectDeployDetailsTask);

        if (ProjectUtils.isRootProject(project)) {
            // Build-info, Deploy and Distribute tasks are added once for the root to finalize all modules tasks that prepare the needed information
            ExtractBuildInfoTask extractBuildInfoTask = TaskUtils.addExtractBuildInfoTask(project);
            TaskUtils.addDeploymentTask(extractBuildInfoTask);
            // Add a DependencyResolutionListener, to populate the dependency hierarchy map.
            project.getGradle().addListener(resolutionListener);
        } else {
            // Makes sure the plugin is applied in the root project
            project.getRootProject().getPluginManager().apply(ArtifactoryPlugin.class);
        }

        // TODO: Remove
        project.getTasks().maybeCreate(HelloWorldTask.TASK_NAME, HelloWorldTask.class);


        // Set build started if not set
        // TODO: check why
        String buildStarted = convention.getClientConfig().info.getBuildStarted();
        if (buildStarted == null || buildStarted.isEmpty()) {
            convention.getClientConfig().info.setBuildStarted(System.currentTimeMillis());
        }

        project.getGradle().addProjectEvaluationListener(new ProjectsEvaluatedBuildListener());
        log.debug("Using Artifactory Plugin for " + project.getPath());
    }

    public boolean isGradleVersionNotSupported(Project project) {
        return Constant.MIN_GRADLE_VERSION.compareTo(project.getGradle().getGradleVersion()) > 0;
    }

    public ArtifactoryDependencyResolutionListener getResolutionListener() {
        return resolutionListener;
    }
}
