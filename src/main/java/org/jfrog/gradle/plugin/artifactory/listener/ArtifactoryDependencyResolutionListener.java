package org.jfrog.gradle.plugin.artifactory.listener;

import org.apache.commons.lang3.StringUtils;
import org.gradle.api.artifacts.ResolvableDependencies;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.jfrog.build.api.util.FileChecksumCalculator;
import org.jfrog.gradle.plugin.artifactory.task.ExtractModuleTask;
import org.jfrog.gradle.plugin.artifactory.utils.ProjectUtils;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Populate a dependency hierarchy map for each dependency in each module,
 * which is used in the 'requestedBy' field of every dependency in the build info, by listening to the 'afterResolve' event of every module.
 * Also collects full dependency data (id, type, checksums, scopes) for module extraction.
 *
 * Data is stored in static maps so it is accessible from any BuildService instance or task,
 * even when Configuration Cache creates separate deserialized instances.
 */
public class ArtifactoryDependencyResolutionListener {
    private static final Map<String, Map<String, String[][]>> modulesHierarchyMap = new ConcurrentHashMap<>();
    private static final Map<String, List<ExtractModuleTask.PreCollectedDependency>> modulesDependenciesMap = new ConcurrentHashMap<>();

    /**
     * Clear static state at the start of a new build (called from plugin apply).
     */
    public static void resetState() {
        modulesHierarchyMap.clear();
        modulesDependenciesMap.clear();
    }

    public static Map<String, Map<String, String[][]>> getModulesHierarchyMap() {
        return modulesHierarchyMap;
    }

    public static Map<String, List<ExtractModuleTask.PreCollectedDependency>> getModulesDependenciesMap() {
        return modulesDependenciesMap;
    }

    public void afterResolve(ResolvableDependencies dependencies) {
        updateModulesMap(dependencies);
        collectDependencyDetails(dependencies);
    }

    /**
     * Update the modules map with the resolved dependencies.
     *
     * @param dependencies - resolved dependencies to update the map
     */
    private void updateModulesMap(ResolvableDependencies dependencies) {
        if (dependencies.getResolutionResult().getAllDependencies().isEmpty()) {
            return;
        }
        String moduleId = ProjectUtils.getId(dependencies.getResolutionResult().getRoot().getModuleVersion());
        if (moduleId == null) {
            return;
        }
        Map<String, String[][]> dependenciesMap = modulesHierarchyMap.computeIfAbsent(moduleId, k -> new HashMap<>());
        updateDependencyMap(dependenciesMap, dependencies.getResolutionResult().getAllDependencies());
    }

    /**
     * Collect full dependency details (id, type, checksums, scopes) for module extraction.
     * Collects from both artifact views (for external deps with files) and resolution results (for project deps).
     */
    private void collectDependencyDetails(ResolvableDependencies dependencies) {
        String moduleId = ProjectUtils.getId(dependencies.getResolutionResult().getRoot().getModuleVersion());
        if (moduleId == null) {
            return;
        }
        List<ExtractModuleTask.PreCollectedDependency> depsList =
                modulesDependenciesMap.computeIfAbsent(moduleId, k -> new ArrayList<>());

        String configName = dependencies.getName();
        Set<? extends DependencyResult> dependencyResults = dependencies.getResolutionResult().getAllDependencies();

        // Collect from artifact view (external dependencies with files and checksums)
        for (ResolvedArtifactResult artifact : dependencies.artifactView(view -> view.setLenient(true)).getArtifacts()) {
            extractAndStoreDependencyFromArtifact(moduleId, configName, artifact, dependencyResults, depsList);
        }

        // Also collect inter-project dependencies from resolution result — they may not appear in artifact view
        // (e.g., project deps without output artifacts). Skip self-references (root module appearing as its own dep).
        for (DependencyResult dep : dependencyResults) {
            if (!(dep instanceof ResolvedDependencyResult)) {
                continue;
            }
            ResolvedDependencyResult resolved = (ResolvedDependencyResult) dep;
            if (!(resolved.getSelected().getId() instanceof ProjectComponentIdentifier)) {
                continue;
            }
            String depId = ProjectUtils.getId(resolved.getSelected().getModuleVersion());
            if (depId == null || depId.equals(moduleId)) {
                continue;
            }
            addOrMergeScope(depsList, depId, configName, "", null, null, null);
        }
    }

