package org.jfrog.gradle.plugin.artifactory;

import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;
import org.jfrog.gradle.plugin.artifactory.extractor.GradleDeployDetails;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A shared BuildService for inter-task communication.
 * ArtifactoryTask populates task data at execution time, and ExtractModuleTask/DeployTask read from it.
 */
public abstract class ArtifactoryBuildService implements BuildService<BuildServiceParameters.None> {

    public static final String SERVICE_NAME = "artifactoryBuildService";

    private final Map<String, TaskData> taskDataMap = new ConcurrentHashMap<>();

    public void registerTaskData(String taskPath, TaskData data) {
        taskDataMap.put(taskPath, data);
    }

    public TaskData getTaskData(String taskPath) {
        return taskDataMap.get(taskPath);
    }

    public List<TaskData> getAllTaskData() {
        return new ArrayList<>(taskDataMap.values());
    }

    /**
     * Holds data collected by an ArtifactoryTask at execution time,
     * for consumption by ExtractModuleTask and DeployTask.
     */
    public static class TaskData {
        private final String taskPath;
        private final String projectName;
        private final String projectPath;
        private final Set<GradleDeployDetails> deployDetails;
        private final Map<String, String> configSnapshot;
        private final String moduleType;
        private final boolean hasPublications;

        public TaskData(String taskPath, String projectName, String projectPath,
                        Set<GradleDeployDetails> deployDetails, Map<String, String> configSnapshot,
                        String moduleType, boolean hasPublications) {
            this.taskPath = taskPath;
            this.projectName = projectName;
            this.projectPath = projectPath;
            this.deployDetails = deployDetails;
            this.configSnapshot = configSnapshot;
            this.moduleType = moduleType;
            this.hasPublications = hasPublications;
        }

        public String getTaskPath() {
            return taskPath;
        }

        public String getProjectName() {
            return projectName;
        }

        public String getProjectPath() {
            return projectPath;
        }

        public Set<GradleDeployDetails> getDeployDetails() {
            return deployDetails;
        }

        public Map<String, String> getConfigSnapshot() {
            return configSnapshot;
        }

        public String getModuleType() {
            return moduleType;
        }

        public boolean isHasPublications() {
            return hasPublications;
        }
    }
}
