package org.jfrog.gradle.plugin.artifactory.extractor;

import org.apache.commons.lang3.StringUtils;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.jfrog.build.api.util.FileChecksumCalculator;
import org.jfrog.gradle.plugin.artifactory.extractor.PreCollectedDependency;
import org.jfrog.gradle.plugin.artifactory.utils.ProjectUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extracts a module's dependency records (id, type, checksums, scopes and requestedBy paths) from
 * a resolved configuration's {@link ResolvedComponentResult} graph and its {@link ResolvedArtifactResult}s.
 * <p>
 * This is the configuration-cache-compatible replacement for the old {@code afterResolve} listener:
 * it runs inside a lazy provider transform (execution time), reading from the resolution result the
 * configuration cache serializes and reloads on a cache hit. No configuration-time file observation,
 * no build listener that dies when the configuration phase is skipped.
 */
public class DependencyExtractor {

    /**
     * Extract dependency records for a single resolved configuration.
     *
     * @param configName - the configuration name, used as the dependency scope
     * @param root       - the resolved root component of the configuration
     * @param artifacts  - the resolved artifacts of the configuration (lenient view)
     * @return dependency records for this configuration
     */
    public static List<PreCollectedDependency> extract(String configName,
                                                       ResolvedComponentResult root,
                                                       Set<ResolvedArtifactResult> artifacts) {
        String moduleId = ProjectUtils.getId(root.getModuleVersion());

        // Collect every resolved dependency edge reachable from the root (equivalent to getAllDependencies()).
        Set<ResolvedDependencyResult> allDeps = new LinkedHashSet<>();
        collectAllDependencies(root, new HashSet<>(), allDeps);

        // Build the requestedBy (path-to-root) map, keyed by the selected component's GAV.
        Map<String, String[][]> requestedByMap = new HashMap<>();
        for (ResolvedDependencyResult dep : allDeps) {
            String depId = ProjectUtils.getId(dep.getSelected().getModuleVersion());
            if (depId == null || requestedByMap.containsKey(depId)) {
                continue;
            }
            requestedByMap.put(depId, getDependencyDependents(dep));
        }

        // Pre-build a lookup map from ComponentIdentifier to GAV string to avoid O(artifacts * deps)
        // linear scan when resolving project-component artifact identifiers.
        Map<ComponentIdentifier, String> idToGav = new HashMap<>();
        for (ResolvedDependencyResult dep : allDeps) {
            idToGav.put(dep.getSelected().getId(), ProjectUtils.getId(dep.getSelected().getModuleVersion()));
        }

        // Use an insertion-ordered map keyed by dep id to collect artifact-backed entries.
        // This makes the later project-dep dedup a single O(1) containsKey check.
        Map<String, PreCollectedDependency> resultMap = new LinkedHashMap<>();

        // Dependencies backed by an artifact file (external deps and project deps that produce artifacts).
        for (ResolvedArtifactResult artifact : artifacts) {
            File file = artifact.getFile();
            if (!file.exists()) {
                continue;
            }
            String depId = extractDependencyId(artifact, idToGav);
            if (depId == null || depId.equals(moduleId)) {
                continue;
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
            Set<String> scopes = new HashSet<>();
            scopes.add(configName);
            resultMap.put(depId, new PreCollectedDependency(depId, type, scopes, md5, sha1, sha256, requestedByMap.get(depId)));
        }

        // Inter-project dependencies that resolve without an output artifact - they do not appear in the
        // artifact view but must still be reported. The resultMap containsKey check is O(1).
        for (ResolvedDependencyResult dep : allDeps) {
            if (!(dep.getSelected().getId() instanceof ProjectComponentIdentifier)) {
                continue;
            }
            String depId = ProjectUtils.getId(dep.getSelected().getModuleVersion());
            if (depId == null || depId.equals(moduleId) || resultMap.containsKey(depId)) {
                continue;
            }
            Set<String> scopes = new HashSet<>();
            scopes.add(configName);
            resultMap.put(depId, new PreCollectedDependency(depId, "", scopes, null, null, null, requestedByMap.get(depId)));
        }

        return new ArrayList<>(resultMap.values());
    }

    private static void collectAllDependencies(ResolvedComponentResult component,
                                               Set<ResolvedComponentResult> visited,
                                               Set<ResolvedDependencyResult> out) {
        if (!visited.add(component)) {
            return;
        }
        for (DependencyResult dependencyResult : component.getDependencies()) {
            if (dependencyResult instanceof ResolvedDependencyResult) {
                ResolvedDependencyResult resolved = (ResolvedDependencyResult) dependencyResult;
                out.add(resolved);
                collectAllDependencies(resolved.getSelected(), visited, out);
            }
        }
    }

    private static String[][] getDependencyDependents(ResolvedDependencyResult resolvedDependency) {
        List<String> dependents = new ArrayList<>();
        populateDependents(resolvedDependency, dependents);
        return new String[][]{dependents.toArray(new String[0])};
    }

    private static void populateDependents(ResolvedDependencyResult dependency, List<String> dependents) {
        ResolvedComponentResult from = dependency.getFrom();
        if (from.getDependents().isEmpty()) {
            if (from.getSelectionReason().isExpected()) {
                dependents.add(ProjectUtils.getId(from.getModuleVersion()));
                return;
            }
            throw new RuntimeException("Failed populating dependency parents map: dependency has no dependents and is not root.");
        }
        ResolvedDependencyResult parent = from.getDependents().iterator().next();
        String parentGav = ProjectUtils.getId(parent.getSelected().getModuleVersion());
        if (dependents.contains(parentGav)) {
            return;
        }
        dependents.add(parentGav);
        populateDependents(parent, dependents);
    }

    private static String extractDependencyId(ResolvedArtifactResult artifact, Map<ComponentIdentifier, String> idToGav) {
        ComponentIdentifier identifier = artifact.getId().getComponentIdentifier();
        if (!(identifier instanceof ProjectComponentIdentifier)) {
            return identifier.getDisplayName();
        }
        return idToGav.get(identifier);
    }
}
