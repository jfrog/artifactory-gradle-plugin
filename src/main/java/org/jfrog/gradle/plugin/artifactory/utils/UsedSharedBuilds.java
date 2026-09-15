package org.jfrog.gradle.plugin.artifactory.utils;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.initialization.IncludedBuild;
import org.gradle.api.plugins.PluginContainer;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared builds the consumer actually used: a resolved composite project,
 * or an applied plugin whose code lives under that include folder.
 */
public final class UsedSharedBuilds {
    private UsedSharedBuilds() {
    }

    public static List<File> findUsedIncludeDirs(Project root) {
        List<File> used = new ArrayList<File>();
        if (root == null) {
            return used;
        }
        Set<String> pluginSources = collectPluginCodeSources(root);
        Set<String> resolvedBuildNames = collectResolvedIncludedBuildNames(root);
        for (IncludedBuild included : root.getGradle().getIncludedBuilds()) {
            if (isUsed(included.getProjectDir(), included.getName(), pluginSources, resolvedBuildNames)) {
                used.add(included.getProjectDir());
            }
        }
        return used;
    }

    /**
     * GAV Gradle already resolved for that included build, or null if it was not a project dependency.
     */
    public static String[] resolvedCoordinatesForInclude(Project root, String includeName) {
        if (root == null || includeName == null) {
            return null;
        }
        for (Project project : root.getAllprojects()) {
            for (Configuration configuration : project.getConfigurations()) {
                if (!configuration.isCanBeResolved()) {
                    continue;
                }
                try {
                    for (ResolvedComponentResult component : configuration.getIncoming().getResolutionResult().getAllComponents()) {
                        if (!(component.getId() instanceof ProjectComponentIdentifier)) {
                            continue;
                        }
                        ProjectComponentIdentifier projectId = (ProjectComponentIdentifier) component.getId();
                        if (!includeName.equals(includedBuildName(projectId))) {
                            continue;
                        }
                        ModuleVersionIdentifier module = component.getModuleVersion();
                        if (module != null) {
                            return new String[]{module.getGroup(), module.getVersion()};
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    /**
     * Nested includeBuild is used when the parent declares it or the consumer already resolved it.
     * A leftover jar in build/libs is not enough.
     */
    public static boolean isNestedIncludeUsed(File parentDir, File childDir, Collection<String> resolvedBuildNames) {
        if (childDir == null) {
            return false;
        }
        String childName = SharedBuildLogicUtils.readRootProjectName(childDir, childDir.getName());
        if (childName != null && resolvedBuildNames != null && resolvedBuildNames.contains(childName)) {
            return true;
        }
        for (String gav : SharedBuildDependencies.parseDeclaredDependencies(
                SharedBuildLogicUtils.readBuildScript(parentDir))) {
            String[] parts = gav.split(":");
            if (parts.length >= 2 && childName != null && childName.equals(parts[1])) {
                return true;
            }
        }
        return false;
    }

    public static Set<String> resolvedIncludedBuildNames(Project root) {
        return collectResolvedIncludedBuildNames(root);
    }

    static boolean isUsed(File includeDir, String includeName,
                          Collection<String> pluginCodeSources,
                          Collection<String> resolvedIncludedBuildNames) {
        if (includeName != null && resolvedIncludedBuildNames != null && resolvedIncludedBuildNames.contains(includeName)) {
            return true;
        }
        if (pluginCodeSources == null) {
            return false;
        }
        for (String source : pluginCodeSources) {
            if (codeSourceIsFromDir(source, includeDir)) {
                return true;
            }
        }
        return false;
    }

    static boolean codeSourceIsFromDir(String codeSource, File includeDir) {
        if (codeSource == null || includeDir == null) {
            return false;
        }
        String includePath = canonicalPath(includeDir);
        String sourcePath = pathFromCodeSource(codeSource);
        return sourcePath != null && includePath != null
                && (sourcePath.equals(includePath) || sourcePath.startsWith(includePath + File.separator));
    }

    private static Set<String> collectPluginCodeSources(Project root) {
        Set<String> sources = new LinkedHashSet<String>();
        for (Project project : root.getAllprojects()) {
            PluginContainer plugins = project.getPlugins();
            for (Object plugin : plugins) {
                if (plugin == null) {
                    continue;
                }
                CodeSource codeSource = plugin.getClass().getProtectionDomain().getCodeSource();
                if (codeSource == null) {
                    continue;
                }
                URL location = codeSource.getLocation();
                if (location != null) {
                    sources.add(location.toString());
                }
            }
        }
        return sources;
    }

    private static Set<String> collectResolvedIncludedBuildNames(Project root) {
        Set<String> names = new LinkedHashSet<String>();
        for (Project project : root.getAllprojects()) {
            for (Configuration configuration : project.getConfigurations()) {
                if (!configuration.isCanBeResolved()) {
                    continue;
                }
                try {
                    for (org.gradle.api.artifacts.result.ResolvedComponentResult component
                            : configuration.getIncoming().getResolutionResult().getAllComponents()) {
                        if (!(component.getId() instanceof ProjectComponentIdentifier)) {
                            continue;
                        }
                        ProjectComponentIdentifier projectId = (ProjectComponentIdentifier) component.getId();
                        String buildName = includedBuildName(projectId);
                        if (buildName != null && !buildName.isEmpty() && !":".equals(buildName)) {
                            names.add(buildName);
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return names;
    }

    /**
     * Gradle 9 dropped BuildIdentifier.getName(); prefer getBuildPath() and keep getName() for older Gradle.
     */
    static String includedBuildName(ProjectComponentIdentifier projectId) {
        if (projectId == null || projectId.getBuild() == null) {
            return null;
        }
        Object build = projectId.getBuild();
        try {
            Object path = build.getClass().getMethod("getBuildPath").invoke(build);
            if (path != null) {
                String buildPath = path.toString();
                if (buildPath.startsWith(":")) {
                    buildPath = buildPath.substring(1);
                }
                int last = buildPath.lastIndexOf(':');
                return last >= 0 ? buildPath.substring(last + 1) : buildPath;
            }
        } catch (Exception ignored) {
        }
        try {
            Object name = build.getClass().getMethod("getName").invoke(build);
            return name != null ? name.toString() : null;
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String pathFromCodeSource(String codeSource) {
        if (codeSource.startsWith("file:")) {
            try {
                return canonicalPath(new File(new URI(codeSource)));
            } catch (Exception ignored) {
                return codeSource;
            }
        }
        return codeSource;
    }

    private static String canonicalPath(File file) {
        try {
            return file.getCanonicalPath();
        } catch (Exception e) {
            return file.getAbsolutePath();
        }
    }
}