    private void extractAndStoreDependencyFromArtifact(String moduleId, String configName, ResolvedArtifactResult artifact,
                                            Set<? extends DependencyResult> dependencyResults,
                                            List<ExtractModuleTask.PreCollectedDependency> depsList) {
        File file = artifact.getFile();
        if (!file.exists()) {
            return;
        }
        String depId = extractDependencyId(artifact, dependencyResults);
        if (depId == null || depId.equals(moduleId)) {
            return;
        }

        String type = StringUtils.substringAfterLast(file.getName(), ".");
        String md5 = null, sha1 = null, sha256 = null;
        if (file.isFile()) {
            try {
                Map<String, String> checksums = FileChecksumCalculator.calculateChecksums(file,
                        FileChecksumCalculator.MD5_ALGORITHM,
                        FileChecksumCalculator.SHA1_ALGORITHM,
                        FileChecksumCalculator.SHA256_ALGORITHM);
                md5 = checksums.get(FileChecksumCalculator.MD5_ALGORITHM);
                sha1 = checksums.get(FileChecksumCalculator.SHA1_ALGORITHM);
                sha256 = checksums.get(FileChecksumCalculator.SHA256_ALGORITHM);
            } catch (Exception e) {
                throw new RuntimeException("Failed to calculate checksums for: " + file.getAbsolutePath(), e);
            }
        }
        addOrMergeScope(depsList, depId, configName, type, md5, sha1, sha256);
    }

    /**
     * Add a dependency to the list, or merge the scope if it already exists.
     */
    private void addOrMergeScope(List<ExtractModuleTask.PreCollectedDependency> depsList,
                                  String depId, String configName, String type,
                                  String md5, String sha1, String sha256) {
        for (ExtractModuleTask.PreCollectedDependency existing : depsList) {
            if (existing.getId().equals(depId)) {
                existing.getScopes().add(configName);
                return;
            }
        }
        Set<String> scopes = new HashSet<>();
        scopes.add(configName);
        depsList.add(new ExtractModuleTask.PreCollectedDependency(depId, type, scopes, md5, sha1, sha256, null));
    }

    private String extractDependencyId(ResolvedArtifactResult artifact,
                                        Set<? extends DependencyResult> dependencyResults) {
        ComponentIdentifier identifier = artifact.getId().getComponentIdentifier();
        if (!(identifier instanceof ProjectComponentIdentifier)) {
            return identifier.getDisplayName();
        }
        ResolvedComponentResult resolvedResult = null;
        for (DependencyResult dep : dependencyResults) {
            if (dep instanceof ResolvedDependencyResult) {
                ResolvedDependencyResult resolved = (ResolvedDependencyResult) dep;
                if (resolved.getSelected().getId().equals(identifier)) {
                    resolvedResult = resolved.getSelected();
                    break;
                }
            }
        }
        if (resolvedResult == null) {
            return null;
        }
        return ProjectUtils.getId(resolvedResult.getModuleVersion());
    }

    /**
     * Update the resolved dependency map with the given information.
     *
     * @param dependencyMap - the map that holds the modules resolved dependencies information
     * @param dependencies  - the resolved dependencies to update the map
     */
    private void updateDependencyMap(Map<String, String[][]> dependencyMap, Set<? extends DependencyResult> dependencies) {
        for (DependencyResult dependency : dependencies) {
            if (!(dependency instanceof ResolvedDependencyResult)) {
                continue;
            }
            ResolvedDependencyResult resolvedDependency = (ResolvedDependencyResult) dependency;
            String componentId = ProjectUtils.getId(resolvedDependency.getSelected().getModuleVersion());
            if (dependencyMap.containsKey(componentId)) {
                // Only add information that was not collected yet for the given module.
                continue;
            }
            dependencyMap.put(componentId, getDependencyDependents(resolvedDependency));
        }
    }

    /**
     * Get pathToRoot list of transitive dependencies. Root is expected to be last in list.
     *
     * @param resolvedDependency - the dependency to resolve from.
     * @return Dependents list of a given dependency
     */
    private String[][] getDependencyDependents(ResolvedDependencyResult resolvedDependency) {
        List<String> dependents = new ArrayList<>();
        populateDependents(resolvedDependency, dependents);
        return new String[][]{dependents.toArray(new String[0])};
    }

    /**
     * Recursively populate a pathToRoot list of transitive dependencies. Root is expected to be last in list.
     *
     * @param dependency - To populate the dependents list for.
     * @param dependents - Dependents list to populate.
     */
    private void populateDependents(ResolvedDependencyResult dependency, List<String> dependents) {
        ResolvedComponentResult from = dependency.getFrom();
        if (from.getDependents().isEmpty()) {
            if (from.getSelectionReason().isExpected()) {
                // If the dependency was requested by root, append the root's GAV.
                dependents.add(ProjectUtils.getId(from.getModuleVersion()));
                return;
            }
            // Unexpected result.
            throw new RuntimeException("Failed populating dependency parents map: dependency has no dependents and is not root.");
        }
        // We assume the first parent in the list, is the item that triggered this dependency resolution.
        ResolvedDependencyResult parent = from.getDependents().iterator().next();
        String parentGav = ProjectUtils.getId(parent.getSelected().getModuleVersion());
        // Check for circular dependencies loop. We do this check to avoid an infinite loop dependencies. For example: A --> B --> C --> A...
        if (dependents.contains(parentGav)) {
            return;
        }
        // Append the current parent's GAV to list.
        dependents.add(parentGav);
        // Continue populating dependents list.
        populateDependents(parent, dependents);
    }
}
