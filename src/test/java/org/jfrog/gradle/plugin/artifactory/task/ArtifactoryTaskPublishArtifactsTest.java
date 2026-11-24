package org.jfrog.gradle.plugin.artifactory.task;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.jfrog.gradle.plugin.artifactory.ArtifactoryPlugin;
import org.jfrog.gradle.plugin.artifactory.dsl.ArtifactoryPluginConvention;
import org.jfrog.gradle.plugin.artifactory.utils.ExtensionsUtils;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

/**
 * Verifies that {@code publishArtifacts} set on the {@link ArtifactoryTask} (for example in {@code defaults {}})
 * reaches the publisher configuration that the deploy step reads (RTECO-2747).
 */
public class ArtifactoryTaskPublishArtifactsTest {
    private static final String CONTEXT_URL = "http://localhost:8081/artifactory";

    private static Project createProject(Project parent, String name) {
        ProjectBuilder builder = ProjectBuilder.builder().withName(name);
        if (parent != null) {
            builder.withParent(parent);
        }
        Project project = builder.build();
        project.getPlugins().apply("java");
        project.getPlugins().apply("maven-publish");
        return project;
    }

    private static void applyPluginAndConfigure(Project project, Object defaultsPublishArtifacts) {
        project.getPlugins().apply(ArtifactoryPlugin.class);
        ArtifactoryPluginConvention extension = ExtensionsUtils.getArtifactoryExtension(project);
        extension.publish(publisher -> {
            publisher.setContextUrl(CONTEXT_URL);
            publisher.repository(repository -> repository.setRepoKey("libs-release-local"));
            if (defaultsPublishArtifacts != null) {
                publisher.defaults(task -> task.setPublishArtifacts(defaultsPublishArtifacts));
            }
        });
    }

    private static ArtifactoryTask evaluate(Project project) {
        ArtifactoryTask task = (ArtifactoryTask) project.getTasks().getByName("artifactoryPublish");
        task.evaluateTask();
        return task;
    }

    @DataProvider
    public Object[][] defaultsValues() {
        return new Object[][]{
                // defaults{} value, expected value in the publisher that the deploy step reads
                {null, true},   // Not configured: deploy as before
                {true, true},   // Explicit true: deploy as before
                {"true", true}, // The setter accepts any object (String): deploy as before
                {false, false}, // RTECO-2747: do not deploy
                {"false", false},
        };
    }

    @Test(dataProvider = "defaultsValues")
    public void defaultsPublishArtifactsReachesPublisher(Object defaultsValue, boolean expectedPublishArtifacts) {
        Project project = createProject(null, "single");
        applyPluginAndConfigure(project, defaultsValue);

        ArtifactoryTask task = evaluate(project);

        assertEquals(task.getPublisherFromSnapshot().isPublishArtifacts(), Boolean.valueOf(expectedPublishArtifacts));
    }

    @Test
    public void defaultsFalseKeepsOtherPublisherSettings() {
        Project project = createProject(null, "settings");
        applyPluginAndConfigure(project, false);

        ArtifactoryTask task = evaluate(project);

        // Only publishArtifacts changes. Build info publishing and the repository settings stay as configured.
        assertEquals(task.getPublisherFromSnapshot().getContextUrl(), CONTEXT_URL);
        assertEquals(task.getPublisherFromSnapshot().getRepoKey(), "libs-release-local");
        assertTrue(task.getPublisherFromSnapshot().isPublishBuildInfo());
        assertFalse(task.getPublisherFromSnapshot().isPublishArtifacts());
    }

    @Test
    public void defaultsTrueDoesNotOverrideDisabledProperty() {
        // A CI server or a "-Dartifactory.publish.artifacts=false" property already disabled the deployment.
        // An old build script with an explicit "publishArtifacts = true" must not start deploying.
        Project project = createProject(null, "property");
        applyPluginAndConfigure(project, true);
        ExtensionsUtils.getArtifactoryExtension(project).getClientConfig().publisher.setPublishArtifacts(false);

        ArtifactoryTask task = evaluate(project);

        assertFalse(task.getPublisherFromSnapshot().isPublishArtifacts());
    }

    @Test
    public void defaultsFalseWithPropertyFalse() {
        Project project = createProject(null, "both");
        applyPluginAndConfigure(project, false);
        ExtensionsUtils.getArtifactoryExtension(project).getClientConfig().publisher.setPublishArtifacts(false);

        ArtifactoryTask task = evaluate(project);

        assertFalse(task.getPublisherFromSnapshot().isPublishArtifacts());
    }

