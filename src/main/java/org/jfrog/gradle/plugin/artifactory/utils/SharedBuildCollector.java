package org.jfrog.gradle.plugin.artifactory.utils;

import org.gradle.api.Project;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.jfrog.build.extractor.ci.Dependency;
import org.jfrog.gradle.plugin.artifactory.extractor.SubprocessModuleInfoFileProducer;
import org.jfrog.gradle.plugin.artifactory.task.DeployTask;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Collects only shared builds Gradle already used: resolved project components
 * or applied plugins whose code lives under the include. Does not run assemble.
 * Nested includeBuilds are kept only when the parent declared them or the consumer
 * already resolved them ({@link UsedSharedBuilds#isNestedIncludeUsed}).
 */
public final class SharedBuildCollector {
    private static final Logger log = Logging.getLogger(SharedBuildCollector.class);

    private SharedBuildCollector() {
    }

    public static void collectUsed(Project rootProject, DeployTask deployTask) {
        Set<File> registered = new HashSet<File>();
        String inheritedGroup = rootProject.getGroup() != null ? rootProject.getGroup().toString() : "";
        String consumerModuleId = ProjectUtils.getId(rootProject);
        Set<String> resolvedNames = UsedSharedBuilds.resolvedIncludedBuildNames(rootProject);

        File buildSrcDir = new File(rootProject.getProjectDir(), "buildSrc");
        if (buildSrcDir.isDirectory()) {
            register(rootProject, deployTask, buildSrcDir, "buildSrc", consumerModuleId, inheritedGroup, registered, resolvedNames);
        }

        // Root-level: only includes Gradle already used (resolution or applied plugin).
        for (File usedInclude : UsedSharedBuilds.findUsedIncludeDirs(rootProject)) {
            register(rootProject, deployTask, usedInclude, usedInclude.getName(), consumerModuleId, inheritedGroup, registered, resolvedNames);
        }
    }

    private static void register(Project rootProject, DeployTask deployTask, File sharedBuildDir, String defaultName,
                                 String parentModuleId, String inheritedGroup, Set<File> registered, Set<String> resolvedNames) {
        File canonical;
        try {
            canonical = sharedBuildDir.getCanonicalFile();
        } catch (Exception e) {
            log.debug("Could not resolve shared build path '{}': {}", sharedBuildDir, e.getMessage());
            return;
        }
        if (!canonical.isDirectory() || !registered.add(canonical)) {
            return;
        }

        String moduleName = SharedBuildLogicUtils.readRootProjectName(canonical, defaultName);
        String[] resolved = UsedSharedBuilds.resolvedCoordinatesForInclude(rootProject, moduleName);
        String[] groupAndVersion = resolved != null
                ? resolved
                : SharedBuildLogicUtils.readGroupAndVersionFromBuildScript(canonical);
        String effectiveGroup = SharedBuildLogicUtils.resolveEffectiveGroup(groupAndVersion[0], inheritedGroup);
        String publishedVersion = SharedBuildLogicUtils.resolvePublishedVersion(
                groupAndVersion[1], moduleName, canonical);
        String moduleId = SharedBuildLogicUtils.buildQualifiedModuleId(
                parentModuleId, effectiveGroup, moduleName, publishedVersion);
        String artifactModuleId = SharedBuildLogicUtils.deployCoordinates(
                moduleId, effectiveGroup, moduleName, publishedVersion);

        List<Dependency> dependencies = SharedBuildDependencies.collect(
                rootProject, canonical, effectiveGroup, moduleName, publishedVersion);
        deployTask.registerModuleInfoProducer(
                new SubprocessModuleInfoFileProducer(rootProject, moduleId, dependencies,
                        canonical, artifactModuleId));

        File buildSrcDir = new File(canonical, "buildSrc");
        if (buildSrcDir.isDirectory()) {
            register(rootProject, deployTask, buildSrcDir, "buildSrc", moduleId, effectiveGroup, registered, resolvedNames);
        }
        // Nested includeBuilds: only if declared by parent or already resolved by consumer.
        for (File childInclude : SettingsGradleParser.listIncludeBuildDirs(canonical)) {
            if (UsedSharedBuilds.isNestedIncludeUsed(canonical, childInclude, resolvedNames)) {
                register(rootProject, deployTask, childInclude, childInclude.getName(), moduleId, effectiveGroup, registered, resolvedNames);
            }
        }
    }
}
