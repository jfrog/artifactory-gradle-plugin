package org.jfrog.gradle.plugin.artifactory.extractor;

import org.testng.annotations.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.assertEquals;

public class SubprocessModuleInfoFileProducerTest {

    @Test
    public void testAddPublishedArtifactKeepsOneEntryPerMavenPath() {
        List<SubprocessModuleInfoFileProducer.ArtifactToPublish> toPublish = new ArrayList<>();
        String path = "com/example/nested-app/buildSrc/1.0.3/buildSrc-1.0.3.jar";
        SubprocessModuleInfoFileProducer.addPublishedArtifact(toPublish, new File("buildSrc.jar"), path);
        SubprocessModuleInfoFileProducer.addPublishedArtifact(toPublish, new File("buildSrc-1.0.1.jar"), path);
        SubprocessModuleInfoFileProducer.addPublishedArtifact(toPublish, new File("buildSrc-1.0.3.jar"), path);

        assertEquals(toPublish.size(), 1);
        assertEquals(toPublish.get(0).artifactPath, path);
        assertEquals(toPublish.get(0).sourceFile.getName(), "buildSrc-1.0.3.jar");
    }
}
