package org.jfrog.gradle.plugin.artifactory.utils;

import org.jfrog.build.api.util.Log;
import org.testng.Reporter;

public class TestingLog implements Log {

    private static final int DEBUG_LEVEL = 2;
    private static final int INFO_LEVEL = 1;
    private static final int WARN_LEVEL = 1;
    private static final int ERROR_LEVEL = 0;

    @Override
    public void debug(String message) {
        Reporter.log(message, DEBUG_LEVEL, true);
    }

    @Override
    public void info(String message) {
        Reporter.log(message, INFO_LEVEL, true);
    }

    @Override
    public void warn(String message) {
        Reporter.log(message, WARN_LEVEL, true);
    }

    @Override
    public void error(String message) {
        Reporter.log(message, ERROR_LEVEL, true);
    }

    @Override
    public void error(String message, Throwable e) {
        Reporter.log(message, ERROR_LEVEL, true);
    }
}
