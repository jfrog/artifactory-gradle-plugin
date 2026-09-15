package org.jfrog.gradle.plugin.artifactory.utils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

/**
 * Fallback parser for includeBuild paths when Gradle's includedBuilds API
 * does not list a nested include.
 */
public final class SettingsGradleParser {
    private static final Logger log = Logging.getLogger(SettingsGradleParser.class);
    private static final Pattern INCLUDE_BUILD = Pattern.compile(
            "includeBuild\\s*(?:\\(\\s*)?['\"]([^'\"]+)['\"]");
    private static final Pattern LINE_COMMENT = Pattern.compile("//.*$", Pattern.MULTILINE);
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    private SettingsGradleParser() {
    }

    public static List<File> listIncludeBuildDirs(File projectDir) {
        List<File> dirs = new ArrayList<File>();
        for (String path : parseSettingsFile(settingsFile(projectDir))) {
            File dir = new File(projectDir, path);
            if (dir.isDirectory()) {
                dirs.add(dir);
            }
        }
        return dirs;
    }

    public static List<String> parseSettingsFile(File settingsFile) {
        if (settingsFile == null || !settingsFile.isFile()) {
            return Collections.emptyList();
        }
        try {
            return parseIncludeBuilds(new String(Files.readAllBytes(settingsFile.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("Could not parse settings file {}: {}", settingsFile.getAbsolutePath(), e.getMessage());
            return Collections.emptyList();
        }
    }

    public static List<String> parseGroovy(String fileContent) {
        return parseIncludeBuilds(fileContent);
    }

    public static List<String> parseKotlin(String fileContent) {
        return parseIncludeBuilds(fileContent);
    }

    static List<String> parseIncludeBuilds(String fileContent) {
        if (fileContent == null || fileContent.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> paths = new LinkedHashSet<String>();
        String cleaned = LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(fileContent).replaceAll(" ")).replaceAll("");
        Matcher matcher = INCLUDE_BUILD.matcher(cleaned);
        while (matcher.find()) {
            paths.add(matcher.group(1));
        }
        return new ArrayList<String>(paths);
    }

    private static File settingsFile(File projectDir) {
        File groovy = new File(projectDir, "settings.gradle");
        return groovy.isFile() ? groovy : new File(projectDir, "settings.gradle.kts");
    }
}
