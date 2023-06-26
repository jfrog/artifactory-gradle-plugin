package org.jfrog.gradle.plugin.artifactory.extractor.details;

import org.apache.commons.lang3.StringUtils;
import org.gradle.api.GradleException;

import javax.xml.namespace.QName;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Describes an Artifact information to be published
 */
public class PublishArtifactInfo implements Comparable<PublishArtifactInfo> {

    private final String name;
    private final String extension;
    private final String type;
    private final String classifier;
    private final Map<QName, String> extraInfo;
    private final File file;

    public PublishArtifactInfo(String name, String extension, String type, String classifier, File file) {
        this(name, extension, type, classifier, null, file);
    }

    public PublishArtifactInfo(String name, String extension, String type, String classifier,
                               Map<QName, String> extraInfo, File file) {
        this.name = name;
        this.extension = extension;
        this.type = type;
        this.classifier = classifier;
        this.extraInfo = extraInfo;
        this.file = file;
    }

    public String getName() {
        return name;
    }

    public String getExtension() {
        return extension;
    }

    public String getType() {
        return type;
    }

    public String getClassifier() {
        return classifier;
    }

    /**
     * Get artifact raw extra information.
     */
    public Map<QName, String> getExtraInfo() {
        return extraInfo;
    }

    /**
     * Get the artifact (and module) processed and combined extra information.
     * 1. adds 'classifier' extra information to the map
     * 2. adds the raw artifact extra information and remove duplications of keys (localPart in the QName are the same).
     * @return a map of artifact extra information that can be used to create the artifact path for DeployDetails.
     */
    public Map<String, String> getExtraTokens() {
        Map<String, String> extraTokens = new HashMap<>();
        if (StringUtils.isNotBlank(getClassifier())) {
            extraTokens.put("classifier", getClassifier());
        }
        Map<QName, String> extraInfo = getExtraInfo();
        if (extraInfo != null) {
            // extract the local part of the QName and use it as an expected string key, verify no duplications.
            for (Map.Entry<QName, String> extraToken : extraInfo.entrySet()) {
                String key = extraToken.getKey().getLocalPart();
                if (extraTokens.containsKey(key)) {
                    throw new GradleException("Found duplicated extra info key defined '" + key + "'.");
                }
                extraTokens.put(key, extraToken.getValue());
            }
        }
        return extraTokens;
    }

    public File getFile() {
        return file;
    }

    public int compareTo(PublishArtifactInfo other) {
        return file.compareTo(other.getFile());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        PublishArtifactInfo info = (PublishArtifactInfo) o;
        return file.equals(info.file);
    }

    @Override
    public int hashCode() {
        return file.hashCode();
    }
}
