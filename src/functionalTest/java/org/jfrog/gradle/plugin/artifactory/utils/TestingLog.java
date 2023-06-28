package org.jfrog.gradle.plugin.artifactory.utils;

import org.jfrog.build.api.util.Log;
import org.testng.Reporter;

public class TestingLog implements Log {

    public enum LogLevel {
        ERROR(0),
        WARN(1),
        INFO(1),
        DEBUG(2);

        public final int value;

        LogLevel(int value) {
            this.value = value;
        }
    }

    @Override
    public void debug(String message) {
        Reporter.log(message, LogLevel.DEBUG.value, true);
    }

    @Override
    public void info(String message) {
        Reporter.log(message, LogLevel.INFO.value, true);
    }

    @Override
    public void warn(String message) {
        Reporter.log(message, LogLevel.WARN.value, true);
    }

    @Override
    public void error(String message) {
        Reporter.log(message, LogLevel.ERROR.value, true);
    }

    @Override
    public void error(String message, Throwable e) {
        Reporter.log(message, LogLevel.ERROR.value, true);
    }
}
