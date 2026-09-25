package org.jfrog.gradle.plugin.artifactory.extractor;

import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.jfrog.build.api.builder.ModuleType;
import org.jfrog.build.extractor.builder.ArtifactBuilder;
import org.jfrog.build.extractor.builder.DependencyBuilder;
import org.jfrog.build.extractor.builder.ModuleBuilder;
import org.jfrog.build.extractor.ci.Artifact;
import org.jfrog.build.extractor.ci.Dependency;
import org.jfrog.build.extractor.ci.Module;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.build.extractor.clientConfiguration.deploy.DeployDetails;
import org.jfrog.gradle.plugin.artifactory.ArtifactoryBuildService;
import org.jfrog.gradle.plugin.artifactory.task.ExtractModuleTask;
import org.jfrog.gradle.plugin.artifactory.utils.ClientConfigHelper;
import org.jfrog.gradle.plugin.artifactory.utils.ProjectUtils;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.jfrog.build.extractor.BuildInfoExtractorUtils.getTypeString;
import static org.jfrog.gradle.plugin.artifactory.utils.PluginUtils.getModuleType;

public class GradleModuleExtractor {
    private static final Logger log = Logging.getLogger(GradleModuleExtractor.class);

    /**
     * Extract module information using pre-stored values and BuildService task data.
     */
    public Module extractModule(String projectPath, String projectName, String projectGroup, String projectVersion,
                                Map<String, String> configSnapshot,
                                ArtifactoryBuildService.TaskData taskData,
                                Map<String, Map<String, String[][]>> modulesHierarchyMap,
                                List<ExtractModuleTask.PreCollectedDependency> preCollectedDependencies) {
        ModuleType moduleType;
        Set<GradleDeployDetails> gradleDeployDetails;

        if (taskData != null) {
            moduleType = getModuleType(taskData.getModuleType());
            gradleDeployDetails = taskData.getDeployDetails();
        } else {
            moduleType = ModuleType.GRADLE;
            gradleDeployDetails = new HashSet<>();
        }

        String moduleId = ProjectUtils.getId(projectGroup, projectName, projectVersion);
        return getModuleBuilder(projectPath, moduleId, moduleType, gradleDeployDetails, configSnapshot, modulesHierarchyMap, preCollectedDependencies).build();
    }

    /**
     * Create a ModuleBuilder ready to be built for the given project and deployment details
     *
     * @param projectPath         - project path
     * @param moduleId            - module ID (group:name:version)
     * @param moduleType          - module type
     * @param gradleDeployDetails - module deployment details
     * @param configSnapshot      - client configuration snapshot
     * @param modulesHierarchyMap - dependency hierarchy map
     * @param preCollectedDependencies - pre-collected dependency data
     */
    private ModuleBuilder getModuleBuilder(String projectPath, String moduleId, ModuleType moduleType,
                                           Set<GradleDeployDetails> gradleDeployDetails,
                                           Map<String, String> configSnapshot,
                                           Map<String, Map<String, String[][]>> modulesHierarchyMap,
                                           List<ExtractModuleTask.PreCollectedDependency> preCollectedDependencies) {
        String repo = gradleDeployDetails.stream()
                .map(GradleDeployDetails::getDeployDetails)
                .map(DeployDetails::getTargetRepository)
                .findAny()
                .orElse("");
        ModuleBuilder builder = new ModuleBuilder()
                .type(moduleType)
                .id(moduleId)
                .repository(repo);
        try {
            // Extract dependencies from pre-collected data
            builder.dependencies(buildDependencies(moduleId, modulesHierarchyMap, preCollectedDependencies));

            // Extract the module's artifacts
            ArtifactoryClientConfiguration.PublisherHandler publisher = null;
            if (configSnapshot != null) {
                publisher = ClientConfigHelper.restoreConfig(configSnapshot).publisher;
            }
            if (publisher == null) {
                log.warn("No publisher config found for module: " + moduleId);
                return builder;
            }
            builder.excludedArtifacts(calculateArtifacts(ProjectUtils.filterIncludeExcludeDetails(projectPath, publisher, gradleDeployDetails, false)));
            builder.artifacts(calculateArtifacts(ProjectUtils.filterIncludeExcludeDetails(projectPath, publisher, gradleDeployDetails, true)));
        } catch (Exception e) {
            log.error("Error occur during extraction: ", e);
        }
        return builder;
    }

    /**
     * Build Dependency list from pre-collected dependency data.
     */
    private List<Dependency> buildDependencies(String moduleId,
                                               Map<String, Map<String, String[][]>> modulesHierarchyMap,
                                               List<ExtractModuleTask.PreCollectedDependency> preCollectedDependencies) {
        if (preCollectedDependencies == null || preCollectedDependencies.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, String[][]> requestedByMap = null;
        if (modulesHierarchyMap != null) {
            requestedByMap = modulesHierarchyMap.get(moduleId);
        }

        List<Dependency> dependencies = new ArrayList<>();
        for (ExtractModuleTask.PreCollectedDependency dep : preCollectedDependencies) {
            // Check if already added (merge scopes)
            Dependency existing = null;
            for (Dependency d : dependencies) {
                if (d.getId().equals(dep.getId())) {
                    existing = d;
                    break;
                }
            }
            if (existing != null) {
                Set<String> mergedScopes = existing.getScopes();
                mergedScopes.addAll(dep.getScopes());
                existing.setScopes(mergedScopes);
                continue;
            }

            DependencyBuilder depBuilder = new DependencyBuilder()
                    .id(dep.getId())
                    .type(dep.getType())
                    .scopes(dep.getScopes())
                    .md5(dep.getMd5())
                    .sha1(dep.getSha1())
                    .sha256(dep.getSha256());
            if (requestedByMap != null) {
                depBuilder.requestedBy(requestedByMap.get(dep.getId()));
            }
            dependencies.add(depBuilder.build());
        }
        return dependencies;
    }

    /**
     * Extract Artifacts from the given deploy details
     */
    private List<Artifact> calculateArtifacts(Iterable<GradleDeployDetails> deployDetails) {
        return StreamSupport.stream(deployDetails.spliterator(), false).map(from -> {
            PublishArtifactInfo publishArtifact = from.getPublishArtifact();
            DeployDetails artifactDeployDetails = from.getDeployDetails();
            String artifactPath = artifactDeployDetails.getArtifactPath();
            return new ArtifactBuilder(artifactPath.substring(artifactPath.lastIndexOf('/') + 1))
                    .type(getTypeString(publishArtifact.getType(),
                            publishArtifact.getClassifier(), publishArtifact.getExtension()))
                    .md5(artifactDeployDetails.getMd5())
                    .sha1(artifactDeployDetails.getSha1())
                    .sha256(artifactDeployDetails.getSha256())
                    .remotePath(artifactPath).build();
        }).collect(Collectors.toList());
    }
}
