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
    // The version group excludes ':' (like group/artifact) so a classifier (":sources") or
    // extension ("@aar") suffix is not swallowed into the captured GAV; those suffixes are
    // matched by the trailing non-capturing groups and discarded - see RTECO-136 review notes.
    private static final Pattern DECLARED_DEP = Pattern.compile(
            "(implementation|api|compileOnly|runtimeOnly|testImplementation|compile|testCompile)\\s*\\(?\\s*['\"]" +
                    "([^:'\"]+:[^:'\"]+:[^:'\"@]+)(?::[^'\"@]*)?(?:@[^'\"]*)?['\"]");
    private static final Pattern POM_DEPENDENCY = Pattern.compile(
            "<dependency>(.*?)</dependency>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern POM_GROUP = Pattern.compile("<groupId>\\s*([^<\\s]+)\\s*</groupId>", Pattern.CASE_INSENSITIVE);
    private static final Pattern POM_ARTIFACT = Pattern.compile("<artifactId>\\s*([^<\\s]+)\\s*</artifactId>", Pattern.CASE_INSENSITIVE);
    private static final Pattern POM_VERSION = Pattern.compile("<version>\\s*([^<\\s]+)\\s*</version>", Pattern.CASE_INSENSITIVE);
    private static final Pattern POM_SCOPE = Pattern.compile("<scope>\\s*([^<\\s]+)\\s*</scope>", Pattern.CASE_INSENSITIVE);
    private static final Pattern POM_OPTIONAL = Pattern.compile("<optional>\\s*true\\s*</optional>", Pattern.CASE_INSENSITIVE);
    private static final Pattern POM_PROPERTIES_BLOCK = Pattern.compile(
            "(?s)<properties>(.*?)</properties>", Pattern.CASE_INSENSITIVE);
    private static final Pattern POM_PROPERTY_ENTRY = Pattern.compile(
            "<([A-Za-z0-9_.-]+)>\\s*([^<\\s][^<]*?)\\s*</\\1>");
    private static final Pattern POM_PROPERTY_REF = Pattern.compile("\\$\\{([^}]+)\\}");

    private SharedBuildDependencies() {
    }

    public static List<Dependency> collect(Project consumer, File sharedDir, String group, String name, String version) {
        Map<String, Dependency> byId = new LinkedHashMap<String, Dependency>();
        List<File> nestedIncludeDirs = nestedIncludeBuildDirs(sharedDir);
        collectDeclaredAndPomTransitives(consumer, sharedDir, byId);
        // version disambiguates which resolved component to walk when more than one configuration
        // resolved a same-named shared build to different versions (rare, but possible when a
        // consumer overrides a version for one classpath and not another).
        collectFromResolvedGraph(consumer, group, name, version, byId, nestedIncludeDirs);
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
        Map<String, String> properties = parsePomProperties(pomXml);
        String projectDeps = projectDependenciesBlock(pomXml);
        List<String[]> entries = new ArrayList<String[]>();
        Matcher matcher = POM_DEPENDENCY.matcher(projectDeps);
        while (matcher.find()) {
            String block = matcher.group(1);
            if (POM_OPTIONAL.matcher(block).find()) {
                continue;
            }
            String scope = pomScopeToGradle(SharedBuildLogicUtils.firstMatch(POM_SCOPE, block));
            if (scope == null) {
                continue;
            }
            String depGroup = resolvePomProperty(SharedBuildLogicUtils.firstMatch(POM_GROUP, block), properties);
            String depName = resolvePomProperty(SharedBuildLogicUtils.firstMatch(POM_ARTIFACT, block), properties);
            String depVersion = resolvePomProperty(SharedBuildLogicUtils.firstMatch(POM_VERSION, block), properties);
            if (StringUtils.isAnyBlank(depGroup, depName, depVersion) || depVersion.contains("${")) {
                // Still unresolved after substitution: the property is defined elsewhere (a
                // parent POM, a BOM) that this static parser does not fetch. Skip rather than
                // record a bogus literal "${...}" version.
                continue;
            }
            entries.add(new String[]{depGroup + ":" + depName + ":" + depVersion, scope});
        }
        return entries;
    }

    /**
     * {@code <properties>} entries declared directly in this POM, for resolving a same-POM
     * {@code ${propertyName}} reference in a dependency's own groupId/artifactId/version (a
     * common Maven pattern, e.g. JUnit 4's own {@code ${hamcrestVersion}}). Properties inherited
     * from a parent POM are out of scope for this static parser - not fetched.
     */
    static Map<String, String> parsePomProperties(String pomXml) {
        Map<String, String> properties = new java.util.HashMap<String, String>();
        Matcher blockMatcher = POM_PROPERTIES_BLOCK.matcher(pomXml);
        if (!blockMatcher.find()) {
            return properties;
        }
        Matcher entryMatcher = POM_PROPERTY_ENTRY.matcher(blockMatcher.group(1));
        while (entryMatcher.find()) {
            properties.put(entryMatcher.group(1), entryMatcher.group(2).trim());
        }
        return properties;
    }

    /**
     * Replace a single {@code ${propertyName}} reference using this POM's own properties.
     * Leaves the value unchanged (including the placeholder) if it isn't a property reference,
     * or if the referenced property isn't declared in this same POM.
     */
    static String resolvePomProperty(String value, Map<String, String> properties) {
        if (StringUtils.isBlank(value) || !value.contains("${")) {
            return value;
        }
        Matcher refMatcher = POM_PROPERTY_REF.matcher(value);
        if (!refMatcher.matches()) {
            return value;
        }
        String resolved = properties.get(refMatcher.group(1));
        return resolved != null ? resolved : value;
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

    private static void collectFromResolvedGraph(Project consumer, String group, String name, String version,
                                                 Map<String, Dependency> byId, List<File> nestedIncludeDirs) {
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
                ResolvedComponentResult moduleRoot = findComponent(configuration, group, name, version);
                if (moduleRoot == null) {
                    continue;
                }
                Map<String, File> filesById = artifactFiles(consumer, configuration);
                collectReachable(consumer, moduleRoot, filesById, new HashSet<String>(), byId, nestedIncludeDirs);
            }
        }
    }

    private static void collectReachable(Project consumer, ResolvedComponentResult node,
                                         Map<String, File> filesById, Set<String> visited, Map<String, Dependency> byId,
                                         List<File> nestedIncludeDirs) {
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
            // If this edge is itself a project dependency onto another used shared build, the GAV
            // recorded here must be the version SharedBuildCollector.register() will actually
            // publish it under (own version stamped with the consumer's, when real) - not Gradle's
            // raw ModuleVersionIdentifier - or build-info records a dependency edge to a version
            // nothing was ever deployed as.
            String published = publishedGavForProjectComponent(selected.getId(), consumer, id);
            if (published != null) {
                id = published;
            }
            if (id == null || !visited.add(id)) {
                continue;
            }
            // A first-level shared build's OWN nested composite (e.g. build-logic-1's
            // build-logic-2) is never itself published, so it would only ever be recorded as an
            // unresolved (no checksum) edge here. Do not record it as a dependency - just keep
            // walking through it so its own real dependencies still get attributed to the outer
            // shared build, matching addGavAndPomTransitives' static-parse handling of the same
            // case and the FlexPack collection's shape.
            if (nestedIncludeBuildDirFor(id, nestedIncludeDirs) == null) {
                File file = filesById.get(id);
                if (file == null || !file.isFile() || file.getName().endsWith(".pom")) {
                    File jar = jarForProjectComponent(selected.getId(), consumer);
                    if (jar != null && jar.isFile()) {
                        file = jar;
                    }
                }
                file = artifactFileForDependency(
                        file, SharedBuildLogicUtils.resolveGradleUserHome(), id);
                addDependency(byId, id, file, null);
            }
            collectReachable(consumer, selected, filesById, visited, byId, nestedIncludeDirs);
        }
    }

    private static void collectDeclaredAndPomTransitives(Project consumer, File sharedDir,
                                                         Map<String, Dependency> byId) {
        String gradleUserHome = SharedBuildLogicUtils.resolveGradleUserHome();
        Set<String> visitedPoms = new HashSet<String>();
        List<File> nestedIncludeDirs = nestedIncludeBuildDirs(sharedDir);
        for (String[] entry : parseDeclaredDependencyEntries(SharedBuildLogicUtils.readBuildScript(sharedDir))) {
            String scope = gradleScopeFor(entry[0]);
            addGavAndPomTransitives(consumer, entry[1], scope, gradleUserHome, visitedPoms, byId, nestedIncludeDirs);
        }
    }

    // Matches includeBuild 'path' / includeBuild("path") in a settings.gradle(.kts), the same
    // notation UsedSharedBuilds/gradle_shared_builds.go (FlexPack) already handle for the
    // consumer's own settings.gradle. Used here for a first-level shared build's OWN nested
    // includeBuild (e.g. build-logic-1's build-logic-2), which the consumer's Gradle instance
    // does not expose via IncludedBuild.getIncludedBuilds() (that only lists the consumer's own
    // direct includes), so it must be found the same static-parse way.
    private static final Pattern INCLUDE_BUILD = Pattern.compile("includeBuild\\s*\\(?\\s*['\"]([^'\"]+)['\"]");

    // Package-visible for SharedBuildDependenciesTest.
    static List<File> nestedIncludeBuildDirs(File dir) {
        List<File> dirs = new ArrayList<File>();
        String settingsText = SharedBuildLogicUtils.readSettingsScript(dir);
        if (StringUtils.isBlank(settingsText)) {
            return dirs;
        }
        for (String line : settingsText.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("includeBuild")) {
                continue;
            }
            Matcher matcher = INCLUDE_BUILD.matcher(trimmed);
            if (!matcher.find()) {
                continue;
            }
            File nested = new File(dir, matcher.group(1));
            if (nested.isDirectory()) {
                dirs.add(nested);
            }
        }
        return dirs;
    }

    /**
     * The nested includeBuild directory whose own rootProject.name matches this GAV's artifact
     * segment, or null. A shared build's own nested composite has no published coordinates the
     * consumer can resolve/checksum, so it is identified by name instead.
     */
    private static File nestedIncludeBuildDirFor(String gav, List<File> nestedIncludeDirs) {
        if (nestedIncludeDirs.isEmpty()) {
            return null;
        }
        String artifact = SharedBuildLogicUtils.gavParts(gav)[1];
        for (File dir : nestedIncludeDirs) {
            if (SharedBuildLogicUtils.readRootProjectName(dir, dir.getName()).equals(artifact)) {
                return dir;
            }
        }
        return null;
    }

    private static void addGavAndPomTransitives(Project consumer, String gav, String scope, String gradleUserHome,
                                                Set<String> visitedPoms, Map<String, Dependency> byId,
                                                List<File> nestedIncludeDirs) {
        if (StringUtils.isBlank(gav) || !visitedPoms.add(gav)) {
            return;
        }
        File nestedDir = nestedIncludeBuildDirFor(gav, nestedIncludeDirs);
        if (nestedDir != null) {
            // A first-level shared build's own nested composite (e.g. build-logic-1's
            // build-logic-2) is never itself published/deployed anywhere the consumer can
            // resolve a checksum for, so recording it as a dependency edge would always be an
            // unresolved "sha1=null" entry. Flatten its own declared dependencies directly onto
            // the outer shared build instead, matching the FlexPack collection's shape here.
            for (String[] nestedEntry : parseDeclaredDependencyEntries(SharedBuildLogicUtils.readBuildScript(nestedDir))) {
                addGavAndPomTransitives(consumer, nestedEntry[1], gradleScopeFor(nestedEntry[0]), gradleUserHome,
                        visitedPoms, byId, nestedIncludeBuildDirs(nestedDir));
            }
            return;
        }
        File file = artifactFileForDependency(findResolvedFile(consumer, gav), gradleUserHome, gav);
        addDependency(byId, gav, file, scope);
        File pom = findCachedPom(gradleUserHome, gav);
        if (pom == null || !pom.isFile()) {
            return;
        }
        try {
            String pomXml = org.apache.commons.io.FileUtils.readFileToString(pom, java.nio.charset.StandardCharsets.UTF_8);
            for (String[] transitive : parsePomDependenciesWithScopes(pomXml)) {
                addGavAndPomTransitives(consumer, transitive[0], transitive[1], gradleUserHome, visitedPoms, byId,
                        nestedIncludeDirs);
            }
        } catch (Exception e) {
            log.debug("Could not read POM transitives for {}: {}", gav, e.getMessage());
        }
    }

    /**
     * Merge a discovered id/file into the map. A null scope (edges found while walking the
     * already-resolved dependency graph, which carries no per-configuration scope of its own)
     * leaves an existing entry's scopes untouched and defaults a new entry to compileClasspath.
     * A non-null scope (declared/POM-derived dependencies, which do carry one) is always added
     * to the entry's scope set. This is the merged form of what used to be two near-identical
     * methods (addDiscoveredDependency / addDependency) differing only in that behavior.
     */
    private static void addDependency(Map<String, Dependency> byId, String id, File file, String scope) {
        Dependency existing = byId.get(id);
        if (existing != null) {
            if (scope != null) {
                existing.getScopes().add(scope);
            }
            if (StringUtils.isBlank(existing.getSha1()) && file != null && file.isFile()) {
                String replacementScope = scope != null ? scope : firstScopeOrDefault(existing);
                Dependency replacement = toDependency(id, file, replacementScope);
                if (replacement != null) {
                    replacement.getScopes().clear();
                    replacement.getScopes().addAll(existing.getScopes());
                    byId.put(id, replacement);
                }
            }
            return;
        }
        Dependency created = toDependency(id, file, scope != null ? scope : "compileClasspath");
        if (created != null) {
            byId.put(id, created);
        }
    }

    private static String firstScopeOrDefault(Dependency existing) {
        if (existing.getScopes() != null && !existing.getScopes().isEmpty()) {
            return existing.getScopes().iterator().next();
        }
        return "compileClasspath";
    }

    /**
     * version, when a real one is given, disambiguates between same group:name components at
     * different versions across configurations; otherwise the first group:name match wins,
     * unchanged from before version-awareness was added here (see RTECO-136 review notes).
     */
    static ResolvedComponentResult findComponent(Configuration detached, String group, String name, String version) {
        ResolvedComponentResult fallback = null;
        for (ResolvedComponentResult component : detached.getIncoming().getResolutionResult().getAllComponents()) {
            ModuleVersionIdentifier module = component.getModuleVersion();
            if (module == null || !group.equals(module.getGroup()) || !name.equals(module.getName())) {
                continue;
            }
            if (SharedBuildLogicUtils.hasRealVersion(version) && version.equals(module.getVersion())) {
                return component;
            }
            if (fallback == null) {
                fallback = component;
            }
        }
        return fallback;
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
                    String published = publishedGavForProjectComponent(id, consumer, depId);
                    if (published != null) {
                        depId = published;
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
        ResolvedSharedBuild shared = resolveSharedBuildProjectComponent(id, consumer);
        if (shared == null) {
            return null;
        }
        return SharedBuildLogicUtils.ensurePublishedJar(shared.dir, shared.name, shared.version);
    }

    /**
     * GAV that {@code SharedBuildCollector.register()} will actually publish this project
     * component under (own version, consumer-version-stamped the same way register() stamps it),
     * so a dependency edge onto a used shared build is recorded with the version that was truly
     * deployed rather than the shared build's raw/own {@link ModuleVersionIdentifier} version.
     * Returns null when {@code id} is not a project component resolving to a used shared build,
     * in which case the caller's own already-computed GAV should be kept unchanged.
     *
     * @param fallbackGav the caller's already-computed GAV, used only for its group when the
     *                    shared build's own build script does not declare one.
     */
    public static String publishedGavForProjectComponent(ComponentIdentifier id, Project consumer, String fallbackGav) {
        ResolvedSharedBuild shared = resolveSharedBuildProjectComponent(id, consumer);
        if (shared == null) {
            return null;
        }
        String fallbackGroup = StringUtils.isNotBlank(fallbackGav) ? SharedBuildLogicUtils.gavParts(fallbackGav)[0] : "";
        String group = SharedBuildLogicUtils.hasOwnGroup(shared.group) ? shared.group : fallbackGroup;
        return group + ":" + shared.name + ":" + shared.version;
    }

    private static ResolvedSharedBuild resolveSharedBuildProjectComponent(ComponentIdentifier id, Project consumer) {
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
        // Same consumer-version fallback SharedBuildCollector.register() stamps the deploy with -
        // always the root project's version, regardless of which project in the build is asking.
        Project rootProject = consumer.getRootProject();
        String consumerVersion = rootProject != null && rootProject.getVersion() != null
                ? rootProject.getVersion().toString() : null;
        String version = SharedBuildLogicUtils.resolvePublishedVersion(groupAndVersion[1], moduleName, dir, consumerVersion);
        return new ResolvedSharedBuild(dir, groupAndVersion[0], moduleName, version);
    }

    private static final class ResolvedSharedBuild {
        final File dir;
        final String group;
        final String name;
        final String version;

        ResolvedSharedBuild(File dir, String group, String name, String version) {
            this.dir = dir;
            this.group = group;
            this.name = name;
            this.version = version;
        }
    }

    static File findIncludeDir(Project consumer, String includeName) {
        if (includeName == null || includeName.isEmpty() || consumer == null) {
            return null;
        }
        for (IncludedBuild included : consumer.getGradle().getIncludedBuilds()) {
            File dir = included.getProjectDir();
            if (matchesInclude(dir, included.getName(), includeName)) {
                return dir;
            }
        }
        File buildSrc = new File(consumer.getProjectDir(), "buildSrc");
        if (buildSrc.isDirectory() && matchesInclude(buildSrc, "buildSrc", includeName)) {
            return buildSrc;
        }
        return null;
    }

    private static boolean matchesInclude(File dir, String includedName, String includeName) {
        if (dir == null || !dir.isDirectory()) {
            return false;
        }
        return includeName.equals(includedName)
                || includeName.equals(dir.getName())
                || includeName.equals(SharedBuildLogicUtils.readRootProjectName(dir, dir.getName()));
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
                    .type(file != null && file.isFile() ? StringUtils.substringAfterLast(file.getName(), ".") : "");
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

}
