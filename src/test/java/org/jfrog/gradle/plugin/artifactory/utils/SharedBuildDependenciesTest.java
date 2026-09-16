package org.jfrog.gradle.plugin.artifactory.utils;

import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.ResolvableDependencies;
import org.gradle.api.artifacts.result.ResolutionResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.testng.annotations.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class SharedBuildDependenciesTest {

    @Test
    public void testParseDeclaredDependenciesReadsImplementationLines() {
        String buildScript =
                "dependencies {\n" +
                "    implementation 'com.google.code.gson:gson:2.9.0'\n" +
                "    api \"org.slf4j:slf4j-api:1.7.36\"\n" +
                "    testImplementation 'junit:junit:4.13.2'\n" +
                "}\n";

        List<String> gavs = SharedBuildDependencies.parseDeclaredDependencies(buildScript);

        assertEquals(gavs.size(), 2);
        assertTrue(gavs.contains("com.google.code.gson:gson:2.9.0"));
        assertTrue(gavs.contains("org.slf4j:slf4j-api:1.7.36"));
    }

    @Test
    public void testParseDeclaredDependenciesReadsKotlinParentheses() {
        String buildScript =
                "dependencies {\n" +
                "    implementation(\"com.fasterxml.jackson.core:jackson-databind:2.14.0\")\n" +
                "}\n";

        List<String> gavs = SharedBuildDependencies.parseDeclaredDependencies(buildScript);

        assertEquals(gavs.size(), 1);
        assertTrue(gavs.contains("com.fasterxml.jackson.core:jackson-databind:2.14.0"));
    }

    @Test
    public void testParseDeclaredDependenciesHandlesClassifiedNotation() {
        // Regression test: the version group used to allow ':', so gavParts() would read
        // "sources" as the version instead of "5.9.0".
        String buildScript =
                "dependencies {\n" +
                "    implementation 'org.junit:junit-jupiter:5.9.0:sources'\n" +
                "}\n";

        List<String> gavs = SharedBuildDependencies.parseDeclaredDependencies(buildScript);

        assertEquals(gavs.size(), 1);
        assertTrue(gavs.contains("org.junit:junit-jupiter:5.9.0"));
    }

    @Test
    public void testParseDeclaredDependenciesHandlesExtensionNotation() {
        String buildScript =
                "dependencies {\n" +
                "    implementation 'com.example:widget:1.2.3@aar'\n" +
                "}\n";

        List<String> gavs = SharedBuildDependencies.parseDeclaredDependencies(buildScript);

        assertEquals(gavs.size(), 1);
        assertTrue(gavs.contains("com.example:widget:1.2.3"));
    }

    @Test
    public void testFindComponentPrefersExactVersionMatchWhenGivenARealVersion() {
        ResolvedComponentResult older = mockComponent("com.example", "build-logic-1", "1.0.0");
        ResolvedComponentResult newer = mockComponent("com.example", "build-logic-1", "1.0.1");
        Configuration configuration = mockConfigurationWithComponents(older, newer);

        ResolvedComponentResult found = SharedBuildDependencies.findComponent(
                configuration, "com.example", "build-logic-1", "1.0.1");

        assertEquals(found, newer);
    }

    @Test
    public void testFindComponentFallsBackToFirstMatchWhenVersionIsNotReal() {
        ResolvedComponentResult first = mockComponent("com.example", "build-logic-1", "1.0.0");
        ResolvedComponentResult second = mockComponent("com.example", "build-logic-1", "1.0.1");
        Configuration configuration = mockConfigurationWithComponents(first, second);

        // "unspecified" and blank are not real versions - version-agnostic behavior is kept so a
        // shared build that only ever resolves to one version within a consumer build (the common
        // case) is unaffected by making this method version-aware.
        assertEquals(SharedBuildDependencies.findComponent(
                configuration, "com.example", "build-logic-1", "unspecified"), first);
        assertEquals(SharedBuildDependencies.findComponent(
                configuration, "com.example", "build-logic-1", null), first);
    }

    @Test
    public void testFindComponentFallsBackToFirstMatchWhenRequestedVersionIsNotResolved() {
        ResolvedComponentResult only = mockComponent("com.example", "build-logic-1", "1.0.0");
        Configuration configuration = mockConfigurationWithComponents(only);

        // Requested version was never actually resolved for this configuration - still return
        // the group:name match rather than nothing, matching the pre-existing version-agnostic
        // behavior for the common single-version case.
        assertEquals(SharedBuildDependencies.findComponent(
                configuration, "com.example", "build-logic-1", "9.9.9"), only);
    }

    private static ResolvedComponentResult mockComponent(String group, String name, String version) {
        ModuleVersionIdentifier module = mock(ModuleVersionIdentifier.class);
        when(module.getGroup()).thenReturn(group);
        when(module.getName()).thenReturn(name);
        when(module.getVersion()).thenReturn(version);
        ResolvedComponentResult component = mock(ResolvedComponentResult.class);
        when(component.getModuleVersion()).thenReturn(module);
        return component;
    }

    private static Configuration mockConfigurationWithComponents(ResolvedComponentResult... components) {
        Set<ResolvedComponentResult> all = new LinkedHashSet<>();
        for (ResolvedComponentResult component : components) {
            all.add(component);
        }
        ResolutionResult resolutionResult = mock(ResolutionResult.class);
        when(resolutionResult.getAllComponents()).thenReturn(all);
        ResolvableDependencies incoming = mock(ResolvableDependencies.class);
        when(incoming.getResolutionResult()).thenReturn(resolutionResult);
        Configuration configuration = mock(Configuration.class);
        when(configuration.getIncoming()).thenReturn(incoming);
        return configuration;
    }

    @Test
    public void testGradleScopeUsesConfigurationNames() {
        assertEquals(SharedBuildDependencies.gradleScopeFor("implementation"), "compileClasspath");
        assertEquals(SharedBuildDependencies.gradleScopeFor("api"), "compileClasspath");
        assertEquals(SharedBuildDependencies.gradleScopeFor("compileOnly"), "compileOnly");
        assertEquals(SharedBuildDependencies.gradleScopeFor("runtimeOnly"), "runtimeClasspath");
        assertEquals(SharedBuildDependencies.gradleScopeFor("testImplementation"), "testCompileClasspath");
    }

    @Test
    public void testPomScopeMapsToSharedBuildGradleScope() {
        assertEquals(SharedBuildDependencies.pomScopeToGradle(null), "compileClasspath");
        assertEquals(SharedBuildDependencies.pomScopeToGradle(""), "compileClasspath");
        assertEquals(SharedBuildDependencies.pomScopeToGradle("compile"), "compileClasspath");
        assertEquals(SharedBuildDependencies.pomScopeToGradle("runtime"), "runtimeClasspath");
        assertEquals(SharedBuildDependencies.pomScopeToGradle("test"), null);
        assertEquals(SharedBuildDependencies.pomScopeToGradle("import"), null);
        assertEquals(SharedBuildDependencies.pomScopeToGradle("provided"), null);
        assertEquals(SharedBuildDependencies.pomScopeToGradle("system"), null);
    }

    @Test
    public void testParsePomDependenciesKeepsRuntimeScope() {
        String pom =
                "<project><dependencies>" +
                "<dependency><groupId>com.fasterxml.jackson.core</groupId>" +
                "<artifactId>jackson-core</artifactId><version>2.14.0</version></dependency>" +
                "<dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId>" +
                "<version>1.7.36</version><scope>runtime</scope></dependency>" +
                "</dependencies></project>";

        List<String[]> entries = SharedBuildDependencies.parsePomDependenciesWithScopes(pom);

        assertEquals(entries.size(), 2);
        assertEquals(entries.get(0)[0], "com.fasterxml.jackson.core:jackson-core:2.14.0");
        assertEquals(entries.get(0)[1], "compileClasspath");
        assertEquals(entries.get(1)[0], "org.slf4j:slf4j-api:1.7.36");
        assertEquals(entries.get(1)[1], "runtimeClasspath");
    }

    @Test
    public void testConsumerTestClasspathsAreNotSharedBuildScopes() {
        assertTrue(SharedBuildDependencies.isConsumerClasspathNoise("testRuntimeClasspath"));
        assertTrue(SharedBuildDependencies.isConsumerClasspathNoise("testCompileClasspath"));
        assertTrue(SharedBuildDependencies.isConsumerClasspathNoise("testRuntimeElements"));
        assertFalse(SharedBuildDependencies.isConsumerClasspathNoise("compileClasspath"));
        assertFalse(SharedBuildDependencies.isConsumerClasspathNoise("runtimeClasspath"));
    }

    @Test
    public void testParsePomDependenciesIncludesTransitivesAndSkipsTestOptional() {
        String pom =
                "<project><dependencies>" +
                "<dependency><groupId>org.hamcrest</groupId><artifactId>hamcrest-core</artifactId><version>1.3</version></dependency>" +
                "<dependency><groupId>org.example</groupId><artifactId>optional-lib</artifactId><version>1.0</version><optional>true</optional></dependency>" +
                "<dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4.13.2</version><scope>test</scope></dependency>" +
                "</dependencies>" +
                "<build><plugins><plugin><dependencies>" +
                "<dependency><groupId>com.github.stephenc.wagon</groupId><artifactId>wagon-gitsite</artifactId><version>0.4.1</version></dependency>" +
                "</dependencies></plugin></plugins></build>" +
                "</project>";

        List<String> gavs = SharedBuildDependencies.parsePomCompileDependencies(pom);

        assertEquals(gavs.size(), 1);
        assertTrue(gavs.contains("org.hamcrest:hamcrest-core:1.3"));
    }

    @Test
    public void testParsePomDependenciesIncludesBomAndSkipsImport() {
        String pom =
                "<project><dependencies>" +
                "<dependency><groupId>com.fasterxml.jackson</groupId><artifactId>jackson-bom</artifactId>" +
                "<version>2.14.0</version><type>pom</type></dependency>" +
                "<dependency><groupId>org.example</groupId><artifactId>imported-bom</artifactId>" +
                "<version>1.0</version><type>pom</type><scope>import</scope></dependency>" +
                "<dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-core</artifactId>" +
                "<version>2.14.0</version></dependency>" +
                "</dependencies></project>";

        List<String> gavs = SharedBuildDependencies.parsePomCompileDependencies(pom);

        assertEquals(gavs.size(), 2);
        assertTrue(gavs.contains("com.fasterxml.jackson:jackson-bom:2.14.0"));
        assertTrue(gavs.contains("com.fasterxml.jackson.core:jackson-core:2.14.0"));
    }

    @Test
    public void testArtifactFileKeepsPomWhenNoJar() throws Exception {
        java.io.File pom = java.io.File.createTempFile("jackson-bom-2.14.0", ".pom");
        try {
            java.nio.file.Files.write(pom.toPath(), "<project/>".getBytes());
            java.io.File chosen = SharedBuildDependencies.artifactFileForDependency(
                    pom, "/tmp/does-not-exist-gradle-home", "com.fasterxml.jackson:jackson-bom:2.14.0");
            assertEquals(chosen.getName(), pom.getName());
        } finally {
            pom.delete();
        }
    }

    @Test
    public void testEnsurePublishedJarPacksClassesWhenNoJar() throws Exception {
        java.io.File dir = new java.io.File(System.getProperty("java.io.tmpdir"), "jfrog-pack-classes");
        java.io.File classes = new java.io.File(dir, "build/classes/java/main/com/example");
        assertTrue(classes.mkdirs() || classes.isDirectory());
        java.io.File classFile = new java.io.File(classes, "Logic3.class");
        java.nio.file.Files.write(classFile.toPath(), new byte[]{1, 2, 3, 4});
        try {
            java.io.File jar = SharedBuildLogicUtils.ensurePublishedJar(dir, "build-logic-3", "1.0.0");
            assertTrue(jar.isFile());
            assertEquals(jar.getName(), "build-logic-3-1.0.0.jar");
        } finally {
            java.io.File libs = new java.io.File(dir, "build/libs");
            java.io.File packed = new java.io.File(libs, "build-logic-3-1.0.0.jar");
            packed.delete();
            libs.delete();
            classFile.delete();
            classes.delete();
            new java.io.File(dir, "build/classes/java/main/com").delete();
            new java.io.File(dir, "build/classes/java/main").delete();
            new java.io.File(dir, "build/classes/java").delete();
            new java.io.File(dir, "build/classes").delete();
            new java.io.File(dir, "build").delete();
            dir.delete();
        }
    }

    @Test
    public void testFindCachedJarReadsGradleModuleCache() throws Exception {
        java.io.File home = new java.io.File(System.getProperty("java.io.tmpdir"), "jfrog-gradle-home");
        java.io.File jarDir = new java.io.File(home,
                "caches/modules-2/files-2.1/junit/junit/4.13.2/abc123");
        assertTrue(jarDir.mkdirs() || jarDir.isDirectory());
        java.io.File jar = new java.io.File(jarDir, "junit-4.13.2.jar");
        java.nio.file.Files.write(jar.toPath(), new byte[]{9, 8, 7});
        try {
            assertEquals(
                    SharedBuildDependencies.findCachedJar(home.getAbsolutePath(), "junit:junit:4.13.2").getName(),
                    "junit-4.13.2.jar");
        } finally {
            jar.delete();
            jarDir.delete();
            java.io.File version = jarDir.getParentFile();
            version.delete();
            version.getParentFile().delete();
            version.getParentFile().getParentFile().delete();
            new java.io.File(home, "caches/modules-2/files-2.1").delete();
            new java.io.File(home, "caches/modules-2").delete();
            new java.io.File(home, "caches").delete();
            home.delete();
        }
    }

    @Test
    public void testEnsurePublishedMetadataWritesPomAndModuleWhenMissing() throws Exception {
        java.io.File dir = new java.io.File(System.getProperty("java.io.tmpdir"), "jfrog-pub-meta");
        assertTrue(dir.mkdirs() || dir.isDirectory());
        try {
            SharedBuildLogicUtils.ensurePublishedMetadata(
                    dir, "com.example.nested-app", "buildSrc", "unspecified",
                    java.util.Collections.singletonList("org.slf4j:slf4j-api:1.7.36"));
            java.io.File pom = new java.io.File(dir, "build/publications/generated/pom-default.xml");
            java.io.File module = new java.io.File(dir, "build/publications/generated/module.json");
            assertTrue(pom.isFile());
            assertTrue(module.isFile());
            String pomText = new String(java.nio.file.Files.readAllBytes(pom.toPath()));
            assertTrue(pomText.contains("<groupId>com.example.nested-app</groupId>"));
            assertTrue(pomText.contains("<artifactId>buildSrc</artifactId>"));
            assertTrue(pomText.contains("<artifactId>slf4j-api</artifactId>"));
            String moduleText = new String(java.nio.file.Files.readAllBytes(module.toPath()));
            assertTrue(moduleText.contains("\"module\": \"buildSrc\""));
            assertTrue(moduleText.contains("buildSrc-unspecified.jar"));
        } finally {
            java.io.File gen = new java.io.File(dir, "build/publications/generated");
            new java.io.File(gen, "pom-default.xml").delete();
            new java.io.File(gen, "module.json").delete();
            gen.delete();
            new java.io.File(dir, "build/publications").delete();
            new java.io.File(dir, "build").delete();
            dir.delete();
        }
    }

    @Test
    public void testFirstBuiltJarFindsJarInLibs() throws Exception {
        java.io.File dir = new java.io.File(System.getProperty("java.io.tmpdir"), "jfrog-first-jar");
        java.io.File libs = new java.io.File(dir, "build/libs");
        assertTrue(libs.mkdirs() || libs.isDirectory());
        java.io.File jar = new java.io.File(libs, "build-logic-1-1.0.0.jar");
        assertTrue(jar.createNewFile() || jar.isFile());
        try {
            assertEquals(SharedBuildLogicUtils.firstBuiltJar(dir).getName(), "build-logic-1-1.0.0.jar");
        } finally {
            jar.delete();
            libs.delete();
            new java.io.File(dir, "build").delete();
            dir.delete();
        }
    }
}
