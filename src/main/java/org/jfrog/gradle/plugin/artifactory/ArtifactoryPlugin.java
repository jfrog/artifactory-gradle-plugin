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
import static org.jfrog.gradle.plugin.artifactory.utils.SharedBuildLogicUtils.isIncludeSharedBuildEnabled;

public class ArtifactoryPlugin implements Plugin<Project> {
    private static final Logger log = Logging.getLogger(ArtifactoryPlugin.class);
    private final ArtifactoryDependencyResolutionListener resolutionListener = new ArtifactoryDependencyResolutionListener();
    private final ProjectsEvaluatedBuildListener projectsEvaluatedBuildListener = new ProjectsEvaluatedBuildListener();

    @Override
    public void apply(Project project) {
        if (!shouldApplyPluginOnProject(project)) {
            return;
        }

        // Flag off: same as main — create/reuse the extension and continue even on a second apply.
        // Flag on: skip a second apply so init script + plugins { id } do not crash.
        ArtifactoryPluginConvention extension;
        if (isIncludeSharedBuildEnabled(project)) {
            extension = extensionForSharedBuildApply(project);
            if (extension == null) {
                return;
            }
        } else {
            extension = ExtensionsUtils.getOrCreateArtifactoryExtension(project);
        }

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
        } else {
            // Makes sure the plugin is applied in the root project
            project.getRootProject().getPluginManager().apply(ArtifactoryPlugin.class);
        }

        // Set build started if not set
        String buildStarted = extension.getClientConfig().info.getBuildStarted();
        if (buildStarted == null || buildStarted.isEmpty()) {
            extension.getClientConfig().info.setBuildStarted(System.currentTimeMillis());
        }

        log.debug("Using Artifactory Plugin for " + project.getPath());
    }

    /**
     * Flag-on only. Null means this apply is a duplicate and should stop.
     */
    private ArtifactoryPluginConvention extensionForSharedBuildApply(Project project) {
        try {
            if (project.getExtensions().findByName(Constant.ARTIFACTORY) != null) {
                log.debug("Artifactory extension already present on {}, skipping duplicate apply", project.getPath());
                return null;
            }
            return ExtensionsUtils.getOrCreateArtifactoryExtension(project);
        } catch (IllegalArgumentException e) {
            ArtifactoryPluginConvention existing = project.getExtensions().findByType(ArtifactoryPluginConvention.class);
            if (existing != null) {
                log.debug("Artifactory extension already registered on {}, reusing existing instance", project.getPath());
                return existing;
            }
            throw e;
        }
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
