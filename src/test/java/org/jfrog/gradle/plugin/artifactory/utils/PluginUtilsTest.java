package org.jfrog.gradle.plugin.artifactory.utils;

import org.gradle.api.GradleException;
import org.gradle.api.invocation.Gradle;
import org.testng.annotations.Test;

import static org.jfrog.gradle.plugin.artifactory.utils.PluginUtils.assertGradleVersionSupported;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

public class PluginUtilsTest {

    @Test
    public void testAssertGradleVersionSupported() {
        Gradle gradle = mock(Gradle.class);

        // Assert 6.0 not supported
        when(gradle.getGradleVersion()).thenReturn("6.0");
        assertThrows(GradleException.class, () -> assertGradleVersionSupported(gradle));

        // Assert 6.8 not supported
        when(gradle.getGradleVersion()).thenReturn("6.8");
        assertThrows(GradleException.class, () -> assertGradleVersionSupported(gradle));

        // Assert 6.8.1 supported
        when(gradle.getGradleVersion()).thenReturn("6.8.1");
        assertGradleVersionSupported(gradle);

        // Assert 7.0 supported
        when(gradle.getGradleVersion()).thenReturn("7.0");
        assertGradleVersionSupported(gradle);
    }
}