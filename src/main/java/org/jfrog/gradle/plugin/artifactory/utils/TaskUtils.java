package org.jfrog.gradle.plugin.artifactory.utils;

import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.UnknownTaskException;
import org.gradle.api.execution.TaskExecutionGraph;
import org.gradle.api.file.FileCollection;
import org.gradle.api.tasks.TaskProvider;
import org.jfrog.gradle.plugin.artifactory.Constant;
import org.jfrog.gradle.plugin.artifactory.extractor.ModuleInfoFileProducer;
import org.jfrog.gradle.plugin.artifactory.task.ArtifactoryTask;
import org.jfrog.gradle.plugin.artifactory.task.DeployTask;
import org.jfrog.gradle.plugin.artifactory.task.ExtractModuleTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
     * @return the task that was created in the project
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
     * @return - task that was created for the given module
     */
    public static TaskProvider<ArtifactoryTask> addCollectDeployDetailsTask(Project project) {
        try {
            return project.getTasks().named(Constant.ARTIFACTORY_PUBLISH_TASK_NAME, ArtifactoryTask.class);
        } catch (UnknownTaskException e) {
            log.debug("Can't find '" + Constant.ARTIFACTORY_PUBLISH_TASK_NAME + "' task registered at the project", e);
        }
        return registerTaskInProject(Constant.ARTIFACTORY_PUBLISH_TASK_NAME, ArtifactoryTask.class, Constant.ARTIFACTORY_PUBLISH_TASK_DESCRIPTION, project, true);
    }

    /**
     * Adds a task that will run after the given collectDeployDetailsTask task and will extract module info file from the information collected.
     * An ExtractModuleTask task will be added (if not exists) to the given task's project.
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
            log.debug("Can't find '" + Constant.EXTRACT_MODULE_TASK_NAME + "' task registered at the project", e);
        }
        if (taskProvider == null) {
            taskProvider = registerTaskInProject(Constant.EXTRACT_MODULE_TASK_NAME, ExtractModuleTask.class, Constant.EXTRACT_MODULE_TASK_DESCRIPTION, project, false);
        }
        // Lazy Configure
        taskProvider.configure(extractModuleTask -> {
            extractModuleTask.getOutputs().upToDateWhen(reuseOutputs -> false);
            extractModuleTask.getModuleFile().set(project.getLayout().getBuildDirectory().file(Constant.MODULE_INFO_FILE_NAME));
            extractModuleTask.mustRunAfter(project.getTasks().withType(ArtifactoryTask.class));
        });
        TaskProvider<ExtractModuleTask> finalTaskProvider = taskProvider;
        project.getRootProject().getTasks().withType(DeployTask.class).configureEach(deployTask ->
                deployTask.registerModuleInfoProducer(new DefaultModuleInfoFileProducer(collectDeployDetailsTask.get(), finalTaskProvider.get()))
        );
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
            log.debug("Can't find '" + Constant.DEPLOY_TASK_NAME + "' task registered at the project", e);
        }
        registerTaskInProject(Constant.DEPLOY_TASK_NAME, DeployTask.class, Constant.DEPLOY_TASK_DESCRIPTION, project, false);
    }

    /**
     * Find a ArtifactoryTask of a given project that finished to execute or null if not exists.
     *
     * @param project - a project to search for a finished task
     * @return - finished collection task or null if not exists in project
     */
    public static ArtifactoryTask findExecutedCollectionTask(Project project) {
        Set<Task> tasks = project.getTasksByName(Constant.ARTIFACTORY_PUBLISH_TASK_NAME, false);
        if (tasks.isEmpty()) {
            return null;
        }
        ArtifactoryTask artifactoryTask = (ArtifactoryTask) tasks.iterator().next();
        return artifactoryTask.getState().getDidWork() ? artifactoryTask : null;
    }

    /**
     * Get a list of all the ArtifactoryTask tasks of a given project and its submodules
     *
     * @param project - project to get its related tasks
     * @return list of all the ArtifactoryTask that ran
     */
    public static List<ArtifactoryTask> getAllArtifactoryPublishTasks(Project project) {
        TaskExecutionGraph graph = project.getGradle().getTaskGraph();
        List<ArtifactoryTask> tasks = new ArrayList<>();
        for (Task task : graph.getAllTasks()) {
            if (task instanceof ArtifactoryTask) {
                tasks.add(((ArtifactoryTask) task));
            }
        }
        return tasks;
    }

    /**
     * Produce module info files if the module has publications to deploy from the collecting task
     */
    private static class DefaultModuleInfoFileProducer implements ModuleInfoFileProducer {
        private final ArtifactoryTask collectDeployDetailsTask;
        private final ExtractModuleTask extractModuleTask;

        DefaultModuleInfoFileProducer(ArtifactoryTask collectDeployDetailsTask, ExtractModuleTask extractModuleTask) {
            this.collectDeployDetailsTask = collectDeployDetailsTask;
            this.extractModuleTask = extractModuleTask;
        }

        @Override
        public boolean hasModules() {
            if (collectDeployDetailsTask != null && collectDeployDetailsTask.getProject().getState().getExecuted()) {
                return collectDeployDetailsTask.hasPublications();
            }
            return false;
        }

        @Override
        public FileCollection getModuleInfoFiles() {
            return extractModuleTask.getOutputs().getFiles();
        }
    }
}
