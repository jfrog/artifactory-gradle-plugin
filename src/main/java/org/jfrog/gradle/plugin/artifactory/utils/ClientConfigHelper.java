package org.jfrog.gradle.plugin.artifactory.utils;

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

    public static Map<String, String> snapshotConfig(ArtifactoryClientConfiguration config) {
        return new HashMap<>(config.getAllProperties());
    }

    public static ArtifactoryClientConfiguration restoreConfig(Map<String, String> snapshot) {
        ArtifactoryClientConfiguration config = new ArtifactoryClientConfiguration(new NoOpLog());
        Properties props = new Properties();
        props.putAll(snapshot);
        config.fillFromProperties(props);
        return config;
    }

    private static class NoOpLog implements Log {
        @Override
        public void debug(String message) {
        }

        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message) {
        }

        @Override
        public void error(String message) {
        }

        @Override
        public void error(String message, Throwable e) {
        }
    }
}
