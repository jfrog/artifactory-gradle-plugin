package org.jfrog.buildinfo.extractor;

import com.google.common.collect.Sets;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.jfrog.build.api.builder.ModuleType;
import org.jfrog.build.api.util.FileChecksumCalculator;
import org.jfrog.build.extractor.ModuleExtractor;
import org.jfrog.build.extractor.builder.ArtifactBuilder;
import org.jfrog.build.extractor.builder.DependencyBuilder;
import org.jfrog.build.extractor.builder.ModuleBuilder;
import org.jfrog.build.extractor.ci.Artifact;
import org.jfrog.build.extractor.ci.Dependency;
import org.jfrog.build.extractor.ci.Module;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.build.extractor.clientConfiguration.deploy.DeployDetails;
import org.jfrog.buildinfo.ArtifactoryPlugin;
import org.jfrog.buildinfo.extractor.details.GradleDeployDetails;
import org.jfrog.buildinfo.extractor.details.PublishArtifactInfo;
import org.jfrog.buildinfo.listener.ArtifactoryDependencyResolutionListener;
import org.jfrog.buildinfo.tasks.CollectDeployDetailsTask;
import org.jfrog.buildinfo.utils.ConventionUtils;
import org.jfrog.buildinfo.utils.ProjectUtils;
import org.jfrog.buildinfo.utils.TaskUtils;

import java.io.File;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static com.google.common.collect.Lists.newArrayList;
import static org.jfrog.build.api.util.FileChecksumCalculator.*;
import static org.jfrog.build.extractor.BuildInfoExtractorUtils.getTypeString;

public class GradleModuleExtractor implements ModuleExtractor<Project> {
    private static final Logger log = Logging.getLogger(GradleModuleExtractor.class);

    @Override
    public Module extractModule(Project project) {
        Set<GradleDeployDetails> gradleDeployDetails = getCollectedDeployDetails(project);
        return getModuleBuilder(project, gradleDeployDetails).build();
    }

    private Set<GradleDeployDetails> getCollectedDeployDetails(Project project) {
        CollectDeployDetailsTask detailsCollectionTask = TaskUtils.findExecutedCollectionTask(project);
        if (detailsCollectionTask == null) {
            return Sets.newHashSet();
        }
        return detailsCollectionTask.getDeployDetails();
    }

    private ModuleBuilder getModuleBuilder(Project project, Set<GradleDeployDetails> gradleDeployDetails) {
        String moduleId = ProjectUtils.getId(project);
        String repo = gradleDeployDetails.stream()
                .map(GradleDeployDetails::getDeployDetails)
                .map(DeployDetails::getTargetRepository)
                .findAny()
                .orElse("");
        ModuleBuilder builder = new ModuleBuilder()
                .type(ModuleType.GRADLE)
                .id(moduleId)
                .repository(repo);
        try {
            // Extract the module's dependencies
            builder.dependencies(calculateDependencies(project, moduleId));
            // Extract the module's artifacts
            ArtifactoryClientConfiguration.PublisherHandler publisher = ConventionUtils.getPublisherHandler(project);
            if (publisher == null) {
                log.warn("No publisher config found for project: " + project.getName());
                return builder;
            }
            builder.excludedArtifacts(calculateArtifacts(ProjectUtils.filterIncludeExcludeDetails(project, publisher, gradleDeployDetails, false)));
            builder.artifacts(calculateArtifacts(ProjectUtils.filterIncludeExcludeDetails(project, publisher, gradleDeployDetails, true)));
        } catch (Exception e) {
            log.error("Error occur during extraction: ", e);
        }
        return builder;
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

    /**
     * Extract a given project dependencies from the information collected by the resolutionListener
     */
    private List<Dependency> calculateDependencies(Project project, String moduleId) throws Exception {
        ArtifactoryDependencyResolutionListener artifactoryDependencyResolutionListener =
                project.getRootProject().getPlugins().getPlugin(ArtifactoryPlugin.class).getResolutionListener();
        Map<String, String[][]> requestedByMap = artifactoryDependencyResolutionListener.getModulesHierarchyMap().get(moduleId);

        Set<Configuration> configurationSet = project.getConfigurations();
        List<Dependency> dependencies = newArrayList();
        for (Configuration configuration : configurationSet) {
            if (configuration.getState() != Configuration.State.RESOLVED) {
                log.info("Artifacts for configuration '{}' were not all resolved, skipping", configuration.getName());
                continue;
            }
            for (final ResolvedArtifact artifact : configuration.getResolvedConfiguration().getResolvedArtifacts()) {
                Dependency extractedDependency = extractDependencyFromResolvedArtifact(configuration, artifact, requestedByMap, dependencies);
                if (extractedDependency == null) {
                    continue;
                }
                dependencies.add(extractedDependency);
            }
        }
        return dependencies;
    }

    private Dependency extractDependencyFromResolvedArtifact(Configuration configuration, final ResolvedArtifact artifact, Map<String, String[][]> requestedByMap, List<Dependency> dependencies) throws NoSuchAlgorithmException, IOException {
        File file = artifact.getFile();
        if (!file.exists()) {
            return null;
        }
        final String depId = ProjectUtils.getId(artifact.getModuleVersion().getId());
        Dependency existingDependency = dependencies.stream()
                .filter(input -> input.getId().equals(depId)).findAny().orElse(null);
        if (existingDependency != null) {
            // Already extracted, update the dependency with the artifact info
            Set<String> existingScopes = existingDependency.getScopes();
            existingScopes.add(configuration.getName());
            existingDependency.setScopes(existingScopes);
            return null;
        }
        // New dependency to extract
        DependencyBuilder dependencyBuilder = new DependencyBuilder()
                .type(getTypeString(artifact.getType(),
                        artifact.getClassifier(), artifact.getExtension()))
                .id(depId)
                .scopes(Sets.newHashSet(configuration.getName()));
        if (requestedByMap != null) {
            dependencyBuilder.requestedBy(requestedByMap.get(depId));
        }
        if (file.isFile()) {
            // In recent gradle builds (3.4+) subproject dependencies are represented by a dir not jar.
            Map<String, String> checksums = FileChecksumCalculator.calculateChecksums(file, MD5_ALGORITHM, SHA1_ALGORITHM, SHA256_ALGORITHM);
            dependencyBuilder.md5(checksums.get(MD5_ALGORITHM)).sha1(checksums.get(SHA1_ALGORITHM)).sha256(checksums.get(SHA256_ALGORITHM));
        }
        return dependencyBuilder.build();
    }
}
