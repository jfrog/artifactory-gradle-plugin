package org.jfrog.gradle.plugin.artifactory.listener;

import org.apache.commons.lang3.StringUtils;
import org.gradle.BuildAdapter;
import org.gradle.StartParameter;
import org.gradle.api.Project;
import org.gradle.api.ProjectEvaluationListener;
import org.gradle.api.ProjectState;
import org.gradle.api.Task;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.publish.PublishingExtension;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.gradle.plugin.artifactory.config.ArtifactoryPluginConvention;
import org.jfrog.gradle.plugin.artifactory.Constant;
import org.jfrog.gradle.plugin.artifactory.task.ArtifactoryTask;
import org.jfrog.gradle.plugin.artifactory.utils.ConventionUtils;
import org.jfrog.gradle.plugin.artifactory.utils.ProjectUtils;
import org.jfrog.gradle.plugin.artifactory.utils.PublicationUtils;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Build event listener to prepare data for publish Task after the projects are evaluated
 * Main actions:
 * 1) Prepare (evaluate) task dependencies with other task
 * 2) Grabbing user and system properties
 * 3) Apply default action to projects
 * 4) Adding default attributes that are used in CI mode
 */
public class ProjectsEvaluatedBuildListener extends BuildAdapter implements ProjectEvaluationListener {
    private static final Logger log = Logging.getLogger(ProjectsEvaluatedBuildListener.class);
    private final Set<Task> detailsCollectingTasks = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * Prepare the given task before running.
     */
    private void evaluate(ArtifactoryTask collectDeployDetailsTask) {
        log.debug("Try to evaluate {}", collectDeployDetailsTask);
        Project project = collectDeployDetailsTask.getProject();
        ArtifactoryPluginConvention convention = ConventionUtils.getArtifactoryConvention(project);
        if (convention == null) {
            log.debug("Can't find artifactory convention.");
            return;
        }
        ArtifactoryClientConfiguration clientConfiguration = convention.getClientConfig();
        if (clientConfiguration == null) {
            log.debug("Client configuration not defined.");
            return;
        }
        // Fill-in the client config with current user/system properties for the given project
        ConventionUtils.updateConfig(clientConfiguration, project);
        // Set task attributes if running on CI Server
        if (collectDeployDetailsTask.isCiServerBuild()) {
            addCiAttributesToTask(collectDeployDetailsTask, clientConfiguration);
        }
        collectDeployDetailsTask.evaluateTask();
    }

    private void addCiAttributesToTask(ArtifactoryTask collectDeployDetailsTask, ArtifactoryClientConfiguration clientConfiguration) {
        PublishingExtension publishingExtension = (PublishingExtension) collectDeployDetailsTask.getProject().getExtensions().findByName(Constant.PUBLISHING);
        if (publishingExtension == null) {
            log.debug("Can't find publishing extensions that is defined for the project {}", collectDeployDetailsTask.getProject().getPath());
            return;
        }
        String publicationsNames = clientConfiguration.publisher.getPublications();
        if (StringUtils.isNotBlank(publicationsNames)) {
            collectDeployDetailsTask.publications((Object[]) publicationsNames.split(","));
        } else if (ProjectUtils.hasOneOfComponents(collectDeployDetailsTask.getProject(), Constant.JAVA, Constant.JAVA_PLATFORM)) {
            PublicationUtils.addDefaultPublications(collectDeployDetailsTask, publishingExtension);
        }
    }

    /**
     * This method is invoked after evaluation of every project.
     * If the configure-on-demand mode is active, Evaluates the ArtifactoryTask tasks
     *
     * @param project The project which was evaluated. Never null.
     * @param state   The project evaluation state. If project evaluation failed, the exception is available in this
     *                state. Never null.
     */
    @Override
    public void afterEvaluate(Project project, ProjectState state) {
        StartParameter startParameter = project.getGradle().getStartParameter();
        Set<Task> tasks = project.getTasksByName(Constant.ARTIFACTORY_PUBLISH_TASK_NAME, false);
        tasks.forEach(task -> {
            if (!(task instanceof ArtifactoryTask)) {
                return;
            }
            ArtifactoryTask collectDeployDetailsTask = (ArtifactoryTask) task;
            detailsCollectingTasks.add(collectDeployDetailsTask);
            collectDeployDetailsTask.finalizeByDeployTask(project);
            if (startParameter.isConfigureOnDemand()) {
                evaluate(collectDeployDetailsTask);
            }
        });
    }

    /**
     * This method is invoked after all projects are evaluated.
     * Evaluate all the ArtifactoryTask tasks that are not yet evaluated (if configure-on-demand on the project is not requested).
     *
     * @param gradle The build which has been evaluated. Never null.
     */
    @Override
    public void projectsEvaluated(Gradle gradle) {
        Set<Task> tasks = gradle.getRootProject().getTasksByName(Constant.ARTIFACTORY_PUBLISH_TASK_NAME, false);
        detailsCollectingTasks.addAll(tasks);
        detailsCollectingTasks.forEach(task -> {
            if ((task instanceof ArtifactoryTask) && !((ArtifactoryTask) task).isEvaluated()) {
                ArtifactoryTask collectDeployDetailsTask = (ArtifactoryTask) task;
                evaluate(collectDeployDetailsTask);
                collectDeployDetailsTask.finalizeByDeployTask(collectDeployDetailsTask.getProject());
            }
        });
    }

    @Override
    public void beforeEvaluate(Project project) {
    }
}
