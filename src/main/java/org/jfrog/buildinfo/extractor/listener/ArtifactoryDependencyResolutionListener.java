package org.jfrog.buildinfo.extractor.listener;

import org.gradle.api.artifacts.DependencyResolutionListener;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.ResolvableDependencies;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.jfrog.buildinfo.utils.ProjectUtils;

import java.util.*;

/**
 * Represents a DependencyResolutionListener, used to populate a dependency hierarchy map for each dependency in each module,
 * which is used in the 'requestedBy' field of every dependency in the build info, by listening to the 'afterResolve' event of every module.
 */
public class ArtifactoryDependencyResolutionListener implements DependencyResolutionListener {
    private static final Logger log = Logging.getLogger(ArtifactoryDependencyResolutionListener.class);
    private final Map<String, Map<String, String[][]>> modulesHierarchyMap = new HashMap<>();

    @Override
    public void beforeResolve(ResolvableDependencies dependencies) {}

    @Override
    public void afterResolve(ResolvableDependencies dependencies) {
        if (!dependencies.getResolutionResult().getAllDependencies().isEmpty()) {
            updateModulesMap(dependencies);
        }
        log.info("<ASSAF> Done update after dependency resolved");
    }

    /**
     * Get the Group, artifact and version of the given module
     * @param module - the module to extract the GAV info
     * @return Gav identifier string of the module or null if module is null
     */
    private static String getGav(ModuleVersionIdentifier module) {
        if (module == null) {
            return null;
        }
        return ProjectUtils.getAsGavString(module.getGroup(), module.getName(), module.getVersion());
    }

    /**
     * Update the modules map with the resolved dependencies.
     * @param dependencies - resolved dependencies to update the map
     */
    private void updateModulesMap(ResolvableDependencies dependencies) {
        String moduleId = getGav(dependencies.getResolutionResult().getRoot().getModuleVersion());
        if (moduleId == null) {
            return;
        }
        log.info("<ASSAF> updating module: " + moduleId);
        Map<String, String[][]> dependenciesMap = modulesHierarchyMap.computeIfAbsent(moduleId, k -> new HashMap<>());
        updateDependencyMap(dependenciesMap, dependencies.getResolutionResult().getAllDependencies());
    }

    /**
     * Update the resolved dependency map with the given information.
     * @param dependencyMap - the map that holds the modules resolved dependencies information
     * @param dependencies - the resolved dependencies to update the map
     */
    private void updateDependencyMap(Map<String, String[][]> dependencyMap, Set<? extends DependencyResult> dependencies) {
        for (DependencyResult dependency : dependencies) {
            if (!(dependency instanceof ResolvedDependencyResult)) {
                continue;
            }
            ResolvedDependencyResult resolvedDependency = (ResolvedDependencyResult) dependency;
            String componentId = getGav(resolvedDependency.getSelected().getModuleVersion());
            log.info("<ASSAF> dependency: " + componentId + " | already collected: " + (dependencyMap.get(componentId) != null));
            if (dependencyMap.containsKey(componentId)) {
                // Only add information that was not collected yet for the given module.
                continue;
            }
            dependencyMap.put(componentId, getDependencyDependents(resolvedDependency));
        }
    }

    /**
     * Get pathToRoot list of transitive dependencies. Root is expected to be last in list.
     * @param resolvedDependency - the dependency to resolve from.
     * @return Dependents list of a given dependency
     */
    private String[][] getDependencyDependents(ResolvedDependencyResult resolvedDependency) {
        List<String> dependents = new ArrayList<>();
        populateDependents(resolvedDependency, dependents);
        log.info("<ASSAF> Dependents from resolved:\n" + dependents);
        return new String[][] { dependents.toArray(new String[0])};
    }

    /**
     * Recursively populate a pathToRoot list of transitive dependencies. Root is expected to be last in list.
     * @param dependency - To populate the dependents list for.
     * @param dependents - Dependents list to populate.
     */
    private void populateDependents(ResolvedDependencyResult dependency, List<String> dependents) {
        ResolvedComponentResult from = dependency.getFrom();
        if (from.getDependents().isEmpty()) {
            if (from.getSelectionReason().isExpected()) {
                // If the dependency was requested by root, append the root's GAV.
                dependents.add(getGav(from.getModuleVersion()));
                return;
            }
            // Unexpected result.
            throw new RuntimeException("Failed populating dependency parents map: dependency has no dependents and is not root.");
        }
        // We assume the first parent in the list, is the item that triggered this dependency resolution.
        ResolvedDependencyResult parent = from.getDependents().iterator().next();
        String parentGav = getGav(parent.getSelected().getModuleVersion());
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