    @Test
    public void taskLevelFlagDoesNotLeakToOtherProjects() {
        // Sub-projects without their own publisher share the root extension. Disabling the deployment
        // on one task must not change the shared client configuration or any other project.
        Project root = createProject(null, "root");
        Project disabled = createProject(root, "disabled");
        Project enabled = createProject(root, "enabled");
        applyPluginAndConfigure(root, null);
        disabled.getPlugins().apply(ArtifactoryPlugin.class);
        enabled.getPlugins().apply(ArtifactoryPlugin.class);

        ArtifactoryTask disabledTask = (ArtifactoryTask) disabled.getTasks().getByName("artifactoryPublish");
        disabledTask.setPublishArtifacts(false);
        evaluate(disabled);
        ArtifactoryTask enabledTask = evaluate(enabled);
        ArtifactoryTask rootTask = evaluate(root);

        assertFalse(disabledTask.getPublisherFromSnapshot().isPublishArtifacts());
        assertTrue(enabledTask.getPublisherFromSnapshot().isPublishArtifacts());
        assertTrue(rootTask.getPublisherFromSnapshot().isPublishArtifacts());
        assertTrue(ExtensionsUtils.getArtifactoryExtension(root).getClientConfig().publisher.isPublishArtifacts());
    }

    @Test
    public void defaultsFalseAppliesToAllProjects() {
        Project root = createProject(null, "root");
        Project sub = createProject(root, "sub");
        applyPluginAndConfigure(root, false);
        sub.getPlugins().apply(ArtifactoryPlugin.class);

        ArtifactoryTask subTask = evaluate(sub);
        ArtifactoryTask rootTask = evaluate(root);

        assertFalse(subTask.getPublisherFromSnapshot().isPublishArtifacts());
        assertFalse(rootTask.getPublisherFromSnapshot().isPublishArtifacts());
    }

    @Test
    public void defaultsFalseOverridesDeprecatedKeyWrittenByCli() {
        // JFrog CLI writes both "publish.artifacts" and the deprecated "artifactory.publish.artifacts" as true.
        // The deprecated key takes precedence when read, so it must not hide the value of defaults{}.
        Project project = createProject(null, "cli");
        applyPluginAndConfigure(project, false);
        ExtensionsUtils.getArtifactoryExtension(project).getClientConfig().getAllProperties().put("artifactory.publish.artifacts", "true");
        ExtensionsUtils.getArtifactoryExtension(project).getClientConfig().getAllProperties().put("publish.artifacts", "true");

        ArtifactoryTask task = evaluate(project);

        assertFalse(task.getPublisherFromSnapshot().isPublishArtifacts());
        // The deprecated key is read first, so the task snapshot must hold it with the value false.
        assertEquals(task.getConfigSnapshot().get("artifactory.publish.artifacts"), "false");
        assertEquals(task.getConfigSnapshot().get("publish.artifacts"), "false");
    }

    @Test
    public void defaultsTrueKeepsDeprecatedKeyFalse() {
        Project project = createProject(null, "cliFalse");
        applyPluginAndConfigure(project, true);
        ExtensionsUtils.getArtifactoryExtension(project).getClientConfig().getAllProperties().put("artifactory.publish.artifacts", "false");

        ArtifactoryTask task = evaluate(project);

        assertFalse(task.getPublisherFromSnapshot().isPublishArtifacts());
    }

    @Test
    public void defaultsTrueKeepsDeprecatedKeyTrue() {
        Project project = createProject(null, "cliTrue");
        applyPluginAndConfigure(project, true);
        ExtensionsUtils.getArtifactoryExtension(project).getClientConfig().getAllProperties().put("artifactory.publish.artifacts", "true");

        ArtifactoryTask task = evaluate(project);

        assertTrue(task.getPublisherFromSnapshot().isPublishArtifacts());
    }

    @Test
    public void defaultsTrueAddsNoDeprecatedKey() {
        // Explicit true with no deprecated key in the config: the task value stays true and no key is added.
        Project project = createProject(null, "noKey");
        applyPluginAndConfigure(project, true);

        ArtifactoryTask task = evaluate(project);

        assertTrue(task.getPublisherFromSnapshot().isPublishArtifacts());
        assertNull(task.getConfigSnapshot().get("artifactory.publish.artifacts"));
    }
}
