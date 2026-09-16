package org.jfrog.gradle.plugin.artifactory.utils;

import org.apache.commons.lang3.StringUtils;
import org.gradle.api.Project;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Opt-in includeSharedBuild helpers used by the plugin and initscripttemplate.gradle.
 * Module IDs stay a 3-part GAV; first-level shared builds nest under the consumer.
 */
public final class SharedBuildLogicUtils {
    private static final Logger log = Logging.getLogger(SharedBuildLogicUtils.class);
    private static final Pattern BUILD_SCRIPT_GROUP = Pattern.compile("(?m)^\\s*group\\s*=\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern BUILD_SCRIPT_VERSION = Pattern.compile("(?m)^\\s*version\\s*=\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern ROOT_PROJECT_NAME = Pattern.compile("(?m)^\\s*rootProject\\.name\\s*=\\s*['\"]([^'\"]+)['\"]");

    private SharedBuildLogicUtils() {
    }

    /**
     * True when -PincludeSharedBuild=true or ORG_GRADLE_PROJECT_includeSharedBuild=true.
     */
    public static boolean isIncludeSharedBuildEnabled(Project project) {
        Object value = project.findProperty("includeSharedBuild");
        return value != null && "true".equalsIgnoreCase(value.toString());
    }

    /**
     * Whether to publish the Maven POM for a publication.
     * Flag-off keeps the existing CI / task resolution.
     * Flag-on keeps the plugin default (publish POM) when jf gradle injects
     * publish.maven=false and the Gradle DSL did not set publishPom=false.
     */
    public static boolean shouldPublishMavenDescriptor(Boolean publisherMaven, Boolean taskPublishPom,
                                                      boolean includeSharedBuild) {
        if (includeSharedBuild) {
            if (taskPublishPom != null) {
                return taskPublishPom;
            }
            return true;
        }
        if (publisherMaven != null) {
            return publisherMaven;
        }
        return taskPublishPom != null ? taskPublishPom : true;
    }

    public static String resolveGradleUserHome() {
        String gradleUserHome = System.getenv("GRADLE_USER_HOME");
        return StringUtils.defaultIfBlank(gradleUserHome,
                System.getProperty("user.home") + File.separator + ".gradle");
    }

    /**
     * [group, name, version] from a GAV. Version is the last segment.
     */
    public static String[] gavParts(String moduleId) {
        if (StringUtils.isBlank(moduleId)) {
            return new String[]{"", "", "unspecified"};
        }
        String[] parts = moduleId.split(":");
        return new String[]{
                parts[0],
                parts.length >= 2 ? parts[1] : "",
                parts.length >= 3 ? parts[parts.length - 1] : "unspecified"
        };
    }

    public static String readBuildScript(File projectDir) {
        return readFirstExistingFile(projectDir, "build.gradle", "build.gradle.kts");
    }

    public static String[] readGroupAndVersionFromBuildScript(File projectDir) {
        String text = readBuildScript(projectDir);
        return new String[]{
                firstMatch(BUILD_SCRIPT_GROUP, text),
                StringUtils.defaultIfBlank(firstMatch(BUILD_SCRIPT_VERSION, text), "unspecified")
        };
    }

    public static String readRootProjectName(File projectDir, String defaultName) {
        String text = readFirstExistingFile(projectDir, "settings.gradle", "settings.gradle.kts");
        String name = firstMatch(ROOT_PROJECT_NAME, text);
        return StringUtils.defaultIfBlank(name, defaultName);
    }

    public static void ensurePublishedMetadata(File projectDir, String group, String name, String version,
                                              List<String> declaredGavs) {
        if (projectDir == null || StringUtils.isBlank(name)) {
            return;
        }
        String safeGroup = StringUtils.defaultIfBlank(group, "");
        String safeVersion = StringUtils.defaultIfBlank(version, "unspecified");
        File generated = new File(projectDir, "build/publications/generated");
        if (!hasPublicationFile(projectDir, "pom-default.xml")) {
            if (generated.isDirectory() || generated.mkdirs()) {
                writeGeneratedPom(new File(generated, "pom-default.xml"), safeGroup, name, safeVersion, declaredGavs);
            }
        }
        if (!hasPublicationFile(projectDir, "module.json")) {
            if (generated.isDirectory() || generated.mkdirs()) {
                writeGeneratedModule(new File(generated, "module.json"), safeGroup, name, safeVersion);
            }
        }
    }

    static boolean hasPublicationFile(File projectDir, String fileName) {
        File publications = new File(projectDir, "build/publications");
        File[] dirs = publications.isDirectory() ? publications.listFiles(File::isDirectory) : null;
        if (dirs == null) {
            return false;
        }
        for (File dir : dirs) {
            if (new File(dir, fileName).isFile()) {
                return true;
            }
        }
        return false;
    }

    private static void writeGeneratedPom(File pom, String group, String name, String version, List<String> declaredGavs) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n");
        xml.append("  <modelVersion>4.0.0</modelVersion>\n");
        xml.append("  <groupId>").append(xmlEscape(group)).append("</groupId>\n");
        xml.append("  <artifactId>").append(xmlEscape(name)).append("</artifactId>\n");
        xml.append("  <version>").append(xmlEscape(version)).append("</version>\n");
        if (declaredGavs != null && !declaredGavs.isEmpty()) {
            xml.append("  <dependencies>\n");
            for (String gav : declaredGavs) {
                String[] parts = gav.split(":");
                if (parts.length < 3) {
                    continue;
                }
                xml.append("    <dependency>\n");
                xml.append("      <groupId>").append(xmlEscape(parts[0])).append("</groupId>\n");
                xml.append("      <artifactId>").append(xmlEscape(parts[1])).append("</artifactId>\n");
                xml.append("      <version>").append(xmlEscape(parts[2])).append("</version>\n");
                xml.append("      <scope>compile</scope>\n");
                xml.append("    </dependency>\n");
            }
            xml.append("  </dependencies>\n");
        }
        xml.append("</project>\n");
        writeUtf8(pom, xml.toString());
    }

