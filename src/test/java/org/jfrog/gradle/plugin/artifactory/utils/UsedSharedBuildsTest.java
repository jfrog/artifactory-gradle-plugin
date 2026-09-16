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
}
