package org.jfrog.gradle.plugin.artifactory.utils;

import org.gradle.api.Project;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.build.extractor.clientConfiguration.IncludeExcludePatterns;
import org.jfrog.build.extractor.clientConfiguration.PatternMatcher;
import org.jfrog.gradle.plugin.artifactory.extractor.GradleDeployDetails;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class ProjectUtils {

    public static boolean isRootProject(Project project) {
        return project.equals(project.getRootProject());
    }

    /**
     * Get the ID (Group, artifact and version) of the given module
     *
     * @param project - project to extract its Id
     * @return Gav identifier string of the module
     */
    public static String getId(Project project) {
        return getAsGavString(project.getGroup().toString(), project.getName(), project.getVersion().toString());
    }

    /**
     * Get the ID (Group, artifact and version) of the given module
     *
     * @param module - the module to extract the GAV info
     * @return Gav identifier string of the module or null if module is null
     */
    public static String getId(ModuleVersionIdentifier module) {
        if (module == null) {
            return null;
        }
        return getAsGavString(module.getGroup(), module.getName(), module.getVersion());
    }

    private static String getAsGavString(String group, String name, String version) {
        return group + ':' + name + ':' + version;
    }

    /**
     * Check if a given project has at least one of the given components
     *
     * @param project        - project to check
     * @param componentNames - components names to check
     * @return true if the given project has at least one of the given components.
     */
    public static boolean hasOneOfComponents(Project project, String... componentNames) {
        for (String componentName : componentNames) {
            if (project.getComponents().findByName(componentName) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Filter (Include/Exclude) project deployment details by a given publisher configurations
     *
     * @param project             - project of the given details
     * @param publisher           - configurations to apply
     * @param gradleDeployDetails - details to filter
     * @param isInclude           - if true applying include-pattern or exclude-pattern if false
     * @return filtered details by the given input
     */
    public static Iterable<GradleDeployDetails> filterIncludeExcludeDetails(Project project, ArtifactoryClientConfiguration.PublisherHandler publisher, Set<GradleDeployDetails> gradleDeployDetails, boolean isInclude) {
        IncludeExcludePatterns patterns = new IncludeExcludePatterns(
                publisher.getIncludePatterns(),
                publisher.getExcludePatterns());
        if (publisher.isFilterExcludedArtifactsFromBuild()) {
            return gradleDeployDetails.stream().filter(new IncludeExcludePredicate(project, patterns, isInclude)).collect(Collectors.toSet());
        }
        if (!isInclude) {
            return new ArrayList<>();
        }
        return gradleDeployDetails.stream().filter(new ProjectPredicate(project)).collect(Collectors.toSet());
    }

    public static class ProjectPredicate implements Predicate<GradleDeployDetails> {
        private final Project project;

        private ProjectPredicate(Project project) {
            this.project = project;
        }

        @Override
        public boolean test(@Nullable GradleDeployDetails input) {
            if (input == null) {
                return false;
            }
            return input.getProject().equals(project);
        }
    }

    public static class IncludeExcludePredicate implements Predicate<GradleDeployDetails> {
        private final Project project;
        private final IncludeExcludePatterns patterns;
        private final boolean include;

        public IncludeExcludePredicate(Project project, IncludeExcludePatterns patterns, boolean include) {
            this.project = project;
            this.patterns = patterns;
            this.include = include;
        }

        @Override
        public boolean test(@Nullable GradleDeployDetails input) {
            if (input == null || !Objects.equals(input.getProject(), project)) {
                return false;
            }
            if (include) {
                return !PatternMatcher.pathConflicts(input.getDeployDetails().getArtifactPath(), patterns);
            }
            return PatternMatcher.pathConflicts(input.getDeployDetails().getArtifactPath(), patterns);
        }
    }
}
