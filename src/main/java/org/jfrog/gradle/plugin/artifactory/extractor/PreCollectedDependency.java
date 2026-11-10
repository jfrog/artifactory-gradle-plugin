package org.jfrog.gradle.plugin.artifactory.extractor;

import java.util.Set;

/**
 * Serializable record of a single resolved dependency for one configuration, captured
 * at execution time via the lazy resolution-result provider so it survives a
 * Gradle configuration-cache hit.
 */
public class PreCollectedDependency {
    private final String id;
    private final String type;
    private final Set<String> scopes;
    private final String md5;
    private final String sha1;
    private final String sha256;
    private final String[][] requestedBy;

    public PreCollectedDependency(String id, String type, Set<String> scopes,
                                  String md5, String sha1, String sha256,
                                  String[][] requestedBy) {
        this.id = id;
        this.type = type;
        this.scopes = scopes;
        this.md5 = md5;
        this.sha1 = sha1;
        this.sha256 = sha256;
        this.requestedBy = requestedBy;
    }

    public String getId() { return id; }
    public String getType() { return type; }
    public Set<String> getScopes() { return scopes; }
    public String getMd5() { return md5; }
    public String getSha1() { return sha1; }
    public String getSha256() { return sha256; }
    public String[][] getRequestedBy() { return requestedBy; }
}
