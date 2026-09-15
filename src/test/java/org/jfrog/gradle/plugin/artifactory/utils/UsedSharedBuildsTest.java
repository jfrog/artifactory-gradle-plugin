package org.jfrog.gradle.plugin.artifactory.utils;

import org.testng.annotations.Test;

import java.io.File;
import java.util.Collections;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class UsedSharedBuildsTest {

    @Test
    public void testIncludeUsedWhenResolvedBuildNameMatches() {
        assertTrue(UsedSharedBuilds.isUsed(
                new File("/tmp/build-logic-1"),
                "build-logic-1",
                Collections.<String>emptyList(),
                Collections.singletonList("build-logic-1")));
    }

    @Test
    public void testIncludeUsedWhenPluginJarLivesInThatFolder() {
        File includeDir = new File("/tmp/build-logic-1");
        String pluginJar = "file:/tmp/build-logic-1/build/libs/build-logic-1-1.0.0.jar";
        assertTrue(UsedSharedBuilds.isUsed(
                includeDir,
                "build-logic-1",
                Collections.singletonList(pluginJar),
                Collections.<String>emptyList()));
    }

    @Test
    public void testIncludeNotUsedWhenOnlyDeclaredNameMatches() {
        assertFalse(UsedSharedBuilds.isUsed(
                new File("/tmp/build-logic-1"),
                "build-logic-1",
                Collections.<String>emptyList(),
                Collections.<String>emptyList()));
    }

    @Test
    public void testUnusedIncludeIsSkipped() {
        assertFalse(UsedSharedBuilds.isUsed(
                new File("/tmp/unused-logic"),
                "unused-logic",
                Collections.singletonList("file:/tmp/other/build/libs/other.jar"),
                Collections.singletonList("build-logic-1")));
    }

    @Test
    public void testNestedIncludeUsedWhenParentDeclaresIt() throws Exception {
        File parent = new File(System.getProperty("java.io.tmpdir"), "jfrog-nested-used-parent");
        File child = new File(parent, "build-logic-2");
        assertTrue(parent.mkdirs() || parent.isDirectory());
        assertTrue(child.mkdirs() || child.isDirectory());
        File settings = new File(child, "settings.gradle");
        File build = new File(parent, "build.gradle");
        try {
            java.nio.file.Files.write(settings.toPath(), "rootProject.name = 'build-logic-2'\n".getBytes());
            java.nio.file.Files.write(build.toPath(),
                    "dependencies {\n    implementation 'com.example:build-logic-2:1.0.0'\n}\n".getBytes());
            assertTrue(UsedSharedBuilds.isNestedIncludeUsed(
                    parent, child, Collections.<String>emptyList()));
        } finally {
            settings.delete();
            build.delete();
            child.delete();
            parent.delete();
        }
    }

    @Test
    public void testNestedIncludeSkippedWhenOnlyLeftoverJar() throws Exception {
        File parent = new File(System.getProperty("java.io.tmpdir"), "jfrog-nested-unused-parent");
        File child = new File(parent, "build-logic-4");
        File libs = new File(child, "build/libs");
        assertTrue(libs.mkdirs() || libs.isDirectory());
        File leftover = new File(libs, "build-logic-4-1.0.0.jar");
        File settings = new File(child, "settings.gradle");
        File build = new File(parent, "build.gradle");
        try {
            java.nio.file.Files.write(settings.toPath(), "rootProject.name = 'build-logic-4'\n".getBytes());
            java.nio.file.Files.write(build.toPath(), "dependencies {}\n".getBytes());
            assertTrue(leftover.createNewFile() || leftover.isFile());
            assertFalse(UsedSharedBuilds.isNestedIncludeUsed(
                    parent, child, Collections.<String>emptyList()));
        } finally {
            leftover.delete();
            libs.delete();
            new File(child, "build").delete();
            settings.delete();
            build.delete();
            child.delete();
            parent.delete();
        }
    }

    @Test
    public void testNestedIncludeUsedWhenResolvedInConsumerGraph() throws Exception {
        File parent = new File(System.getProperty("java.io.tmpdir"), "jfrog-nested-resolved-parent");
        File child = new File(parent, "build-logic-2");
        assertTrue(child.mkdirs() || child.isDirectory());
        File settings = new File(child, "settings.gradle");
        try {
            java.nio.file.Files.write(settings.toPath(), "rootProject.name = 'build-logic-2'\n".getBytes());
            assertTrue(UsedSharedBuilds.isNestedIncludeUsed(
                    parent, child, Collections.singletonList("build-logic-2")));
        } finally {
            settings.delete();
            child.delete();
            parent.delete();
        }
    }
}
