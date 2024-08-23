package org.jfrog.gradle.plugin.artifactory.utils;

import org.jfrog.build.api.util.NullLog;
import org.jfrog.build.client.ProxyConfiguration;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.build.extractor.clientConfiguration.client.artifactory.ArtifactoryManager;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.jfrog.gradle.plugin.artifactory.utils.DeployUtils.configureProxy;
import static org.testng.Assert.*;

public class DeployUtilsTest {
    private static final String artifactoryUrl = "https://acme.jfrog.io/artifactory";
    private static final String proxyUrl = "https://acme-proxy.jfrog.io";
    private static final int proxyPort = 1234;

    @DataProvider
    public Object[][] proxies() {
        return new Object[][]{
                {"", null, "", false},
                {proxyUrl, null, "", false},
                {"", proxyPort, "", false},
                {proxyUrl, proxyPort, "", true},
                {proxyUrl, proxyPort, "acme.jfrog.io", false},
                {proxyUrl, proxyPort, "acme2.jfrog.io", true},
        };
    }

    @Test(dataProvider = "proxies")
    void testConfigureProxy(String proxyUrl, Integer proxyPort, String noProxy, boolean expectedProxy) {
        // Configure proxy with the given parameters
        ArtifactoryClientConfiguration.ProxyHandler proxyHandler = createProxyHandler(proxyUrl, proxyPort, noProxy);
        ArtifactoryManager artifactoryManager = new ArtifactoryManager(artifactoryUrl, new NullLog());
        configureProxy(proxyHandler, artifactoryManager);

        // Verify the proxy configuration
        ProxyConfiguration actualProxyConfiguration = artifactoryManager.getProxyConfiguration();
        if (expectedProxy) {
            // Verify the proxy configuration
            assertNotNull(actualProxyConfiguration);
            assertEquals(actualProxyConfiguration.host, proxyUrl);
            assertEquals(actualProxyConfiguration.port, proxyPort);
        } else {
            // Verify no proxy configuration if no proxy is set or the host is in the no proxy list
            assertNull(actualProxyConfiguration);
        }
    }

    /**
     * Create a proxy handler with the given parameters.
     *
     * @param proxyUrl  The proxy URL
     * @param proxyPort The proxy port
     * @param noProxy   The no proxy
     * @return The created proxy handler.
     */
    private ArtifactoryClientConfiguration.ProxyHandler createProxyHandler(String proxyUrl, Integer proxyPort, String noProxy) {
        ArtifactoryClientConfiguration clientConfiguration = new ArtifactoryClientConfiguration(new NullLog());
        ArtifactoryClientConfiguration.ProxyHandler proxyHandler = clientConfiguration.proxy;
        proxyHandler.setHost(proxyUrl);
        proxyHandler.setPort(proxyPort);
        proxyHandler.setNoProxy(noProxy);
        return proxyHandler;
    }
}
