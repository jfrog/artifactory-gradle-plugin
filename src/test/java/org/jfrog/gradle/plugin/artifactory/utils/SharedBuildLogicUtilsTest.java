package org.jfrog.gradle.plugin.artifactory.utils;

import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class SharedBuildLogicUtilsTest {

    @Test
    public void testGavPartsSplitsGroupNameVersion() {
        assertEquals(SharedBuildLogicUtils.gavParts("com.example:nested-app:1.0.0"),
                new String[]{"com.example", "nested-app", "1.0.0"});
        assertEquals(SharedBuildLogicUtils.gavParts(""),
                new String[]{"", "", "unspecified"});
    }

    @Test
    public void testReadGroupAndVersionFromBuildScript() throws Exception {
        java.io.File dir = new java.io.File(System.getProperty("java.io.tmpdir"), "jfrog-read-build-script");
        assertTrue(dir.mkdirs() || dir.isDirectory());
        java.io.File buildFile = new java.io.File(dir, "build.gradle");
        try {
            java.nio.file.Files.write(buildFile.toPath(),
                    "group = 'com.example'\nversion = '1.0.0'\n".getBytes());
            String[] result = SharedBuildLogicUtils.readGroupAndVersionFromBuildScript(dir);
            assertEquals(result[0], "com.example");
            assertEquals(result[1], "1.0.0");
        } finally {
            buildFile.delete();
            dir.delete();
        }
    }

    @Test
    public void testBuildQualifiedModuleIdNestsFirstLevelUnderConsumer() {
        String moduleId = SharedBuildLogicUtils.buildQualifiedModuleId(
                "com.example:nested-app:1.0.0", "", "buildSrc", "unspecified");
        assertEquals(moduleId, "com.example.nested-app:buildSrc:unspecified");
        assertEquals(moduleId.split(":").length, 3, "Shared-build module IDs must stay a 3-part GAV");
    }

    @Test
    public void testNestedDemoProducesFiveDistinctModuleIds() {
        // Consumer plus buildSrc, first-level include, that include's buildSrc, and the nested include.
        String consumer = "com.example:nested-app:1.0.0";
        String consumerBuildSrc = SharedBuildLogicUtils.buildQualifiedModuleId(
                consumer, "com.example", "buildSrc", "unspecified");
        String buildLogic1 = SharedBuildLogicUtils.buildQualifiedModuleId(
                consumer, "com.example", "build-logic-1", "1.0.0");
        String buildLogic1BuildSrc = SharedBuildLogicUtils.buildQualifiedModuleId(
                buildLogic1, "com.example", "buildSrc", "unspecified");
        String buildLogic2 = SharedBuildLogicUtils.buildQualifiedModuleId(
                buildLogic1, "com.example", "build-logic-2", "1.0.0");

        assertEquals(consumerBuildSrc, "com.example.nested-app:buildSrc:unspecified");
        assertEquals(buildLogic1, "com.example.nested-app:build-logic-1:1.0.0");
        assertEquals(buildLogic1BuildSrc, "com.example.nested-app.build-logic-1:buildSrc:unspecified");
        assertEquals(buildLogic2, "com.example.nested-app.build-logic-1:build-logic-2:1.0.0");
        java.util.Set<String> ids = new java.util.HashSet<>(java.util.Arrays.asList(
                consumer, consumerBuildSrc, buildLogic1, buildLogic1BuildSrc, buildLogic2));
        assertEquals(ids.size(), 5, "Shared-build collection must keep all five modules");
    }

    @Test
    public void testBuildQualifiedModuleIdNestsIncludeUnderConsumer() {
        String moduleId = SharedBuildLogicUtils.buildQualifiedModuleId(
                "com.example:nested-app:1.0.0", "com.example", "build-logic-1", "1.0.0");
        assertEquals(moduleId, "com.example.nested-app:build-logic-1:1.0.0");
        assertEquals(moduleId.split(":").length, 3);
    }

    @Test
    public void testEmptyGroupConsumerGavDoesNotCreateLeadingDot() {
        // Plugin apply can see ":nested-app:1.0.0" before group is set; do not fold that into ".nested-app:...".
        String moduleId = SharedBuildLogicUtils.buildQualifiedModuleId(
                ":nested-app:1.0.0", "com.example", "build-logic-1", "1.0.0");
        assertFalse(moduleId.startsWith("."), "Empty consumer group must not produce a leading-dot module ID");
        assertEquals(moduleId, "com.example:build-logic-1:1.0.0");
    }

    @Test
    public void testBuildQualifiedModuleIdWithoutOwnGroupUsesInheritedGroup() {
        String moduleId = SharedBuildLogicUtils.buildQualifiedModuleId(null, "com.example", "buildSrc", "unspecified");
        assertEquals(moduleId, "com.example:buildSrc:unspecified");
    }

    @Test
    public void testResolveEffectiveGroupPrefersOwnGroup() {
        assertEquals(SharedBuildLogicUtils.resolveEffectiveGroup("com.example", "other"), "com.example");
        assertEquals(SharedBuildLogicUtils.resolveEffectiveGroup("", "com.example"), "com.example");
        assertEquals(SharedBuildLogicUtils.resolveEffectiveGroup("unspecified", "com.example"), "com.example");
    }

    @Test
    public void testBuildQualifiedModuleIdTreatsLiteralUnspecifiedGroupAsBlank() {
        String moduleId = SharedBuildLogicUtils.buildQualifiedModuleId(null, "unspecified", "buildSrc", "unspecified");
        assertEquals(moduleId, ":buildSrc:unspecified");
    }

    @Test
    public void testWithVersionReplacesUnspecifiedSegment() {
        assertEquals(SharedBuildLogicUtils.withVersion(
                "com.example.nested-app:build-logic-1:unspecified", "1.0.0"),
                "com.example.nested-app:build-logic-1:1.0.0");
        assertEquals(SharedBuildLogicUtils.buildMavenArtifactPath(
                "com.example:build-logic-1:1.0.0", "build-logic-1-1.0.0.jar"),
                "com/example/build-logic-1/1.0.0/build-logic-1-1.0.0.jar");
    }

    @Test
    public void testDeployCoordinatesForBuildSrcUseNestedModuleId() {
        String consumerBuildSrc = SharedBuildLogicUtils.deployCoordinates(
                "com.example.nested-app:buildSrc:unspecified", "com.example", "buildSrc", "unspecified");
        String nestedBuildSrc = SharedBuildLogicUtils.deployCoordinates(
                "com.example.nested-app.build-logic-1:buildSrc:unspecified", "com.example", "buildSrc", "unspecified");

        assertEquals(consumerBuildSrc, "com.example.nested-app:buildSrc:unspecified");
        assertEquals(nestedBuildSrc, "com.example.nested-app.build-logic-1:buildSrc:unspecified");
        assertNotEquals(consumerBuildSrc, nestedBuildSrc);
        assertEquals(SharedBuildLogicUtils.buildMavenArtifactPath(consumerBuildSrc, "buildSrc-unspecified.jar"),
                "com/example/nested-app/buildSrc/unspecified/buildSrc-unspecified.jar");
        assertEquals(SharedBuildLogicUtils.buildMavenArtifactPath(nestedBuildSrc, "buildSrc-unspecified.jar"),
                "com/example/nested-app/build-logic-1/buildSrc/unspecified/buildSrc-unspecified.jar");
    }

    @Test
    public void testDeployCoordinatesAlwaysUseNestedModuleId() {
        String firstLevel = SharedBuildLogicUtils.deployCoordinates(
                "com.example.nested-app:build-logic-1:1.0.0", "com.example", "build-logic-1", "1.0.0");
        String nested = SharedBuildLogicUtils.deployCoordinates(
                "com.example.nested-app.build-logic-1:build-logic-2:1.0.0", "com.example", "build-logic-2", "1.0.0");
        String sameNameSibling = SharedBuildLogicUtils.deployCoordinates(
                "com.example.nested-app:build-logic-2:1.0.0", "com.example", "build-logic-2", "1.0.0");

        assertEquals(firstLevel, "com.example.nested-app:build-logic-1:1.0.0");
        assertEquals(nested, "com.example.nested-app.build-logic-1:build-logic-2:1.0.0");
        assertEquals(sameNameSibling, "com.example.nested-app:build-logic-2:1.0.0");
        assertNotEquals(nested, sameNameSibling);
        assertEquals(SharedBuildLogicUtils.buildMavenArtifactPath(firstLevel, "build-logic-1-1.0.0.jar"),
                "com/example/nested-app/build-logic-1/1.0.0/build-logic-1-1.0.0.jar");
        assertEquals(SharedBuildLogicUtils.buildMavenArtifactPath(nested, "build-logic-2-1.0.0.jar"),
                "com/example/nested-app/build-logic-1/build-logic-2/1.0.0/build-logic-2-1.0.0.jar");
        assertEquals(SharedBuildLogicUtils.buildMavenArtifactPath(sameNameSibling, "build-logic-2-1.0.0.jar"),
                "com/example/nested-app/build-logic-2/1.0.0/build-logic-2-1.0.0.jar");
    }

    @Test
    public void testOwnModuleCoordinatesStayTheProjectGav() {
        assertEquals(SharedBuildLogicUtils.ownModuleCoordinates("com.example", "build-logic-1", "1.0.0"),
                "com.example:build-logic-1:1.0.0");
        assertEquals(SharedBuildLogicUtils.buildMavenArtifactPath(
                "com.example:build-logic-1:1.0.0", "build-logic-1-1.0.0.jar"),
                "com/example/build-logic-1/1.0.0/build-logic-1-1.0.0.jar");
    }

    @Test
    public void testResolvePublishedVersionPrefersRealVersionThenJarName() {
        assertEquals(SharedBuildLogicUtils.resolvePublishedVersion("1.0.0", "build-logic-1", new java.io.File("/tmp")),
                "1.0.0");

        java.io.File temp = new java.io.File(System.getProperty("java.io.tmpdir"), "jfrog-shared-build-version-test");
        java.io.File libs = new java.io.File(temp, "build/libs");
        assertTrue(libs.mkdirs() || libs.isDirectory());
        java.io.File jar = new java.io.File(libs, "build-logic-1-1.0.0.jar");
        try {
            assertTrue(jar.createNewFile() || jar.isFile());
            assertEquals(SharedBuildLogicUtils.resolvePublishedVersion("unspecified", "build-logic-1", temp),
                    "1.0.0");
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        } finally {
            jar.delete();
            libs.delete();
            new java.io.File(temp, "build").delete();
            temp.delete();
        }
    }

    @Test
    public void testBuildMavenArtifactPathFromThreePartGav() {
        String path = SharedBuildLogicUtils.buildMavenArtifactPath(
                "com.example.nested-app:build-logic-1:1.0.0", "build-logic-1-1.0.0.jar");
        assertEquals(path, "com/example/nested-app/build-logic-1/1.0.0/build-logic-1-1.0.0.jar");
    }

    @Test
    public void testBuildMavenArtifactPathForNestedInclude() {
        String path = SharedBuildLogicUtils.buildMavenArtifactPath(
                "com.example.nested-app.build-logic-1:build-logic-2:1.0.0", "build-logic-2-1.0.0.jar");
        assertEquals(path, "com/example/nested-app/build-logic-1/build-logic-2/1.0.0/build-logic-2-1.0.0.jar");
    }

    @Test
    public void testBuildMavenArtifactPathDoesNotInsertConsumerHierarchy() {
        String path = SharedBuildLogicUtils.buildMavenArtifactPath(
                "com.example:nested-app:build-logic-1:1.0.0", "build-logic-1-1.0.0.jar");
        assertFalse(path.contains("nested-app/build-logic-1"),
                "A 4-part ID must not be treated as a Maven layout hierarchy");
    }

    @Test
    public void testNestedQualifierFromModuleIdStaysDotSeparated() {
        String qualifier = SharedBuildLogicUtils.nestedQualifierFromModuleId(
                "com.example:build-logic-1:1.0.0");
        assertEquals(qualifier, "com.example.build-logic-1");
        assertFalse(qualifier.contains(":"), "Nested qualifiers must not introduce extra GAV colons");
    }

    @Test
    public void testSameNamedIncludesUnderDifferentParentsGetDistinctIds() {
        String consumer = "com.example:nested-app:1.0.0";
        String rootInclude = SharedBuildLogicUtils.buildQualifiedModuleId(
                consumer, "com.example", "build-logic-2", "1.0.0");
        String parent = SharedBuildLogicUtils.buildQualifiedModuleId(
                consumer, "com.example", "build-logic-1", "1.0.0");
        String nestedInclude = SharedBuildLogicUtils.buildQualifiedModuleId(
                parent, "com.example", "build-logic-2", "1.0.0");

        assertEquals(rootInclude, "com.example.nested-app:build-logic-2:1.0.0");
        assertEquals(parent, "com.example.nested-app:build-logic-1:1.0.0");
        assertEquals(nestedInclude, "com.example.nested-app.build-logic-1:build-logic-2:1.0.0");
        assertNotEquals(rootInclude, nestedInclude);
        assertEquals(nestedInclude.split(":").length, 3);
        assertEquals(SharedBuildLogicUtils.deployCoordinates(
                rootInclude, "com.example", "build-logic-2", "1.0.0"), rootInclude);
        assertEquals(SharedBuildLogicUtils.deployCoordinates(
                nestedInclude, "com.example", "build-logic-2", "1.0.0"), nestedInclude);
        assertTrue(SharedBuildLogicUtils.buildMavenArtifactPath(rootInclude, "build-logic-2-1.0.0.jar")
                .startsWith("com/example/nested-app/"));
        assertTrue(SharedBuildLogicUtils.buildMavenArtifactPath(nestedInclude, "build-logic-2-1.0.0.jar")
                .startsWith("com/example/nested-app/"));
    }

    @Test
    public void testDeployCoordinatesFallsBackToOwnGavWithoutNestedId() {
        assertEquals(SharedBuildLogicUtils.deployCoordinates(
                ":convention-plugins:unspecified", "com.example", "convention-plugins", "unspecified"),
                "com.example:convention-plugins:unspecified");
    }

    @Test
    public void testBuildArtifactPropertiesIncludeBuildCoordinates() {
        java.util.Map<String, String> props = SharedBuildLogicUtils.buildArtifactProperties(
                "nested-app-verified", "1789241203539", "1726180000000");
        assertEquals(props.get("build.name"), "nested-app-verified");
        assertEquals(props.get("build.number"), "1789241203539");
        assertEquals(props.get("build.timestamp"), "1726180000000");
    }

    @Test
    public void testShouldPublishMavenDescriptorKeepsPluginPomWhenFlagOn() {
        // jf gradle defaults publish.maven=false. Flag-on must still publish the consumer POM
        // unless the Gradle DSL set publishPom=false.
        assertTrue(SharedBuildLogicUtils.shouldPublishMavenDescriptor(false, null, true));
        assertTrue(SharedBuildLogicUtils.shouldPublishMavenDescriptor(null, null, true));
        assertFalse(SharedBuildLogicUtils.shouldPublishMavenDescriptor(false, false, true));
        assertTrue(SharedBuildLogicUtils.shouldPublishMavenDescriptor(false, true, true));
    }

    @Test
    public void testShouldPublishMavenDescriptorFlagOffUnchanged() {
        assertFalse(SharedBuildLogicUtils.shouldPublishMavenDescriptor(false, null, false));
        assertTrue(SharedBuildLogicUtils.shouldPublishMavenDescriptor(null, null, false));
        assertTrue(SharedBuildLogicUtils.shouldPublishMavenDescriptor(true, null, false));
        assertFalse(SharedBuildLogicUtils.shouldPublishMavenDescriptor(null, false, false));
    }
}
