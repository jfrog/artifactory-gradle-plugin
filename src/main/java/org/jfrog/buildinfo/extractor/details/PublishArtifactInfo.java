package org.jfrog.buildinfo.extractor.details;

import org.apache.commons.lang3.StringUtils;
import org.gradle.api.GradleException;
import org.gradle.api.artifacts.PublishArtifact;

import javax.xml.namespace.QName;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Describes an Artifact information to be published
 */
public class PublishArtifactInfo  implements Comparable<PublishArtifactInfo> {

    private final String name;
    private final String extension;
    private final String type;
    private final String classifier;
    private final Map<QName, String> extraInfo;
    private final File file;

    public PublishArtifactInfo(PublishArtifact artifact) {
        this.name = artifact.getName();
        this.extension = artifact.getExtension();
        this.type = artifact.getType();
        this.classifier = artifact.getClassifier();
        this.extraInfo = null;
        this.file = artifact.getFile();
    }

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

    public Map<QName, String> getExtraInfo() {
        return extraInfo;
    }

    public Map<String, String> getExtraTokens() {
        Map<String, String> extraTokens = new HashMap<>();
        if (StringUtils.isNotBlank(getClassifier())) {
            extraTokens.put("classifier", getClassifier());
        }
        Map<QName, String> extraInfo = getExtraInfo();
        if (extraInfo != null) {
            for (Map.Entry<QName, String> extraToken : extraInfo.entrySet()) {
                String key = extraToken.getKey().getLocalPart();
                if (extraTokens.containsKey(key)) {
                    throw new GradleException("Duplicated extra info '" + key + "'.");
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
