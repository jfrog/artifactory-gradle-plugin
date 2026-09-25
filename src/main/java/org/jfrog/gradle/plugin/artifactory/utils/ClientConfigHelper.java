package org.jfrog.gradle.plugin.artifactory.utils;

import org.gradle.api.logging.Logging;
import org.jfrog.build.api.util.Log;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Helper to snapshot and restore ArtifactoryClientConfiguration for configuration cache compatibility.
 * ArtifactoryClientConfiguration contains a non-serializable Log field, so we snapshot its properties
 * (which are all Strings) and restore from them.
 */
public class ClientConfigHelper {

    private static final Log RESTORE_LOG = new GradleClientLogger(Logging.getLogger(ClientConfigHelper.class));

    public static Map<String, String> snapshotConfig(ArtifactoryClientConfiguration config) {
        return new HashMap<>(config.getAllProperties());
    }

    public static ArtifactoryClientConfiguration restoreConfig(Map<String, String> snapshot) {
        // Restore runs at execution time on the hot path; surface any warnings/errors through the real
        // Gradle logger instead of swallowing them silently.
        ArtifactoryClientConfiguration config = new ArtifactoryClientConfiguration(RESTORE_LOG);
        Properties props = new Properties();
        props.putAll(snapshot);
        config.fillFromProperties(props);
        return config;
    }
}
