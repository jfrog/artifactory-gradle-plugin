package org.jfrog.buildinfo.utils;

import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.FileCollection;
import org.jfrog.buildinfo.extractor.ModuleInfoFileProducer;
import org.jfrog.buildinfo.tasks.CollectDeployDetailsTask;
import org.jfrog.buildinfo.tasks.DeployTask;
import org.jfrog.buildinfo.tasks.ExtractBuildInfoTask;
import org.jfrog.buildinfo.tasks.ExtractModuleTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TaskUtils {
    private static final Logger log = LoggerFactory.getLogger(TaskUtils.class);

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
        log.debug("Configuring {} task for project (is root: {}) {}", Constant.ARTIFACTORY_PUBLISH_TASK_NAME, ProjectUtils.isRootProject(project), project.getPath());

        CollectDeployDetailsTask collectDeployDetailsTask = project.getTasks().create(Constant.ARTIFACTORY_PUBLISH_TASK_NAME, CollectDeployDetailsTask.class);
        collectDeployDetailsTask.setDescription("Collect artifacts to be later used to generate build-info and deploy.");
        collectDeployDetailsTask.setGroup(Constant.PUBLISH_TASK_GROUP);

        return collectDeployDetailsTask;
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
            log.debug("Configuring {} task for project (is root: {}) {}", Constant.EXTRACT_MODULE_TASK_NAME, ProjectUtils.isRootProject(project), project.getPath());
            task = project.getTasks().create(Constant.EXTRACT_MODULE_TASK_NAME, ExtractModuleTask.class);
            task.setDescription("Extracts module info to an intermediate file.");
        }
        ExtractModuleTask extractModuleTask = (ExtractModuleTask) task;

        extractModuleTask.getOutputs().upToDateWhen(reuseOutputs -> false);
        extractModuleTask.getModuleFile().set(project.getLayout().getBuildDirectory().file(Constant.MODULE_INFO_FILE_NAME));
//        extractModuleTask.mustRunAfter(project.getTasks().withType(CollectDeployDetailsTask.class));
        collectDeployDetailsTask.finalizedBy(extractModuleTask);

        project.getRootProject().getTasks().withType(ExtractBuildInfoTask.class).configureEach(extractBuildInfoTask -> extractBuildInfoTask.registerModuleInfoProducer(new DefaultModuleInfoFileProducer(collectDeployDetailsTask, extractModuleTask)));
    }

    public static ExtractBuildInfoTask addExtractBuildInfoTask(Project project) {
        Task task = project.getTasks().findByName(Constant.EXTRACT_BUILD_INFO_TASK_NAME);
        if (task instanceof ExtractBuildInfoTask) {
            return (ExtractBuildInfoTask) task;
        }
        log.debug("Configuring {} task for root project {}", Constant.EXTRACT_BUILD_INFO_TASK_NAME, project.getPath());

        ExtractBuildInfoTask extractBuildInfoTask = project.getTasks().create(Constant.EXTRACT_BUILD_INFO_TASK_NAME, ExtractBuildInfoTask.class);
        extractBuildInfoTask.setDescription("Extracts build info to a file.");
        extractBuildInfoTask.getOutputs().upToDateWhen(reuseOutputs -> false);

        return extractBuildInfoTask;
    }

    public static void addDeploymentTask(ExtractBuildInfoTask extractBuildInfoTask) {
        Project project = extractBuildInfoTask.getProject();
        Task task = project.getTasks().findByName(Constant.DEPLOY_TASK_NAME);
        if (task instanceof DeployTask) {
            return;
        }
        log.debug("Configuring {} task for root project {}", Constant.DEPLOY_TASK_NAME, project.getPath());

        DeployTask deployTask = project.getTasks().create(Constant.DEPLOY_TASK_NAME, DeployTask.class);
        deployTask.setDescription("Deploys artifacts and build-info to Artifactory.");
        deployTask.setGroup(Constant.PUBLISH_TASK_GROUP);
        deployTask.mustRunAfter(project.getTasks().withType(ExtractBuildInfoTask.class));
        // always deploy after extracting - TODO: check if we need to also do it... maybe remove and change the deploy task name to artifactoryPublish, the collect will be new name?
        extractBuildInfoTask.finalizedBy(deployTask);
        // Extract when deploy
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
                return collectDeployDetailsTask.hasModules();
            }
            return false;
        }

        @Override
        public FileCollection getModuleInfoFiles() {
            return extractModuleTask.getOutputs().getFiles();
        }
    }
}
