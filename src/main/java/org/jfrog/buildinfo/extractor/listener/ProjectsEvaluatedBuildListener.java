package org.jfrog.buildinfo.extractor.listener;

import org.gradle.BuildAdapter;
import org.gradle.StartParameter;
import org.gradle.api.Project;
import org.gradle.api.ProjectEvaluationListener;
import org.gradle.api.ProjectState;
import org.gradle.api.Task;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.buildinfo.config.ArtifactoryPluginConvention;
import org.jfrog.buildinfo.tasks.CollectDeployDetailsTask;
import org.jfrog.buildinfo.utils.Constant;
import org.jfrog.buildinfo.utils.ConventionUtils;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Build event listener to prepare data for publish Task after the projects are evaluated
 * Main actions:
 * 1) Setting the deployment task as a final task for all the CollectDeployDetailsTask tasks
 */
public class ProjectsEvaluatedBuildListener extends BuildAdapter implements ProjectEvaluationListener {
    private static final Logger log = Logging.getLogger(ProjectsEvaluatedBuildListener.class);
    private final Set<Task> detailsCollectingTasks = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * Evaluate the given collectDeployDetailsTask task, preform the follows:
     * 1) Fill the client configuration of the task's project with user and system properties.
     * 2) Adds publishers to the task to collect build details from.
     * @param collectDeployDetailsTask
     */
    private void evaluate(CollectDeployDetailsTask collectDeployDetailsTask) {
        log.debug("Evaluating CollectDeployDetailsTask {}", collectDeployDetailsTask);
        Project project = collectDeployDetailsTask.getProject();
        ArtifactoryPluginConvention convention = ConventionUtils.getArtifactoryConvention(project);
        if (convention == null) {
            return;
        }
        ArtifactoryClientConfiguration clientConfiguration = convention.getClientConfig();
        if (clientConfiguration == null) {
            return;
        }
        // Fill-in the client config with current user/system properties for the given project
//        ConventionUtils.updateConfig(clientConfiguration, project);


        collectDeployDetailsTask.taskEvaluated();
    }

    /**
     * This method is invoked after evaluation of every project.
     * If the configure-on-demand mode is active, Evaluates the CollectDeployDetailsTask tasks
     * @param project The project which was evaluated. Never null.
     * @param state The project evaluation state. If project evaluation failed, the exception is available in this
     * state. Never null.
     */
    @Override
    public void afterEvaluate(Project project, ProjectState state) {
        Set<Task> tasks = project.getTasksByName(Constant.COLLECT_PUBLISH_INFO_TASK_NAME, false);
        StartParameter startParameter = project.getGradle().getStartParameter();
        tasks.forEach(task -> {
            if (task instanceof CollectDeployDetailsTask) {
                CollectDeployDetailsTask collectDeployDetailsTask = (CollectDeployDetailsTask) task;
                detailsCollectingTasks.add(collectDeployDetailsTask);
                if (startParameter.isConfigureOnDemand()) {
                    evaluate(collectDeployDetailsTask);
                }
            }
        });
    }

    /**
     * this method is invoked after all projects are evaluated.
     * Evaluate all the CollectDeployDetailsTask tasks that are not yet evaluated.
     * @param gradle The build which has been evaluated. Never null.
     */
    @Override
    public void projectsEvaluated(Gradle gradle) {
        Set<Task> tasks = gradle.getRootProject().getTasksByName(Constant.COLLECT_PUBLISH_INFO_TASK_NAME, false);
        detailsCollectingTasks.addAll(tasks);
        detailsCollectingTasks.forEach(task -> {
            if ((task instanceof CollectDeployDetailsTask) && !((CollectDeployDetailsTask) task).isEvaluated()) {
                CollectDeployDetailsTask collectDeployDetailsTask = (CollectDeployDetailsTask) task;
                evaluate(collectDeployDetailsTask);
            }
        });
    }

    @Override
    public void beforeEvaluate(Project project) {}
}
