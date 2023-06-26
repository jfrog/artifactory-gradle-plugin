package org.jfrog.gradle.plugin.artifactory;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.UnknownConfigurationException;
import org.gradle.api.logging.Logging;
import org.gradle.internal.metaobject.DynamicInvokeResult;
import org.jfrog.build.extractor.clientConfiguration.ArtifactSpec;
import org.jfrog.build.extractor.clientConfiguration.ArtifactSpecs;
import org.jfrog.gradle.plugin.artifactory.config.PropertiesConfig;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;

public class PropertiesConfigTest {

    private PropertiesConfig propertiesConfig;

    private ConfigurationContainer configurationContainer;

    private final static String VALID_ARTIFACT_NOTATION = "org.example:my-artifact:1.0.0:test@jar";

    @BeforeMethod
    public void setUp() {
        configurationContainer = mock(ConfigurationContainer.class);
        Project mockProject = mock(Project.class);
        when(mockProject.getLogger()).thenReturn(Logging.getLogger(PropertiesConfigTest.class));
        when(mockProject.getConfigurations()).thenReturn(configurationContainer);

        propertiesConfig = new PropertiesConfig(mockProject);
    }

    @Test
    public void testInvokeMethodValidScope() {
        Map<Object, Object> arguments = new HashMap<>();
        arguments.put("key1", "val1");
        arguments.put("key2", "val2");
        Object[] args = { arguments, VALID_ARTIFACT_NOTATION };
        String scope = "configName";
        // Add mock config scope to project
        when(configurationContainer.getByName(scope)).thenReturn(null);
        // Invoke method on valid config scope to add properties
        Object result = propertiesConfig.invokeMethod(scope, args);
        // Validate scoped properties is added.
        assertEquals(DynamicInvokeResult.found(), result);
        assertProperties(arguments, scope);
    }

    @Test
    public void testInvokeMethodAllScopes() {
        Map<Object, Object> arguments = new HashMap<>();
        arguments.put("key1", "val1");
        arguments.put("key2", "val2");
        Object[] args = { arguments, VALID_ARTIFACT_NOTATION };
        // Invoke method on valid config scope to add properties
        Object result = propertiesConfig.invokeMethod(ArtifactSpec.CONFIG_ALL, args);
        // Validate scoped properties is added.
        assertEquals(DynamicInvokeResult.found(), result);
        assertProperties(arguments, "*");
    }

    @Test
    public void testInvokeMethodUnknownConfigurationScope() {
        Map<Object, Object> arguments = new HashMap<>();
        arguments.put("key1", "val1");
        arguments.put("key2", "val2");
        Object[] args = { arguments, VALID_ARTIFACT_NOTATION };
        String scope = "unknownConfigName";
        // Add mock of expected unknown config scope to project
        when(configurationContainer.getByName(scope))
                .thenThrow(UnknownConfigurationException.class);
        // Invoke method on valid config scope to add properties
        Object result = propertiesConfig.invokeMethod(scope, args);
        // Validate scoped properties is added.
        assertEquals(DynamicInvokeResult.found(), result);
        assertProperties(arguments, scope);
    }

    private void assertProperties(Map<Object, Object> expectedProperties, String... expectedScopes) {
        ArtifactSpecs artifactSpecs = propertiesConfig.getArtifactSpecs();
        assertEquals(expectedScopes.length, artifactSpecs.size());
        for (String expectedScope : expectedScopes) {
            List<ArtifactSpec> scopes = artifactSpecs.stream().filter(artifactSpec -> expectedScope.equals(artifactSpec.getConfiguration())).collect(Collectors.toList());
            assertEquals(scopes.size(), 1);
            ArtifactSpec spec = scopes.get(0);
            assertEquals(expectedProperties, spec.getProperties());
        }
    }


    @Test(expectedExceptions = GradleException.class)
    public void testInvokeMethodInvalidArgumentsNoArtifactNotation() {
        Map<Object, Object> arguments = new HashMap<>();
        arguments.put("key1", "val1");
        Object[] args = { arguments };
        propertiesConfig.invokeMethod("configName", args);
    }

    @Test(expectedExceptions = GradleException.class)
    public void testInvokeMethodInvalidArgumentsNoProperties() {
        Object[] args = { VALID_ARTIFACT_NOTATION };
        propertiesConfig.invokeMethod("configName", args);
    }

}