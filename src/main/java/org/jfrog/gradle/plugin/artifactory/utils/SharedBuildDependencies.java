package org.jfrog.gradle.plugin.artifactory.utils;

import org.apache.commons.lang3.StringUtils;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.initialization.IncludedBuild;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.jfrog.build.api.util.FileChecksumCalculator;
import org.jfrog.build.extractor.builder.DependencyBuilder;
import org.jfrog.build.extractor.ci.Dependency;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.jfrog.build.api.util.FileChecksumCalculator.MD5_ALGORITHM;
import static org.jfrog.build.api.util.FileChecksumCalculator.SHA1_ALGORITHM;
import static org.jfrog.build.api.util.FileChecksumCalculator.SHA256_ALGORITHM;

/**
 * Dependencies of a used shared build, including transitives, from the already-resolved
 * Gradle graph and cached POMs. Scopes use Gradle configuration names.
 */
public final class SharedBuildDependencies {
    private static final Logger log = Logging.getLogger(SharedBuildDependencies.class);
    private static final Pattern DECLARED_DEP = Pattern.compile(
            "(implementation|api|compileOnly|runtimeOnly|testImplementation|compile|testCompile)\\s*\\(?\\s*['\"]([^:'\"]+:[^:'\"]+:[^'\"]+)['\"]");
    private static final Pattern POM_DEPENDENCY = Pattern.compile(
            "<dependency>(.*?)</dependency>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern POM_GROUP = Pattern.compile("<groupId>\\s*([^<\\s]+)\\s*</groupId>", Pattern.CASE_INSENSITIVE);
    private static final Pattern POM_ARTIFACT = Pattern.compile("<artifactId>\\s*([^<\\s]+)\\s*</artifactId>", Pattern.CASE_INSENSITIVE);
    private static final Pattern POM_VERSION = Pattern.compile("<version>\\s*([^<\\s]+)\\s*</version>", Pattern.CASE_INSENSITIVE);
    private static final Pattern POM_SCOPE = Pattern.compile("<scope>\\s*([^<\\s]+)\\s*</scope>", Pattern.CASE_INSENSITIVE);
    private static final Pattern POM_OPTIONAL = Pattern.compile("<optional>\\s*true\\s*</optional>", Pattern.CASE_INSENSITIVE);

    private SharedBuildDependencies() {
    }

    public static List<Dependency> collect(Project consumer, File sharedDir, String group, String name, String version) {
        Map<String, Dependency> byId = new LinkedHashMap<String, Dependency>();
        collectDeclaredAndPomTransitives(consumer, sharedDir, byId);
        collectFromResolvedGraph(consumer, group, name, byId);
        return new ArrayList<Dependency>(byId.values());
    }

    public static List<String> parseDeclaredDependencies(String buildScript) {
        List<String> gavs = new ArrayList<String>();
        for (String[] entry : parseDeclaredDependencyEntries(buildScript)) {
            if (!"testImplementation".equals(entry[0])) {
                gavs.add(entry[1]);
            }
        }
        return gavs;
    }

    static List<String[]> parseDeclaredDependencyEntries(String buildScript) {
        if (buildScript == null || buildScript.isEmpty()) {
            return Collections.emptyList();
        }
        List<String[]> entries = new ArrayList<String[]>();
        Matcher matcher = DECLARED_DEP.matcher(buildScript);
        while (matcher.find()) {
            entries.add(new String[]{matcher.group(1), matcher.group(2)});
        }
        return entries;
    }

    public static String gradleScopeFor(String declaredConfiguration) {
        if ("api".equals(declaredConfiguration) || "implementation".equals(declaredConfiguration)
                || "compile".equals(declaredConfiguration)) {
            return "compileClasspath";
        }
        if ("compileOnly".equals(declaredConfiguration)) {
            return "compileOnly";
        }
        if ("runtimeOnly".equals(declaredConfiguration) || "runtime".equals(declaredConfiguration)) {
            return "runtimeClasspath";
        }
        if ("testImplementation".equals(declaredConfiguration) || "testCompile".equals(declaredConfiguration)) {
            return "testCompileClasspath";
        }
        if ("testRuntimeOnly".equals(declaredConfiguration) || "testRuntime".equals(declaredConfiguration)) {
            return "testRuntimeClasspath";
        }
        return StringUtils.defaultIfBlank(declaredConfiguration, "compileClasspath");
    }

    /**
     * POM scope to the shared-build Gradle configuration name. Null means skip.
     */
    public static String pomScopeToGradle(String pomScope) {
        if (StringUtils.isBlank(pomScope) || "compile".equalsIgnoreCase(pomScope)) {
            return "compileClasspath";
        }
        if ("runtime".equalsIgnoreCase(pomScope)) {
            return "runtimeClasspath";
        }
        return null;
    }

    /**
     * Consumer test classpaths are not scopes of the shared build itself.
     */
    public static boolean isConsumerClasspathNoise(String configurationName) {
        if (StringUtils.isBlank(configurationName)) {
            return false;
        }
        String name = configurationName.toLowerCase();
        return name.startsWith("test");
    }

    static List<String> parsePomCompileDependencies(String pomXml) {
        List<String> gavs = new ArrayList<String>();
        for (String[] entry : parsePomDependenciesWithScopes(pomXml)) {
            gavs.add(entry[0]);
        }
        return gavs;
    }

    static List<String[]> parsePomDependenciesWithScopes(String pomXml) {
        if (pomXml == null || pomXml.isEmpty()) {
            return Collections.emptyList();
        }
        String projectDeps = projectDependenciesBlock(pomXml);
        List<String[]> entries = new ArrayList<String[]>();
        Matcher matcher = POM_DEPENDENCY.matcher(projectDeps);
        while (matcher.find()) {
            String block = matcher.group(1);
            if (POM_OPTIONAL.matcher(block).find()) {
                continue;
            }
            String scope = pomScopeToGradle(firstMatch(POM_SCOPE, block));
            if (scope == null) {
                continue;
            }
            String depGroup = firstMatch(POM_GROUP, block);
            String depName = firstMatch(POM_ARTIFACT, block);
            String depVersion = firstMatch(POM_VERSION, block);
            if (StringUtils.isAnyBlank(depGroup, depName, depVersion) || depVersion.contains("${")) {
                continue;
            }
            entries.add(new String[]{depGroup + ":" + depName + ":" + depVersion, scope});
        }
        return entries;
    }

    static String projectDependenciesBlock(String pomXml) {
        String cleaned = pomXml
                .replaceAll("(?s)<dependencyManagement>.*?</dependencyManagement>", "")
                .replaceAll("(?s)<build>.*?</build>", "")
                .replaceAll("(?s)<profiles>.*?</profiles>", "")
                .replaceAll("(?s)<reporting>.*?</reporting>", "");
        Matcher matcher = Pattern.compile("(?s)<dependencies>(.*?)</dependencies>").matcher(cleaned);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String firstMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static void collectFromResolvedGraph(Project consumer, String group, String name,
                                                 Map<String, Dependency> byId) {
        if (consumer == null) {
            return;
        }
        for (Project project : consumer.getAllprojects()) {
            for (Configuration configuration : project.getConfigurations()) {
                if (configuration.getState() != Configuration.State.RESOLVED) {
                    continue;
                }
                if (isConsumerClasspathNoise(configuration.getName())) {
                    continue;
                }
                ResolvedComponentResult moduleRoot = findComponent(configuration, group, name);
                if (moduleRoot == null) {
                    continue;
                }
                Map<String, File> filesById = artifactFiles(consumer, configuration);
                collectReachable(consumer, moduleRoot, filesById, new HashSet<String>(), byId);
            }
        }
    }

    private static void collectReachable(Project consumer, ResolvedComponentResult node,
                                         Map<String, File> filesById, Set<String> visited, Map<String, Dependency> byId) {
        for (DependencyResult edge : node.getDependencies()) {
            if (!(edge instanceof ResolvedDependencyResult)) {
                continue;
            }
            ResolvedComponentResult selected = ((ResolvedDependencyResult) edge).getSelected();
            ModuleVersionIdentifier module = selected.getModuleVersion();
            if (module == null) {
                continue;
            }
            String id = ProjectUtils.getId(module);
            if (id == null || !visited.add(id)) {
                continue;
            }
            File file = filesById.get(id);
            if (file == null || !file.isFile() || file.getName().endsWith(".pom")) {
                File jar = jarForProjectComponent(selected.getId(), consumer);
                if (jar != null && jar.isFile()) {
                    file = jar;
                }
            }
            file = artifactFileForDependency(
                    file, SharedBuildLogicUtils.resolveGradleUserHome(), id);
            addDiscoveredDependency(byId, id, file);
            collectReachable(consumer, selected, filesById, visited, byId);
        }
    }

    private static void collectDeclaredAndPomTransitives(Project consumer, File sharedDir,
                                                         Map<String, Dependency> byId) {
        String gradleUserHome = SharedBuildLogicUtils.resolveGradleUserHome();
        Set<String> visitedPoms = new HashSet<String>();
        for (String[] entry : parseDeclaredDependencyEntries(SharedBuildLogicUtils.readBuildScript(sharedDir))) {
            String scope = gradleScopeFor(entry[0]);
            addGavAndPomTransitives(consumer, entry[1], scope, gradleUserHome, visitedPoms, byId);
        }
    }

    private static void addGavAndPomTransitives(Project consumer, String gav, String scope, String gradleUserHome,
                                                Set<String> visitedPoms, Map<String, Dependency> byId) {
        if (StringUtils.isBlank(gav) || !visitedPoms.add(gav)) {
            return;
        }
        File file = artifactFileForDependency(findResolvedFile(consumer, gav), gradleUserHome, gav);
        addDependency(byId, gav, file, scope);
        File pom = findCachedPom(gradleUserHome, gav);
        if (pom == null || !pom.isFile()) {
            return;
        }
        try {
            String pomXml = new String(java.nio.file.Files.readAllBytes(pom.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            for (String[] transitive : parsePomDependenciesWithScopes(pomXml)) {
                addGavAndPomTransitives(consumer, transitive[0], transitive[1], gradleUserHome, visitedPoms, byId);
            }
        } catch (Exception e) {
            log.debug("Could not read POM transitives for {}: {}", gav, e.getMessage());
        }
    }

    private static void addDiscoveredDependency(Map<String, Dependency> byId, String id, File file) {
        Dependency existing = byId.get(id);
        if (existing != null) {
            if (StringUtils.isBlank(existing.getSha1()) && file != null && file.isFile()) {
                String existingScope = "compileClasspath";
                if (existing.getScopes() != null && !existing.getScopes().isEmpty()) {
                    existingScope = existing.getScopes().iterator().next();
                }
                Dependency replacement = toDependency(id, file, existingScope);
                if (replacement != null) {
                    replacement.getScopes().clear();
                    replacement.getScopes().addAll(existing.getScopes());
                    byId.put(id, replacement);
                }
            }
            return;
        }
        addDependency(byId, id, file, "compileClasspath");
    }

    private static void addDependency(Map<String, Dependency> byId, String id, File file, String scope) {
        Dependency existing = byId.get(id);
        if (existing != null) {
            existing.getScopes().add(scope);
            if (StringUtils.isBlank(existing.getSha1()) && file != null && file.isFile()) {
                Dependency replacement = toDependency(id, file, scope);
                if (replacement != null) {
                    replacement.getScopes().addAll(existing.getScopes());
                    byId.put(id, replacement);
                }
            }
            return;
        }
        Dependency created = toDependency(id, file, scope);
        if (created != null) {
            byId.put(id, created);
        }
    }

    private static ResolvedComponentResult findComponent(Configuration detached, String group, String name) {
        for (ResolvedComponentResult component : detached.getIncoming().getResolutionResult().getAllComponents()) {
            ModuleVersionIdentifier module = component.getModuleVersion();
            if (module != null && group.equals(module.getGroup()) && name.equals(module.getName())) {
                return component;
            }
        }
        return null;
    }

    private static Map<String, File> artifactFiles(Project consumer, Configuration detached) {
        Map<String, File> files = new LinkedHashMap<String, File>();
        try {
            for (ResolvedArtifactResult artifact : detached.getIncoming().artifactView(view -> view.setLenient(true)).getArtifacts()) {
                File file = artifact.getFile();
                ComponentIdentifier id = artifact.getId().getComponentIdentifier();
                String depId = id.getDisplayName();
                if (id instanceof ProjectComponentIdentifier) {
                    ModuleVersionIdentifier module = null;
                    for (ResolvedComponentResult component : detached.getIncoming().getResolutionResult().getAllComponents()) {
                        if (component.getId().equals(id)) {
                            module = component.getModuleVersion();
                            break;
                        }
                    }
                    if (module != null) {
                        depId = ProjectUtils.getId(module);
                    }
                    if (file == null || !file.isFile()) {
                        file = jarForProjectComponent(id, consumer);
                    }
                }
                if (file != null && file.isFile()) {
                    files.put(depId, file);
                }
            }
        } catch (Exception e) {
            log.debug("Could not collect resolved artifact files: {}", e.getMessage());
        }
        return files;
    }

    public static File jarForProjectComponent(ComponentIdentifier id, Project consumer) {
        if (!(id instanceof ProjectComponentIdentifier) || consumer == null) {
            return null;
        }
        ProjectComponentIdentifier projectId = (ProjectComponentIdentifier) id;
        String includeName = UsedSharedBuilds.includedBuildName(projectId);
        File dir = findIncludeDir(consumer, includeName);
        if (dir == null) {
            return null;
        }
        String moduleName = SharedBuildLogicUtils.readRootProjectName(dir, dir.getName());
        String[] groupAndVersion = SharedBuildLogicUtils.readGroupAndVersionFromBuildScript(dir);
        String version = SharedBuildLogicUtils.resolvePublishedVersion(groupAndVersion[1], moduleName, dir);
        return SharedBuildLogicUtils.ensurePublishedJar(dir, moduleName, version);
    }

    static File findIncludeDir(Project consumer, String includeName) {
        if (includeName == null || includeName.isEmpty()) {
            return null;
        }
        for (IncludedBuild included : consumer.getGradle().getIncludedBuilds()) {
            File found = findNamedBuild(included.getProjectDir(), includeName);
            if (found != null) {
                return found;
            }
        }
        return findNamedBuild(consumer.getProjectDir(), includeName);
    }

    private static File findNamedBuild(File dir, String includeName) {
        if (dir == null || !dir.isDirectory()) {
            return null;
        }
        String name = SharedBuildLogicUtils.readRootProjectName(dir, dir.getName());
        if (includeName.equals(name) || includeName.equals(dir.getName())) {
            return dir;
        }
        for (File child : SettingsGradleParser.listIncludeBuildDirs(dir)) {
            File found = findNamedBuild(child, includeName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * Prefer a jar. A BOM / platform only has a POM — keep that so checksums exist.
     */
    static File artifactFileForDependency(File resolved, String gradleUserHome, String gav) {
        if (resolved != null && resolved.isFile() && !resolved.getName().endsWith(".pom")) {
            return resolved;
        }
        File jar = findCachedJar(gradleUserHome, gav);
        if (jar != null && jar.isFile()) {
            return jar;
        }
        if (resolved != null && resolved.isFile()) {
            return resolved;
        }
        return findCachedPom(gradleUserHome, gav);
    }

    static File findCachedPom(String gradleUserHome, String gav) {
        return findCachedFile(gradleUserHome, gav, ".pom");
    }

    static File findCachedJar(String gradleUserHome, String gav) {
        return findCachedFile(gradleUserHome, gav, ".jar");
    }

    private static File findCachedFile(String gradleUserHome, String gav, String extension) {
        if (StringUtils.isBlank(gradleUserHome) || StringUtils.isBlank(gav)) {
            return null;
        }
        if (gav.split(":").length < 3) {
            return null;
        }
        String[] parts = SharedBuildLogicUtils.gavParts(gav);
        File versionDir = new File(gradleUserHome, "caches/modules-2/files-2.1/" + parts[0] + "/" + parts[1] + "/" + parts[2]);
        File[] hashes = versionDir.isDirectory() ? versionDir.listFiles() : null;
        if (hashes == null) {
            return null;
        }
        File found = null;
        for (File hashDir : hashes) {
            File[] files = hashDir.isDirectory() ? hashDir.listFiles() : null;
            if (files == null) {
                continue;
            }
            for (File file : files) {
                String name = file.getName();
                if (!file.isFile() || !name.endsWith(extension)) {
                    continue;
                }
                if (".jar".equals(extension) && (name.endsWith("-sources.jar") || name.endsWith("-javadoc.jar"))) {
                    continue;
                }
                found = file;
                break;
            }
            if (found != null) {
                break;
            }
        }
        return found;
    }

    private static File findResolvedFile(Project consumer, String gav) {
        if (consumer == null || StringUtils.isBlank(gav)) {
            return null;
        }
        for (Project project : consumer.getAllprojects()) {
            for (Configuration configuration : project.getConfigurations()) {
                if (configuration.getState() != Configuration.State.RESOLVED) {
                    continue;
                }
                try {
                    for (ResolvedArtifactResult artifact : configuration.getIncoming()
                            .artifactView(view -> view.setLenient(true)).getArtifacts()) {
                        File file = artifact.getFile();
                        if (file != null && file.isFile() && gav.equals(artifact.getId().getComponentIdentifier().getDisplayName())) {
                            return file;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private static Dependency toDependency(String id, File file, String scope) {
        try {
            Set<String> scopes = new HashSet<String>();
            scopes.add(StringUtils.defaultIfBlank(scope, "compileClasspath"));
            DependencyBuilder builder = new DependencyBuilder()
                    .id(id)
                    .scopes(scopes)
                    .type(file != null && file.isFile() ? typeOf(file.getName()) : "");
            if (file != null && file.isFile()) {
                Map<String, String> checksums = FileChecksumCalculator.calculateChecksums(
                        file, MD5_ALGORITHM, SHA1_ALGORITHM, SHA256_ALGORITHM);
                builder.md5(checksums.get(MD5_ALGORITHM))
                        .sha1(checksums.get(SHA1_ALGORITHM))
                        .sha256(checksums.get(SHA256_ALGORITHM));
            }
            return builder.build();
        } catch (Exception e) {
            log.debug("Could not build dependency {}: {}", id, e.getMessage());
            return null;
        }
    }

    private static String typeOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1);
    }
}
