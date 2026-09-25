package org.jfrog.gradle.plugin.artifactory;

import org.jfrog.gradle.plugin.artifactory.task.ArtifactoryTask.MavenPublicationData;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.testng.Assert.*;

/**
 * Tests for lazy project version resolution logic used for configuration cache compatibility.
 */
public class LazyVersionResolutionTest {

    @Test
    public void testVersionOverrideAffectsMultiplePublications() {
        String overrideVersion = "3.0.0";
        List<MavenPublicationData> snapshots = List.of(
                new MavenPublicationData("pub1", "org.a", "lib-a", "1.0.0", Collections.emptyList()),
                new MavenPublicationData("pub2", "org.b", "lib-b", "2.0.0", Collections.emptyList()),
                new MavenPublicationData("pub3", "org.c", "lib-c", "3.0.0", Collections.emptyList()) // same as override
        );

        List<MavenPublicationData> effectiveList = new ArrayList<>();
        for (MavenPublicationData data : snapshots) {
            MavenPublicationData effective = !overrideVersion.equals(data.getVersion())
                    ? new MavenPublicationData(data.getName(), data.getGroupId(), data.getArtifactId(), overrideVersion, data.getArtifacts())
                    : data;
            effectiveList.add(effective);
        }

        // pub1 and pub2 should be overridden
        assertEquals(effectiveList.get(0).getVersion(), "3.0.0");
        assertNotSame(effectiveList.get(0), snapshots.get(0));

        assertEquals(effectiveList.get(1).getVersion(), "3.0.0");
        assertNotSame(effectiveList.get(1), snapshots.get(1));

        // pub3 version matches — no override, same object
        assertSame(effectiveList.get(2), snapshots.get(2));
    }

    @Test
    public void testNoOverrideWhenVersionNull() {
        MavenPublicationData original = new MavenPublicationData(
                "mavenJava", "org.example", "my-lib", "1.0.0", Collections.emptyList());

        String projectVersion = null;

        MavenPublicationData effective;
        if (projectVersion != null && !projectVersion.equals(original.getVersion())) {
            effective = new MavenPublicationData(
                    original.getName(), original.getGroupId(), original.getArtifactId(),
                    projectVersion, original.getArtifacts());
        } else {
            effective = original;
        }

        assertSame(effective, original);
    }
}
