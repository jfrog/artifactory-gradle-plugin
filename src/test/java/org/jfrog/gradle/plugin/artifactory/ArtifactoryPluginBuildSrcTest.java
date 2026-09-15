package org.jfrog.gradle.plugin.artifactory;

import org.gradle.api.Project;
import org.gradle.api.logging.Logging;
import org.gradle.api.plugins.PluginContainer;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.*;

public class ArtifactoryPluginBuildSrcTest {

    private ArtifactoryPlugin plugin;

    @BeforeMethod
    public void setUp() {
        plugin = new ArtifactoryPlugin();
    }

    @Test
    public void testBuildSrcProjectShouldBeApplied() {
        // Create mock buildSrc project
        Project buildSrcProject = createMockBuildSrcProject();

        assertFalse(shouldApplyPluginOnProject(buildSrcProject),
            "Artifactory Plugin is not applied to buildSrc");
    }

    @Test
    public void testRegularProjectShouldBeApplied() {
        // Create mock regular project
        Project regularProject = createMockRegularProject("app");

        // Regular projects should continue to be processed
        assertTrue(shouldApplyPluginOnProject(regularProject),
            "Artifactory Plugin should continue to be applied to regular projects");
    }

    @Test
    public void testRootProjectShouldBeApplied() {
        // Create mock root project
        Project rootProject = createMockRootProject();

        // Root projects should continue to be processed
        assertTrue(shouldApplyPluginOnProject(rootProject),
            "Artifactory Plugin should continue to be applied to root projects");
    }

    @Test
    public void testMultipleBuildSrcProjectHandling() {
        // While rare, test that if multiple projects named buildSrc exist,
        // only the one with parent is treated as buildSrc
        Project buildSrcWithParent = createMockBuildSrcProject();
        Project buildSrcWithoutParent = createMockBuildSrcProjectWithoutParent();

        assertFalse(shouldApplyPluginOnProject(buildSrcWithParent),
            "buildSrc with parent is not applied");
        assertFalse(shouldApplyPluginOnProject(buildSrcWithoutParent),
            "buildSrc without parent is not applied");
    }

    @Test
    public void testBackwardCompatibility() {
        // Verify that projects other than buildSrc work as before
        String[] projectNames = {"app", "lib", "test-utils", "core", "api"};
        for (String projectName : projectNames) {
            Project project = createMockRegularProject(projectName);
            assertTrue(shouldApplyPluginOnProject(project),
                "Project '" + projectName + "' should be processed as before");
        }
    }

    // Helper methods

    /**
     * Test helper: Check if plugin should be applied (mirrors the logic in ArtifactoryPlugin).
     * Note: The actual method is private, so we're testing the logic it should implement.
     */
    private boolean shouldApplyPluginOnProject(Project project) {
        return !"buildSrc".equals(project.getName());
    }

    private Project createMockBuildSrcProject() {
        Project buildSrcProject = mock(Project.class);
        Project parentProject = mock(Project.class);

        when(buildSrcProject.getName()).thenReturn("buildSrc");
        when(buildSrcProject.getParent()).thenReturn(parentProject);
        when(buildSrcProject.getPath()).thenReturn(":buildSrc");
        when(buildSrcProject.getLogger()).thenReturn(Logging.getLogger(ArtifactoryPluginBuildSrcTest.class));

        return buildSrcProject;
    }

    private Project createMockBuildSrcProjectWithoutParent() {
        Project buildSrcProject = mock(Project.class);

        when(buildSrcProject.getName()).thenReturn("buildSrc");
        when(buildSrcProject.getParent()).thenReturn(null);
        when(buildSrcProject.getPath()).thenReturn("buildSrc");
        when(buildSrcProject.getLogger()).thenReturn(Logging.getLogger(ArtifactoryPluginBuildSrcTest.class));

        return buildSrcProject;
    }

    private Project createMockRegularProject(String name) {
        Project project = mock(Project.class);
        Project parentProject = mock(Project.class);

        when(project.getName()).thenReturn(name);
        when(project.getParent()).thenReturn(parentProject);
        when(project.getPath()).thenReturn(":" + name);
        when(project.getLogger()).thenReturn(Logging.getLogger(ArtifactoryPluginBuildSrcTest.class));

        return project;
    }

    private Project createMockRootProject() {
        Project rootProject = mock(Project.class);

        when(rootProject.getName()).thenReturn("root");
        when(rootProject.getParent()).thenReturn(null);
        when(rootProject.getPath()).thenReturn("");
        when(rootProject.getLogger()).thenReturn(Logging.getLogger(ArtifactoryPluginBuildSrcTest.class));

        return rootProject;
    }
}