    private static void writeGeneratedModule(File moduleFile, String group, String name, String version) {
        String jarName = name + "-" + version + ".jar";
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"formatVersion\": \"1.1\",\n");
        json.append("  \"component\": {\n");
        json.append("    \"group\": \"").append(jsonEscape(group)).append("\",\n");
        json.append("    \"module\": \"").append(jsonEscape(name)).append("\",\n");
        json.append("    \"version\": \"").append(jsonEscape(version)).append("\"\n");
        json.append("  },\n");
        json.append("  \"variants\": [\n");
        json.append("    {\n");
        json.append("      \"name\": \"apiElements\",\n");
        json.append("      \"files\": [\n");
        json.append("        {\n");
        json.append("          \"name\": \"").append(jsonEscape(jarName)).append("\",\n");
        json.append("          \"url\": \"").append(jsonEscape(jarName)).append("\"\n");
        json.append("        }\n");
        json.append("      ]\n");
        json.append("    }\n");
        json.append("  ]\n");
        json.append("}\n");
        writeUtf8(moduleFile, json.toString());
    }

    private static void writeUtf8(File file, String text) {
        try {
            Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.debug("Could not write {}: {}", file, e.getMessage());
        }
    }

    private static String xmlEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // Guards concurrent synthetic-jar creation for the same target path when multiple consumer
    // subprojects resolve the same shared build in parallel within this Gradle daemon.
    private static final ConcurrentMap<String, Object> JAR_PACK_LOCKS = new ConcurrentHashMap<>();

    public static File ensurePublishedJar(File projectDir, String name, String version) {
        File existing = firstBuiltJar(projectDir);
        if (existing != null) {
            return existing;
        }
        File classes = firstClassesDir(projectDir);
        if (classes == null || StringUtils.isBlank(name)) {
            return null;
        }
        File libs = new File(projectDir, "build/libs");
        if (!libs.isDirectory() && !libs.mkdirs()) {
            return null;
        }
        File jar = new File(libs, name + "-" + StringUtils.defaultIfBlank(version, "unspecified") + ".jar");
        Object lock = JAR_PACK_LOCKS.computeIfAbsent(jar.getAbsolutePath(), k -> new Object());
        synchronized (lock) {
            if (jar.isFile()) {
                return jar;
            }
            try {
                packRootsToJar(Arrays.asList(classes, new File(projectDir, "build/resources/main")), jar);
                return jar.isFile() ? jar : null;
            } catch (Exception e) {
                log.debug("Could not pack classes for {}: {}", projectDir, e.getMessage());
                return null;
            }
        }
    }

    private static File firstClassesDir(File projectDir) {
        String[] paths = {
                "build/classes/java/main",
                "build/classes/kotlin/main",
                "build/classes/groovy/main"
        };
        for (String path : paths) {
            File dir = new File(projectDir, path);
            File[] files = dir.isDirectory() ? dir.listFiles() : null;
            if (files != null && files.length > 0) {
                return dir;
            }
        }
        return null;
    }

    private static void packRootsToJar(List<File> roots, File jar) throws IOException {
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar))) {
            Set<String> added = new HashSet<String>();
            for (File root : roots) {
                if (root != null && root.isDirectory()) {
                    addTreeToJar(root, root, out, added);
                }
            }
        }
    }

    private static void addTreeToJar(File root, File current, JarOutputStream out, Set<String> added) throws IOException {
        File[] children = current.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                addTreeToJar(root, child, out, added);
                continue;
            }
            String rel = root.toURI().relativize(child.toURI()).getPath();
            if (!added.add(rel)) {
                continue;
            }
            out.putNextEntry(new JarEntry(rel));
            Files.copy(child.toPath(), out);
            out.closeEntry();
        }
    }

    public static File firstBuiltJar(File projectDir) {
        if (projectDir == null) {
            return null;
        }
        File libs = new File(projectDir, "build/libs");
        File[] files = libs.isDirectory() ? libs.listFiles((dir, name) -> name.endsWith(".jar") || name.endsWith(".war")) : null;
        if (files == null || files.length == 0) {
            return null;
        }
        for (File file : files) {
            String name = file.getName();
            if (!name.endsWith("-sources.jar") && !name.endsWith("-javadoc.jar")) {
                return file;
            }
        }
        return files[0];
    }

    private static String readFirstExistingFile(File projectDir, String... names) {
        if (projectDir == null) {
            return "";
        }
        for (String name : names) {
            File file = new File(projectDir, name);
            if (!file.isFile()) {
                continue;
            }
            try {
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.debug("Could not read {}: {}", file, e.getMessage());
            }
        }
        return "";
    }

    private static String firstMatch(Pattern pattern, String text) {
        if (StringUtils.isBlank(text)) {
            return "";
        }
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    /**
     * Build a 3-part GAV. A usable parent ID becomes {@code parentGroup.parentName:name:version}.
     * An empty-group parent like {@code :nested-app:1.0.0} is not folded, to avoid a leading-dot group.
     */
    public static String buildQualifiedModuleId(String parentModuleId, String group, String name, String version) {
        String effectiveVersion = StringUtils.defaultIfBlank(version, "unspecified");
        if (isThreePartGav(parentModuleId)) {
            return nestedQualifierFromModuleId(parentModuleId) + ":" + name + ":" + effectiveVersion;
        }
        String inheritedGroup = parentModuleId != null && parentModuleId.contains(":") ? null : parentModuleId;
        return resolveEffectiveGroup(group, inheritedGroup) + ":" + name + ":" + effectiveVersion;
    }

    public static String resolveEffectiveGroup(String ownGroup, String inheritedGroup) {
        return hasOwnGroup(ownGroup) ? ownGroup : StringUtils.defaultString(inheritedGroup);
    }

    public static boolean hasOwnGroup(String group) {
        return StringUtils.isNotBlank(group) && !"unspecified".equals(group);
    }

    public static boolean isThreePartGav(String moduleId) {
        if (StringUtils.isBlank(moduleId)) {
            return false;
        }
        String[] parts = moduleId.split(":");
        return parts.length == 3 && hasOwnGroup(parts[0]);
    }

    /**
     * Project GAV without consumer nesting. Used when there is no usable 3-part module ID.
     */
    public static String ownModuleCoordinates(String group, String name, String version) {
        return StringUtils.defaultString(group) + ":" + name + ":" + StringUtils.defaultIfBlank(version, "unspecified");
    }

    /**
     * Deploy GAV is the shared project's own coordinates. Module IDs stay nested in build-info
     * for uniqueness; the Maven path does not include the consumer name or Artifactory repo key.
     * Repo name is {@code module.repository}.
     */
    public static String deployCoordinates(String moduleId, String group, String name, String version) {
        return ownModuleCoordinates(group, name, version);
    }

    /**
     * Replace the version segment of a 3-part GAV when export still said unspecified.
     */
    public static String withVersion(String moduleId, String version) {
        if (StringUtils.isBlank(moduleId) || StringUtils.isBlank(version)) {
            return moduleId;
        }
        String[] parts = moduleId.split(":");
        if (parts.length < 3) {
            return moduleId;
        }
        return parts[0] + ":" + parts[1] + ":" + version;
    }

    /**
     * Prefer a real version from export, then pom-default.xml, then {@code name-version.jar}.
     */
    public static String resolvePublishedVersion(String version, String moduleName, File buildDirectory) {
        if (hasRealVersion(version)) {
            return version;
        }
        String fromPom = readFirstPomVersion(buildDirectory);
        if (hasRealVersion(fromPom)) {
            return fromPom;
        }
        String fromJar = versionFromJarFileName(moduleName, buildDirectory);
        return hasRealVersion(fromJar) ? fromJar : StringUtils.defaultIfBlank(version, "unspecified");
    }

    /**
     * Prefer the consumer version when publishing a shared build from a consumer.
     * Includes keep a stable own version (e.g. 1.0.0); stamping the consumer version
     * keeps each consumer build on its own Maven path instead of overwriting jar/module.
     */
    public static String resolvePublishedVersion(String version, String moduleName, File buildDirectory,
                                                 String fallbackVersion) {
        if (hasRealVersion(fallbackVersion)) {
            return fallbackVersion;
        }
        return resolvePublishedVersion(version, moduleName, buildDirectory);
    }

    public static boolean hasRealVersion(String version) {
        return StringUtils.isNotBlank(version) && !"unspecified".equals(version);
    }

    static String readFirstPomVersion(File buildDirectory) {
        File publicationsDir = new File(buildDirectory, "build/publications");
        File[] publicationDirs = publicationsDir.isDirectory() ? publicationsDir.listFiles(File::isDirectory) : null;
        if (publicationDirs == null) {
            return "";
        }
        Pattern versionTag = Pattern.compile("<version>([^<]+)</version>");
        for (File pubDir : publicationDirs) {
            File pom = new File(pubDir, "pom-default.xml");
            if (!pom.isFile()) {
                continue;
            }
            try {
                Matcher matcher = versionTag.matcher(new String(Files.readAllBytes(pom.toPath()), StandardCharsets.UTF_8));
                if (matcher.find()) {
                    return matcher.group(1).trim();
                }
            } catch (Exception e) {
                log.debug("Could not read version from {}: {}", pom, e.getMessage());
            }
        }
        return "";
    }

    static String versionFromJarFileName(String moduleName, File buildDirectory) {
        File libs = new File(buildDirectory, "build/libs");
        File[] jars = libs.isDirectory() ? libs.listFiles((dir, name) -> name.endsWith(".jar")) : null;
        if (jars == null || StringUtils.isBlank(moduleName)) {
            return "";
        }
        String prefix = moduleName + "-";
        for (File jar : jars) {
            String name = jar.getName();
            if (name.startsWith(prefix) && name.endsWith(".jar")) {
                return name.substring(prefix.length(), name.length() - ".jar".length());
            }
        }
        return "";
    }

    /**
     * Maven layout path from a 3-part GAV, for example {@code com/example/build-logic-1/1.0.0/file}.
     */
    public static String buildMavenArtifactPath(String moduleId, String fileName) {
        if (StringUtils.isBlank(fileName)) {
            return "";
        }
        if (StringUtils.isBlank(moduleId)) {
            return fileName;
        }
        String[] parts = moduleId.split(":");
        if (parts.length < 3) {
            return moduleId.replace(":", "/") + "/" + fileName;
        }
        return parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[parts.length - 1] + "/" + fileName;
    }

    /**
     * Turn {@code group:name:version} into {@code group.name} for nesting the next child.
     */
    public static String nestedQualifierFromModuleId(String moduleId) {
        if (StringUtils.isBlank(moduleId)) {
            return moduleId;
        }
        String[] parts = moduleId.split(":");
        if (parts.length == 3) {
            return hasOwnGroup(parts[0]) ? parts[0] + "." + parts[1] : parts[1];
        }
        return moduleId.replace(':', '.');
    }

    /**
     * Build name / number / timestamp from the jf gradle extractor properties file.
     * Those values are what {@code jf rt bp} publishes; artifacts must be stamped with the same
     * properties or the build browser reports "No path found".
     */
    public static String[] extractorBuildCoordinates(Properties extractorProps) {
        if (extractorProps == null) {
            return new String[]{"", "", ""};
        }
        return new String[]{
                StringUtils.defaultString(extractorProps.getProperty("buildInfo.build.name")),
                StringUtils.defaultString(extractorProps.getProperty("buildInfo.build.number")),
                StringUtils.defaultString(extractorProps.getProperty("buildInfo.build.timestamp"))
        };
    }

    /**
     * Build properties stamped on deployed shared-build artifacts so Artifactory can correlate them.
     */
    public static Map<String, String> buildArtifactProperties(String buildName, String buildNumber, String buildTimestamp) {
        Map<String, String> props = new HashMap<>();
        if (StringUtils.isNotBlank(buildName)) {
            props.put("build.name", buildName);
        }
        if (StringUtils.isNotBlank(buildNumber)) {
            props.put("build.number", buildNumber);
        }
        if (StringUtils.isNotBlank(buildTimestamp)) {
            props.put("build.timestamp", buildTimestamp);
        }
        return props;
    }
}
