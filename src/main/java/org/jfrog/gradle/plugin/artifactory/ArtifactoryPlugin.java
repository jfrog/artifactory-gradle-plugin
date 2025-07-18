package org.jfrog.gradle.plugin.artifactory;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.TaskProvider;
import org.jfrog.gradle.plugin.artifactory.dsl.ArtifactoryPluginConvention;
import org.jfrog.gradle.plugin.artifactory.listener.ArtifactoryDependencyResolutionListener;
import org.jfrog.gradle.plugin.artifactory.listener.ProjectsEvaluatedBuildListener;
import org.jfrog.gradle.plugin.artifactory.task.ArtifactoryTask;
import org.jfrog.gradle.plugin.artifactory.utils.ExtensionsUtils;
import org.jfrog.gradle.plugin.artifactory.utils.ProjectUtils;
import org.jfrog.gradle.plugin.artifactory.utils.TaskUtils;

import static org.jfrog.gradle.plugin.artifactory.utils.PluginUtils.assertGradleVersionSupported;

public class ArtifactoryPlugin implements Plugin<Project> {
    private static final Logger log = Logging.getLogger(ArtifactoryPlugin.class);
    private final ArtifactoryDependencyResolutionListener resolutionListener = new ArtifactoryDependencyResolutionListener();
    private final ProjectsEvaluatedBuildListener projectsEvaluatedBuildListener = new ProjectsEvaluatedBuildListener();

    @Override
    public void apply(Project project) {
        if (!shouldApplyPluginOnProject(project)) {
            return;
        }
        // Get / Add an Artifactory plugin extension to the project module
        ArtifactoryPluginConvention extension = ExtensionsUtils.getOrCreateArtifactoryExtension(project);
        // Add the collect publications for deploy details and extract module-info tasks to the project module
        TaskProvider<ArtifactoryTask> collectDeployDetailsTask = TaskUtils.addCollectDeployDetailsTask(project);
        TaskUtils.addExtractModuleInfoTask(collectDeployDetailsTask, project);

        if (ProjectUtils.isRootProject(project)) {
            // Add extract build-info and deploy task for the root to only deploy one time
            TaskUtils.addDeploymentTask(project);
            project.getAllprojects().forEach(subproject -> {
                // Add a DependencyResolutionListener, to populate the dependency hierarchy map
                subproject.getConfigurations().all(config -> config.getIncoming().afterResolve(resolutionListener::afterResolve));
                // Add after_evaluated listener to run the ArtifactoryPublish task before root deploy task
                if (!subproject.getState().getExecuted()) {
                    subproject.afterEvaluate((projectsEvaluatedBuildListener::afterEvaluate));
                }
            });
            // Add projects_evaluated listener to evaluate all the ArtifactoryTask tasks for the entire project that are not yet evaluated.
            project.getGradle().projectsEvaluated(projectsEvaluatedBuildListener::projectsEvaluated);

            // Set build started if not set
            String buildStarted = extension.getClientConfig().info.getBuildStarted();
            if (buildStarted == null || buildStarted.isEmpty()) {
                extension.getClientConfig().info.setBuildStarted(System.currentTimeMillis());
            }
        } else {
            // Makes sure the plugin is applied in the root project
            project.getRootProject().getPluginManager().apply(ArtifactoryPlugin.class);
        }

        log.debug("Using Artifactory Plugin for " + project.getPath());
    }

    private boolean shouldApplyPluginOnProject(Project project) {
        if ("buildSrc".equals(project.getName())) {
            log.debug("Artifactory Plugin disabled for {}", project.getPath());
            return false;
        }
        assertGradleVersionSupported(project.getGradle());
        return true;
    }

    public ArtifactoryDependencyResolutionListener getResolutionListener() {
        return resolutionListener;
    }
}
