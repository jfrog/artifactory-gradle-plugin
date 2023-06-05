package org.jfrog.buildinfo.extractor.details;

import org.gradle.api.Project;
import org.jfrog.build.extractor.clientConfiguration.deploy.DeployDetails;

import java.util.Objects;

/**
 * Describes a container to hold artifact, and it's corresponding deployment details in a project
 */
public class GradleDeployDetails implements Comparable<GradleDeployDetails> {
    private final DeployDetails deployDetails;
    private final PublishArtifactInfo publishArtifact;
    private final Project project;

    public GradleDeployDetails(PublishArtifactInfo publishArtifact, DeployDetails deployDetails, Project project) {
        this.deployDetails = deployDetails;
        this.publishArtifact = publishArtifact;
        this.project = project;
    }

    public DeployDetails getDeployDetails() {
        return deployDetails;
    }

    public Project getProject() {
        return project;
    }

    public PublishArtifactInfo getPublishArtifact() {
        return publishArtifact;
    }

    public int compareTo(GradleDeployDetails that) {
        if (this.publishArtifact == null) {
            return -1;
        }
        if (that.publishArtifact == null) {
            return 1;
        }

        int compareDeployDetails = this.deployDetails.compareTo(that.deployDetails);
        if (compareDeployDetails == 0) {
            return 0;
        }

        String thisExtension = this.publishArtifact.getExtension();
        String thatExtension = that.publishArtifact.getExtension();
        if (thisExtension == null) {
            return -1;
        }
        if (thatExtension == null) {
            return 1;
        }
        thisExtension = thisExtension.toLowerCase();
        if ("xml".equals(thisExtension) || "pom".equals(thisExtension)) {
            return 1;
        }
        thatExtension = thatExtension.toLowerCase();
        if ("xml".equals(thatExtension) || "pom".equals(thatExtension)) {
            return -1;
        }
        return compareDeployDetails;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        GradleDeployDetails that = (GradleDeployDetails) o;

        if (!Objects.equals(deployDetails, that.deployDetails))
            return false;
        if (!Objects.equals(publishArtifact, that.publishArtifact))
            return false;
        return Objects.equals(project, that.project);
    }

    @Override
    public int hashCode() {
        int result = deployDetails != null ? deployDetails.hashCode() : 0;
        result = 31 * result + (publishArtifact != null ? publishArtifact.hashCode() : 0);
        result = 31 * result + (project != null ? project.hashCode() : 0);
        return result;
    }
}
