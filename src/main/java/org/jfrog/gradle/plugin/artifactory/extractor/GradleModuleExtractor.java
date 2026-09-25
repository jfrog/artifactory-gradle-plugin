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
import org.jfrog.gradle.plugin.artifactory.utils.ClientConfigHelper;
import org.jfrog.gradle.plugin.artifactory.utils.ProjectUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
                                List<PreCollectedDependency> preCollectedDependencies) {
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
        return getModuleBuilder(projectPath, moduleId, moduleType, gradleDeployDetails, configSnapshot, preCollectedDependencies).build();
    }

    /**
     * Create a ModuleBuilder ready to be built for the given project and deployment details.
     *
     * @param projectPath         - project path
     * @param moduleId            - module ID (group:name:version)
     * @param moduleType          - module type
     * @param gradleDeployDetails - module deployment details
     * @param configSnapshot      - client configuration snapshot
     * @param preCollectedDependencies - pre-collected dependency data
     */
    private ModuleBuilder getModuleBuilder(String projectPath, String moduleId, ModuleType moduleType,
                                           Set<GradleDeployDetails> gradleDeployDetails,
                                           Map<String, String> configSnapshot,
                                           List<PreCollectedDependency> preCollectedDependencies) {
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
            builder.dependencies(buildDependencies(preCollectedDependencies));

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
     * Build dependency list from pre-collected records. The same dependency may appear across several
     * configurations; entries are merged by id with scopes accumulated. The requestedBy path travels
     * with each record (computed at capture time by DependencyExtractor).
     */
    private List<Dependency> buildDependencies(List<PreCollectedDependency> preCollectedDependencies) {
        if (preCollectedDependencies == null || preCollectedDependencies.isEmpty()) {
            return new ArrayList<>();
        }
        // Use a map to merge scopes across configurations in O(N) instead of O(N²).
        Map<String, Dependency> byId = new HashMap<>();
        for (PreCollectedDependency dep : preCollectedDependencies) {
            Dependency existing = byId.get(dep.getId());
            if (existing != null) {
                existing.getScopes().addAll(dep.getScopes());
                continue;
            }
            DependencyBuilder depBuilder = new DependencyBuilder()
                    .id(dep.getId())
                    .type(dep.getType())
                    .scopes(dep.getScopes())
                    .md5(dep.getMd5())
                    .sha1(dep.getSha1())
                    .sha256(dep.getSha256());
            if (dep.getRequestedBy() != null) {
                depBuilder.requestedBy(dep.getRequestedBy());
            }
            byId.put(dep.getId(), depBuilder.build());
        }
        return new ArrayList<>(byId.values());
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
