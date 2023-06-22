package org.jfrog.gradle.plugin.artifactory.config;

import groovy.lang.GroovyObjectSupport;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.UnknownConfigurationException;
import org.gradle.internal.metaobject.DynamicInvokeResult;
import org.jfrog.build.extractor.clientConfiguration.ArtifactSpec;
import org.jfrog.build.extractor.clientConfiguration.ArtifactSpecs;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * DSL object that holds a properties configurations for specific scope.
 * Expected format of entry in the closure is: configName artifactSpec, key1:val1, key2:val2...
 * This object is defined at the user build script ander 'artifactoryPublish' / 'defaults' closure in groovy.
 */
public class PropertiesConfig extends GroovyObjectSupport {

    private final ArtifactSpecs artifactSpecs = new ArtifactSpecs();
    private final Project project;

    public PropertiesConfig(Project project) {
        this.project = project;
    }

    @Override
    public Object invokeMethod(String name, Object args) {
        Object[] arguments = (Object[]) args;
        if (arguments.length != 2 || !(arguments[0] instanceof Map<?, ?>)) {
            throw new GradleException("Invalid artifact properties spec: " + name + ", " + Arrays.toString(arguments) + ".\nExpected: configName artifactSpec, key1:val1, key2:val2");
        }

        if (!ArtifactSpec.CONFIG_ALL.equals(name)) {
            try {
                project.getConfigurations().getByName(name);
            } catch (UnknownConfigurationException e) {
                project.getLogger().info("Artifactory plugin: configuration '{}' not found in project '{}'", name, project.getPath());
            }
        }

        Map<String, String> props = convertToMap((Map<?, ?>)arguments[0]);
        String artifactNotation = arguments[1].toString();

        ArtifactSpec spec = ArtifactSpec.builder().artifactNotation(artifactNotation).configuration(name).properties(props).build();
        artifactSpecs.add(spec);

        return DynamicInvokeResult.found();
    }

    private Map<String, String> convertToMap(Map<?, ?> inputMap) {
        Map<String, String> resultMap = new HashMap<>();
        for (Map.Entry<?, ?> entry : inputMap.entrySet()) {
            String key = String.valueOf(entry.getKey());
            String value = String.valueOf(entry.getValue());
            resultMap.put(key, value);
        }
        return resultMap;
    }

    public ArtifactSpecs getArtifactSpecs() {
        return artifactSpecs;
    }
}
