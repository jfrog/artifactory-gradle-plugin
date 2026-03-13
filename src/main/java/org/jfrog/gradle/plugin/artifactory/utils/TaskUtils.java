package org.jfrog.gradle.plugin.artifactory.utils;

import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.UnknownTaskException;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;
import org.jfrog.gradle.plugin.artifactory.ArtifactoryBuildService;
import org.jfrog.gradle.plugin.artifactory.Constant;
import org.jfrog.gradle.plugin.artifactory.task.ArtifactoryTask;
import org.jfrog.gradle.plugin.artifactory.task.DeployTask;
import org.jfrog.gradle.plugin.artifactory.task.ExtractModuleTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TaskUtils {
    private static final Logger log = LoggerFactory.getLogger(TaskUtils.class);

    /**
     * Register (deferring task creation) a task in a given project
     *
     * @param taskName        - the name (ID) of the task
     * @param taskClass       - the task class to be created
     * @param taskDescription - the task description
     * @param project         - the project to create the task inside
     * @param publishGroup    - if true this task will be added to publish, else will be added to 'other'
     * @return the taskProvider that was created in the project
     */
    private static <T extends Task> TaskProvider<T> registerTaskInProject(String taskName, Class<T> taskClass, String taskDescription, Project project, boolean publishGroup) {
        log.debug("Configuring {} task for project (is root: {}) {}", taskName, ProjectUtils.isRootProject(project), project.getPath());
        TaskProvider<T> taskProvider = project.getTasks().register(taskName, taskClass);
        taskProvider.configure(task -> {
            task.setDescription(taskDescription);
            if (publishGroup) {
                task.setGroup(Constant.PUBLISHING);
            }
        });
        return taskProvider;
    }

    /**
     * Adds to a given project a task to collect all the publications and information to be deployed.
     *
     * @param project - the module to collect information from
     * @return - taskProvider that was created for the given module
     */
    public static TaskProvider<ArtifactoryTask> addCollectDeployDetailsTask(Project project) {
        try {
            return project.getTasks().named(Constant.ARTIFACTORY_PUBLISH_TASK_NAME, ArtifactoryTask.class);
        } catch (UnknownTaskException e) {
            log.debug("Task '{}' not found in project", Constant.ARTIFACTORY_PUBLISH_TASK_NAME);
        }
        return registerTaskInProject(Constant.ARTIFACTORY_PUBLISH_TASK_NAME, ArtifactoryTask.class, Constant.ARTIFACTORY_PUBLISH_TASK_DESCRIPTION, project, true);
    }

    /**
     * Adds a task that will run after the given collectDeployDetailsTask task and will extract module info file from the information collected.
     * An ExtractModuleTask task will be added (if not exists) to the given task's project.
     *
     * extract module task is responsible for extracting module details, artifacts and dependencies.
     * artifactoryTask is responsible for collecting deployment details from a project's publications. It figures out what needs to be deployed and how it should be deployed.
     * deploymentTask is responsible for execute the deployment of artifacts and build-info to Artifactory for the entire build.
     *
     * @param collectDeployDetailsTask - the task that will provide the information to produce the module info file
     * @param project                  - the project that the collectDeployDetailsTask is configured in to register the new task in it.
     */
    public static void addExtractModuleInfoTask(TaskProvider<ArtifactoryTask> collectDeployDetailsTask, Project project) {
        TaskProvider<ExtractModuleTask> taskProvider = null;
        // Register
        try {
            taskProvider = project.getTasks().named(Constant.EXTRACT_MODULE_TASK_NAME, ExtractModuleTask.class);
        } catch (UnknownTaskException e) {
            log.debug("Task '{}' not found in project", Constant.EXTRACT_MODULE_TASK_NAME);
        }
        if (taskProvider == null) {
            taskProvider = registerTaskInProject(Constant.EXTRACT_MODULE_TASK_NAME, ExtractModuleTask.class, Constant.EXTRACT_MODULE_TASK_DESCRIPTION, project, false);
        }
        // Lazy Configure
        taskProvider.configure(extractModuleTask -> {
            extractModuleTask.getOutputs().upToDateWhen(reuseOutputs -> false);
            extractModuleTask.getModuleFile().set(project.getLayout().getBuildDirectory().file(Constant.MODULE_INFO_FILE_NAME));
            extractModuleTask.mustRunAfter(project.getTasks().withType(ArtifactoryTask.class));

            extractModuleTask.setProjectInfo(
                    project.getPath(),
                    project.getName(),
                    project.getGroup().toString(),
                    project.getVersion().toString()
            );
        });

        TaskProvider<ExtractModuleTask> finalTaskProvider = taskProvider;
        // Wire the ExtractModuleTask output files to the DeployTask inputs
        project.getRootProject().getTasks().withType(DeployTask.class).configureEach(deployTask -> {
            deployTask.addModuleInfoFiles(finalTaskProvider.get().getOutputs().getFiles());
        });
    }

    /**
     * Adds a task to deploy the artifacts of a given project, extract information on the build and deploy it.
     *
     * @param project - project to add the task to, should be the root project
     */
    public static void addDeploymentTask(Project project) {
        try {
            project.getTasks().named(Constant.DEPLOY_TASK_NAME, DeployTask.class);
            return;
        } catch (UnknownTaskException e) {
            log.debug("Task '{}' not found in project", Constant.DEPLOY_TASK_NAME);
        }
        TaskProvider<DeployTask> taskProvider = registerTaskInProject(Constant.DEPLOY_TASK_NAME, DeployTask.class, Constant.DEPLOY_TASK_DESCRIPTION, project, false);
        taskProvider.configure(deployTask -> {
            deployTask.setRootProjectName(project.getName());
            deployTask.setGradleVersion(project.getGradle().getGradleVersion());
            deployTask.getRootBuildDirectory().set(project.getLayout().getBuildDirectory());
        });
    }

    /**
     * Configure the BuildService on all relevant tasks.
     */
    public static void configureBuildService(Project project, Provider<ArtifactoryBuildService> serviceProvider) {
        project.getTasks().withType(ArtifactoryTask.class).configureEach(task -> {
            task.getBuildServiceProperty().set(serviceProvider);
            task.usesService(serviceProvider);
        });
        project.getTasks().withType(ExtractModuleTask.class).configureEach(task -> {
            task.getBuildServiceProperty().set(serviceProvider);
            task.usesService(serviceProvider);
        });
        project.getTasks().withType(DeployTask.class).configureEach(task -> {
            task.getBuildServiceProperty().set(serviceProvider);
            task.usesService(serviceProvider);
        });
    }
}
