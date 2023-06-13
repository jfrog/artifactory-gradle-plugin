package org.jfrog.gradle.plugin.artifactory.utils;

import org.gradle.api.logging.LogLevel;
import org.gradle.api.logging.Logger;
import org.jfrog.build.api.util.Log;

/**
 * Logger that is to be used for the HTTP client when using Gradle.
 */
public class GradleClientLogger implements Log {

    private Logger logger;

    public GradleClientLogger(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void debug(String message) {
        this.logger.log(LogLevel.DEBUG, message);
    }

    @Override
    public void info(String message) {
        this.logger.log(LogLevel.LIFECYCLE, message);
    }

    @Override
    public void warn(String message) {
        this.logger.log(LogLevel.WARN, message);
    }

    @Override
    public void error(String message) {
        this.logger.log(LogLevel.ERROR, message);
    }

    @Override
    public void error(String message, Throwable e) {
        this.logger.log(LogLevel.ERROR, message, e);
    }
}
