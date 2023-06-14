package org.jfrog.gradle.plugin.artifactory.utils;

import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.execution.TaskExecutionGraph;
import org.gradle.api.file.FileCollection;
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
     * Create a task in a given project
     * @param taskName        - the name (ID) of the task
     * @param taskClass       - the task class to be created
     * @param taskDescription - the task description
     * @param project         - the project to create the task inside
     * @param publishGroup    - if true this task will be added to publish, else will be added to 'other'
     * @return the task that was created in the project
     */
    private static <T extends Task> T createTaskInProject(String taskName, Class<T> taskClass, String taskDescription, Project project, boolean publishGroup) {
        log.debug("Configuring {} task for project (is root: {}) {}", taskName, ProjectUtils.isRootProject(project), project.getPath());
        T task = project.getTasks().create(taskName, taskClass);
        task.setDescription(taskDescription);
        if (publishGroup) {
            task.setGroup(Constant.PUBLISH_TASK_GROUP);
        }
        return task;
    }

    /**
     * Adds to a given project a task to collect all the publications and information to be deployed.
     * @param project - the module to collect information from
     * @return - task that was created for the given module
     */
    public static ArtifactoryTask addCollectDeployDetailsTask(Project project) {
        Task task = project.getTasks().findByName(Constant.ARTIFACTORY_PUBLISH_TASK_NAME);
        if (task instanceof ArtifactoryTask) {
            return (ArtifactoryTask) task;
        }
        return createTaskInProject(Constant.ARTIFACTORY_PUBLISH_TASK_NAME, ArtifactoryTask.class, Constant.ARTIFACTORY_PUBLISH_TASK_DESCRIPTION, project, true);
    }

    /**
     * Find a CollectDeployDetailsTask of a given project that finished to execute or null if not exists.
     * @param project - a project to search for a finished task
     * @return - finished collection task or null if not exists in project
     */
    public static ArtifactoryTask findExecutedCollectionTask(Project project) {
        Set<Task> tasks = project.getTasksByName(Constant.ARTIFACTORY_PUBLISH_TASK_NAME, false);
        if (tasks.isEmpty()) {
            return null;
        }
        ArtifactoryTask artifactoryTask = (ArtifactoryTask)tasks.iterator().next();
        return artifactoryTask.getState().getDidWork() ? artifactoryTask : null;
    }

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
     * Adds a task that will run after the given collectDeployDetailsTask task and will extract module info file from the information collected.
     * An ExtractModuleTask task will be added (if not exists) to the given task's project.
     * @param collectDeployDetailsTask - the task that will provide the information to produce the module info file
     */
    public static void addExtractModuleInfoTask(ArtifactoryTask collectDeployDetailsTask) {
        Project project = collectDeployDetailsTask.getProject();
        Task task = project.getTasks().findByName(Constant.EXTRACT_MODULE_TASK_NAME);
        if (!(task instanceof ExtractModuleTask)) {
            task = createTaskInProject(Constant.EXTRACT_MODULE_TASK_NAME, ExtractModuleTask.class, Constant.EXTRACT_MODULE_TASK_DESCRIPTION, project, false);
        }
        ExtractModuleTask extractModuleTask = (ExtractModuleTask) task;

        extractModuleTask.getOutputs().upToDateWhen(reuseOutputs -> false);
        extractModuleTask.getModuleFile().set(project.getLayout().getBuildDirectory().file(Constant.MODULE_INFO_FILE_NAME));
        extractModuleTask.mustRunAfter(project.getTasks().withType(ArtifactoryTask.class));

        project.getRootProject().getTasks().withType(DeployTask.class).configureEach(deployTask ->
                deployTask.registerModuleInfoProducer(new DefaultModuleInfoFileProducer(collectDeployDetailsTask, extractModuleTask))
        );
    }

    public static void addDeploymentTask(Project project) {
        Task task = project.getTasks().findByName(Constant.DEPLOY_TASK_NAME);
        if (task instanceof DeployTask) {
            return;
        }
        createTaskInProject(Constant.DEPLOY_TASK_NAME, DeployTask.class, Constant.DEPLOY_TASK_DESCRIPTION, project, false);
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
