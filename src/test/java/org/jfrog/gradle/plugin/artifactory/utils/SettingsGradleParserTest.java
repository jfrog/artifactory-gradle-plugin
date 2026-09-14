package org.jfrog.gradle.plugin.artifactory.utils;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertEqualsNoOrder;
import static org.testng.Assert.assertTrue;

/**
 * Groovy and Kotlin includeBuild syntax, comments, and settings-file auto-detection.
 */
class SettingsGradleParserTest {

    @DataProvider
    public Object[][] groovySnippets() {
        return new Object[][]{
                {"includeBuild 'build-logic'", new String[]{"build-logic"}},
                {"includeBuild('build-logic')", new String[]{"build-logic"}},
                {"includeBuild \"build-logic\"", new String[]{"build-logic"}},
                {"includeBuild('build-logic') { configureBuild { ... } }", new String[]{"build-logic"}},
                {"includeBuild  (  'build-logic'  )", new String[]{"build-logic"}},
                {"includeBuild '/usr/local/build-logic'", new String[]{"/usr/local/build-logic"}},
                {"includeBuild '../build-logic'", new String[]{"../build-logic"}},
                {"includeBuild './build-logic'", new String[]{"./build-logic"}},
                {"includeBuild 'my-build-logic-2'", new String[]{"my-build-logic-2"}},
                {"includeBuild 'my_build_logic'", new String[]{"my_build_logic"}},
                {"includeBuild '/usr/local/my-project_v2.0/build-logic'", new String[]{"/usr/local/my-project_v2.0/build-logic"}},
                {"includeBuild 'a'\ninclude 'app'\nincludeBuild 'b'", new String[]{"a", "b"}},
                {"includeBuild 'build-logic'\nincludeBuild 'build-logic'\nincludeBuild('build-logic')", new String[]{"build-logic"}},
                {"includeBuild 'a'\nincludeBuild \"b\"\nincludeBuild('c')", new String[]{"a", "b", "c"}},
                {"// includeBuild 'commented-out'\nincludeBuild 'build-logic'", new String[]{"build-logic"}},
                {"/*\nincludeBuild 'commented-in-block'\n*/\nincludeBuild 'build-logic'", new String[]{"build-logic"}},
                {"/* This is a\n   multi-line comment\n   includeBuild 'not-included'\n*/\nincludeBuild 'build-logic'", new String[]{"build-logic"}},
                {"include 'api'\ninclude 'web'", new String[]{}},
                {"", new String[]{}},
        };
    }

    @DataProvider
    public Object[][] kotlinSnippets() {
        return new Object[][]{
                {"includeBuild(\"build-logic\")", new String[]{"build-logic"}},
                {"includeBuild  (  \"build-logic\"  )", new String[]{"build-logic"}},
                {"includeBuild(\"/usr/local/build-logic\")", new String[]{"/usr/local/build-logic"}},
                {"includeBuild(\"../build-logic\")", new String[]{"../build-logic"}},
                {"includeBuild(\"a\")\ninclude(\"app\")\nincludeBuild(\"b\")", new String[]{"a", "b"}},
                {"// includeBuild(\"commented-out\")\nincludeBuild(\"build-logic\")", new String[]{"build-logic"}},
                {"/*\nincludeBuild(\"commented-in-block\")\n*/\nincludeBuild(\"build-logic\")", new String[]{"build-logic"}},
                {"includeBuild(\n    \"build-logic\"\n)", new String[]{"build-logic"}},
        };
    }

    @Test(dataProvider = "groovySnippets")
    void parseGroovy(String content, String[] expected) {
        assertEqualsNoOrder(SettingsGradleParser.parseGroovy(content).toArray(), expected);
    }

    @Test
    void parseGroovyNull() {
        assertTrue(SettingsGradleParser.parseGroovy(null).isEmpty());
    }

    @Test(dataProvider = "kotlinSnippets")
    void parseKotlin(String content, String[] expected) {
        assertEqualsNoOrder(SettingsGradleParser.parseKotlin(content).toArray(), expected);
    }

    @Test
    void parseSettingsFileDetectsDsl() throws IOException {
        Path groovy = Files.createTempFile("settings", ".gradle");
        Path kotlin = Files.createTempFile("settings", ".gradle.kts");
        try {
            Files.write(groovy, "includeBuild 'build-logic'".getBytes(StandardCharsets.UTF_8));
            Files.write(kotlin, "includeBuild(\"build-logic\")".getBytes(StandardCharsets.UTF_8));
            assertEquals(SettingsGradleParser.parseSettingsFile(groovy.toFile()), java.util.Collections.singletonList("build-logic"));
            assertEquals(SettingsGradleParser.parseSettingsFile(kotlin.toFile()), java.util.Collections.singletonList("build-logic"));
        } finally {
            Files.deleteIfExists(groovy);
            Files.deleteIfExists(kotlin);
        }
    }

    @Test
    void parseSettingsFileMissingOrNull() {
        assertTrue(SettingsGradleParser.parseSettingsFile(new File("/tmp/non-existent-file-xyz.gradle")).isEmpty());
        assertTrue(SettingsGradleParser.parseSettingsFile(null).isEmpty());
    }

}
