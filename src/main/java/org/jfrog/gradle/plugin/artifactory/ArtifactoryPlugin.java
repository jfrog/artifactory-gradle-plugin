package org.jfrog.gradle.plugin.artifactory;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;
import org.jfrog.gradle.plugin.artifactory.dsl.ArtifactoryPluginConvention;
import org.jfrog.gradle.plugin.artifactory.listener.ArtifactoryDependencyResolutionListener;
import org.jfrog.gradle.plugin.artifactory.listener.ProjectsEvaluatedBuildListener;
import org.jfrog.gradle.plugin.artifactory.task.ArtifactoryTask;
import org.jfrog.gradle.plugin.artifactory.task.DeployTask;
import org.jfrog.gradle.plugin.artifactory.task.ExtractModuleTask;
import org.jfrog.gradle.plugin.artifactory.utils.ClientConfigHelper;
import org.jfrog.gradle.plugin.artifactory.utils.ExtensionsUtils;
import org.jfrog.gradle.plugin.artifactory.utils.ProjectUtils;
import org.jfrog.gradle.plugin.artifactory.utils.TaskUtils;

import java.util.Map;

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
            // Clear static listener state for this build (prevents stale data in Gradle daemon)
            ArtifactoryDependencyResolutionListener.resetState();

            // Register the BuildService for inter-task communication
            Provider<ArtifactoryBuildService> serviceProvider = project.getGradle().getSharedServices()
                    .registerIfAbsent(ArtifactoryBuildService.SERVICE_NAME, ArtifactoryBuildService.class, spec -> {
                    });

            // Add extract build-info and deploy task for the root to only deploy one time
            TaskUtils.addDeploymentTask(project);

            // Configure BuildService on all tasks in all projects
            project.getAllprojects().forEach(subproject -> {
                // Add a DependencyResolutionListener, to populate the dependency hierarchy map
                subproject.getConfigurations().all(config -> config.getIncoming().afterResolve(resolutionListener::afterResolve));
                // Add after_evaluated listener to run the ArtifactoryPublish task before root deploy task
                if (!subproject.getState().getExecuted()) {
                    subproject.afterEvaluate((projectsEvaluatedBuildListener::afterEvaluate));
                }
                // Configure BuildService on tasks in each subproject
                TaskUtils.configureBuildService(subproject, serviceProvider);
            });

            // Also configure the root config snapshot on the deploy task and
            // update config snapshots on extract module tasks after projects are evaluated
            project.getGradle().projectsEvaluated(gradle -> {
                projectsEvaluatedBuildListener.projectsEvaluated(gradle);

                // After all projects are evaluated, snapshot root config and set it on DeployTask
                ArtifactoryPluginConvention rootExt = ExtensionsUtils.getArtifactoryExtension(project);
                if (rootExt != null) {
                    Map<String, String> rootSnapshot = ClientConfigHelper.snapshotConfig(rootExt.getClientConfig());
                    project.getTasks().withType(DeployTask.class).configureEach(deployTask -> {
                        deployTask.setRootConfigSnapshot(rootSnapshot);
                    });
                }

                // Update config snapshot on ExtractModuleTasks from each project's extension
                project.getAllprojects().forEach(sub -> {
                    sub.getTasks().withType(ExtractModuleTask.class).configureEach(emt -> {
                        ArtifactoryPluginConvention subExt = ExtensionsUtils.getExtensionWithPublisher(sub);
                        if (subExt != null) {
                            emt.setConfigSnapshot(ClientConfigHelper.snapshotConfig(subExt.getClientConfig()));
                        }
                    });
                });
            });
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
