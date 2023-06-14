package org.jfrog.gradle.plugin.artifactory;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.jfrog.gradle.plugin.artifactory.config.ArtifactoryPluginConvention;
import org.jfrog.gradle.plugin.artifactory.listener.ArtifactoryDependencyResolutionListener;
import org.jfrog.gradle.plugin.artifactory.listener.ProjectsEvaluatedBuildListener;
import org.jfrog.gradle.plugin.artifactory.task.ArtifactoryTask;
import org.jfrog.gradle.plugin.artifactory.utils.ConventionUtils;
import org.jfrog.gradle.plugin.artifactory.utils.ProjectUtils;
import org.jfrog.gradle.plugin.artifactory.utils.TaskUtils;

public class ArtifactoryPlugin implements Plugin<Project> {
    private static final Logger log = Logging.getLogger(ArtifactoryPlugin.class);
    private final ArtifactoryDependencyResolutionListener resolutionListener = new ArtifactoryDependencyResolutionListener();

    @Override
    public void apply(Project project) {
        if ("buildSrc".equals(project.getName())) {
            log.debug("Artifactory Plugin disabled for {}", project.getPath());
            return;
        }
        if (isGradleVersionNotSupported(project)) {
            log.error("Can't apply Artifactory Plugin on Gradle version " + project.getGradle().getGradleVersion());
            return;
        }
        // Get / Add an Artifactory plugin convention to the project module
        ArtifactoryPluginConvention convention = ConventionUtils.getOrCreateArtifactoryConvention(project);
        // Add the collect publications for deploy details and extract module-info tasks to the project module
        ArtifactoryTask collectDeployDetailsTask = TaskUtils.addCollectDeployDetailsTask(project);
        TaskUtils.addExtractModuleInfoTask(collectDeployDetailsTask);

        if (ProjectUtils.isRootProject(project)) {
            // Add extract build-info and deploy task for the root to only deploy one time
            TaskUtils.addDeploymentTask(project);
            // Add a DependencyResolutionListener, to populate the dependency hierarchy map.
            project.getGradle().addListener(resolutionListener);
        } else {
            // Makes sure the plugin is applied in the root project
            project.getRootProject().getPluginManager().apply(ArtifactoryPlugin.class);
        }
        // Add project evaluation listener to allow aggregation from module to one build-info and deploy
        project.getGradle().addProjectEvaluationListener(new ProjectsEvaluatedBuildListener());

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
