package org.jfrog.buildinfo.utils;

import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.FileCollection;
import org.jfrog.buildinfo.Constant;
import org.jfrog.buildinfo.extractor.ModuleInfoFileProducer;
import org.jfrog.buildinfo.tasks.CollectDeployDetailsTask;
import org.jfrog.buildinfo.tasks.DeployTask;
import org.jfrog.buildinfo.tasks.ExtractBuildInfoTask;
import org.jfrog.buildinfo.tasks.ExtractModuleTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    public static CollectDeployDetailsTask addCollectDeployDetailsTask(Project project) {
        Task task = project.getTasks().findByName(Constant.ARTIFACTORY_PUBLISH_TASK_NAME);
        if (task instanceof CollectDeployDetailsTask) {
            return (CollectDeployDetailsTask) task;
        }
        return createTaskInProject(Constant.ARTIFACTORY_PUBLISH_TASK_NAME, CollectDeployDetailsTask.class, Constant.ARTIFACTORY_PUBLISH_TASK_DESCRIPTION, project, true);
    }

    /**
     * Find a CollectDeployDetailsTask of a given project that finished to execute or null if not exists.
     * @param project - a project to search for a finished task
     * @return - finished collection task or null if not exists in project
     */
    public static CollectDeployDetailsTask findExecutedCollectionTask(Project project) {
        Set<Task> tasks = project.getTasksByName(Constant.ARTIFACTORY_PUBLISH_TASK_NAME, false);
        if (tasks.isEmpty()) {
            return null;
        }
        CollectDeployDetailsTask artifactoryTask = (CollectDeployDetailsTask)tasks.iterator().next();
        return artifactoryTask.getState().getDidWork() ? artifactoryTask : null;
    }

    /**
     * Adds a task that will run after the given collectDeployDetailsTask task and will extract module info file from the information collected.
     * An ExtractModuleTask task will be added (if not exists) to the given task's project.
     * @param collectDeployDetailsTask - the task that will provide the information to produce the module info file
     */
    public static void addExtractModuleInfoTask(CollectDeployDetailsTask collectDeployDetailsTask) {
        Project project = collectDeployDetailsTask.getProject();
        Task task = project.getTasks().findByName(Constant.EXTRACT_MODULE_TASK_NAME);
        if (!(task instanceof ExtractModuleTask)) {
            task = createTaskInProject(Constant.EXTRACT_MODULE_TASK_NAME, ExtractModuleTask.class, Constant.EXTRACT_MODULE_TASK_DESCRIPTION, project, false);
        }
        ExtractModuleTask extractModuleTask = (ExtractModuleTask) task;

        extractModuleTask.getOutputs().upToDateWhen(reuseOutputs -> false);
        extractModuleTask.getModuleFile().set(project.getLayout().getBuildDirectory().file(Constant.MODULE_INFO_FILE_NAME));
        extractModuleTask.mustRunAfter(project.getTasks().withType(CollectDeployDetailsTask.class));
//        collectDeployDetailsTask.finalizedBy(extractModuleTask);

        project.getRootProject().getTasks().withType(ExtractBuildInfoTask.class).configureEach(extractBuildInfoTask ->
        {
            log.info("{} Registered info producer from ({} , {})", extractBuildInfoTask.getPath(), collectDeployDetailsTask.getPath(), extractModuleTask.getPath());
            extractBuildInfoTask.registerModuleInfoProducer(new DefaultModuleInfoFileProducer(collectDeployDetailsTask, extractModuleTask));
        });
    }

    public static ExtractBuildInfoTask addExtractBuildInfoTask(Project project) {
        Task task = project.getTasks().findByName(Constant.EXTRACT_BUILD_INFO_TASK_NAME);
        if (task instanceof ExtractBuildInfoTask) {
            return (ExtractBuildInfoTask) task;
        }
        return createTaskInProject(Constant.EXTRACT_BUILD_INFO_TASK_NAME, ExtractBuildInfoTask.class, Constant.EXTRACT_BUILD_INFO_TASK_DESCRIPTION, project, false);
    }

    public static void addDeploymentTask(ExtractBuildInfoTask extractBuildInfoTask) {
        Project project = extractBuildInfoTask.getProject();
        Task task = project.getTasks().findByName(Constant.DEPLOY_TASK_NAME);
        if (task instanceof DeployTask) {
            return;
        }
        DeployTask deployTask = createTaskInProject(Constant.DEPLOY_TASK_NAME, DeployTask.class, Constant.DEPLOY_TASK_DESCRIPTION, project, false);
        deployTask.dependsOn(extractBuildInfoTask);
    }

    /**
     * Produce module info files if the module has publications to deploy from the collecting task
     */
    private static class DefaultModuleInfoFileProducer implements ModuleInfoFileProducer {
        private final CollectDeployDetailsTask collectDeployDetailsTask;
        private final ExtractModuleTask extractModuleTask;

        DefaultModuleInfoFileProducer(CollectDeployDetailsTask collectDeployDetailsTask, ExtractModuleTask extractModuleTask) {
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
