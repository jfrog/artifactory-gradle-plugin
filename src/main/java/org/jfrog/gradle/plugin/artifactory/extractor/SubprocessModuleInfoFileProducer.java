package org.jfrog.gradle.plugin.artifactory.extractor;

import org.apache.commons.lang3.StringUtils;
import org.gradle.api.Project;
import org.gradle.api.file.FileCollection;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.jfrog.build.api.builder.ModuleType;
import org.jfrog.build.api.util.FileChecksumCalculator;
import org.jfrog.build.extractor.ModuleExtractorUtils;
import org.jfrog.build.extractor.builder.ArtifactBuilder;
import org.jfrog.build.extractor.builder.ModuleBuilder;
import org.jfrog.build.extractor.ci.Artifact;
import org.jfrog.build.extractor.ci.Dependency;
import org.jfrog.build.extractor.ci.Module;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.gradle.plugin.artifactory.dsl.ArtifactoryPluginConvention;
import org.jfrog.gradle.plugin.artifactory.task.ArtifactoryTask;
import org.jfrog.gradle.plugin.artifactory.utils.ExtensionsUtils;
import org.jfrog.gradle.plugin.artifactory.utils.SharedBuildDependencies;
import org.jfrog.gradle.plugin.artifactory.utils.SharedBuildLogicUtils;
import org.jfrog.gradle.plugin.artifactory.utils.TaskUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.jfrog.build.api.util.FileChecksumCalculator.MD5_ALGORITHM;
import static org.jfrog.build.api.util.FileChecksumCalculator.SHA1_ALGORITHM;
import static org.jfrog.build.api.util.FileChecksumCalculator.SHA256_ALGORITHM;

/**
 * Module-info for a used shared build collected from the consumer DeployTask.
 * Uses Gradle-resolved first-level dependencies and scans build/libs plus
 * build/publications so jar/pom/module files get Maven layout paths for deploy.
 */
public class SubprocessModuleInfoFileProducer implements ModuleInfoFileProducer {
    private static final Logger log = Logging.getLogger(SubprocessModuleInfoFileProducer.class);

    private final Project anchorProject;
    private final String moduleId;
    private final List<Dependency> collectedDependencies;
    private final File buildDirectory;
    private final String artifactModuleId;
    private Module cachedModule;
    private File cachedModuleFile;
    // Artifact paths that failed to deploy; excluded from the module so build-info never
    // claims an artifact was published when it was not. Must be populated before the module
    // is extracted/cached (i.e. before ensureModuleInfoFilesAreWritten() is called).
    private final Set<String> failedArtifactPaths = new HashSet<>();

    public void markArtifactFailed(String artifactPath) {
        failedArtifactPaths.add(artifactPath);
    }

    public SubprocessModuleInfoFileProducer(Project anchorProject, String moduleId, List<Dependency> collectedDependencies,
                                            File buildDirectory, String artifactModuleId) {
        this.anchorProject = anchorProject;
        this.moduleId = moduleId;
        this.collectedDependencies = collectedDependencies != null ? collectedDependencies : Collections.emptyList();
        this.buildDirectory = buildDirectory;
        this.artifactModuleId = StringUtils.defaultIfBlank(artifactModuleId, moduleId);
    }

    public String getModuleId() {
        return publishedModuleId();
    }

    /**
     * Version from the project's own GAV, or from pom/jar if export still said unspecified.
     */
    private String publishedVersion() {
        String[] own = SharedBuildLogicUtils.gavParts(artifactModuleId);
        return SharedBuildLogicUtils.resolvePublishedVersion(own[2], own[1], buildDirectory);
    }

    private String publishedOwnCoordinates() {
        String[] own = SharedBuildLogicUtils.gavParts(artifactModuleId);
        return SharedBuildLogicUtils.ownModuleCoordinates(own[0], own[1], publishedVersion());
    }

    private String publishedModuleId() {
        return SharedBuildLogicUtils.withVersion(moduleId, publishedVersion());
    }

    @Override
    public boolean hasModules() {
        // Keep a registered shared build even when assemble/export found no files yet.
        return getOrExtractModule() != null;
    }

    @Override
    public FileCollection getModuleInfoFiles() {
        File moduleFile = anchorProject.getLayout().getBuildDirectory()
                .file("module-info-" + moduleId.replace(":", "-") + ".json").get().getAsFile();
        return anchorProject.files(moduleFile);
    }

    @Override
    public void ensureModuleInfoFilesAreWritten() {
        getOrWriteModuleFile();
    }

    /**
     * Local files to deploy. artifactPath is the Maven path without the repo key.
     * POM descriptors follow the same publishPom / publisher.maven rules as main publications.
     */
    public List<ArtifactToPublish> getArtifactsToPublish() {
        List<ArtifactToPublish> toPublish = new ArrayList<>();
        if (buildDirectory == null || !buildDirectory.exists()) {
            return toPublish;
        }

        addBinaryArtifacts(toPublish);
        if (isPublishMaven()) {
            ensurePublicationMetadata();
            addPublicationMetadata(toPublish);
        } else {
            addGradleModuleMetadata(toPublish);
        }
        return toPublish;
    }

    /**
     * Same resolution as {@code MavenPublicationExtractor.isPublishMaven}:
     * CI publisher.maven if set, else artifactoryPublish task publishPom, else true.
     * Flag-on keeps POMs even when jf gradle injects publish.maven=false.
     */
    private boolean isPublishMaven() {
        ArtifactoryClientConfiguration.PublisherHandler publisher = ExtensionsUtils.getPublisherHandler(anchorProject);
        Boolean publisherMaven = publisher != null ? publisher.isMaven() : null;
        Boolean taskPublishPom = null;
        for (ArtifactoryTask task : TaskUtils.getAllArtifactoryPublishTasks(anchorProject)) {
            if (task.getPublishPom() != null) {
                taskPublishPom = task.getPublishPom();
                break;
            }
        }
        return SharedBuildLogicUtils.shouldPublishMavenDescriptor(
                publisherMaven, taskPublishPom,
                SharedBuildLogicUtils.isIncludeSharedBuildEnabled(anchorProject));
    }

    private Module getOrExtractModule() {
        if (cachedModule == null) {
            try {
                List<Dependency> dependencies = new ArrayList<>(collectedDependencies);

                List<Artifact> artifacts = new ArrayList<>();
                for (ArtifactToPublish item : getArtifactsToPublish()) {
                    if (failedArtifactPaths.contains(item.artifactPath)) {
                        continue;
                    }
                    Artifact artifact = toBuildInfoArtifact(item);
                    if (artifact != null) {
                        artifacts.add(artifact);
                    }
                }

                String repoKey = artifacts.isEmpty() ? "" : StringUtils.defaultString(getRepositoryKey());
                cachedModule = new ModuleBuilder()
                        .type(ModuleType.GRADLE)
                        .id(publishedModuleId())
                        .repository(repoKey)
                        .dependencies(dependencies)
                        .artifacts(artifacts)
                        .build();
            } catch (Exception e) {
                log.warn("Failed to extract module from subprocess output for '{}': {}", moduleId, e.getMessage());
            }
        }
        return cachedModule;
    }

    /**
     * Scan common Gradle output dirs for jars and archives produced by the shared build.
     */
    private void addBinaryArtifacts(List<ArtifactToPublish> toPublish) {
        String[] own = SharedBuildLogicUtils.gavParts(publishedOwnCoordinates());
        SharedBuildLogicUtils.ensurePublishedJar(buildDirectory, own[1], own[2]);
        String[] outputDirs = {
                "build/libs",
                "build/outputs/jar",
                "build/distributions"
        };
        for (String outputDir : outputDirs) {
            File dir = new File(buildDirectory, outputDir);
            if (!dir.isDirectory()) {
                continue;
            }
            File[] files = dir.listFiles((d, name) ->
                    name.endsWith(".jar") || name.endsWith(".war") || name.endsWith(".zip") || name.endsWith(".tar.gz"));
            if (files == null) {
                continue;
            }
            for (File file : files) {
                addPublishedArtifact(toPublish, file,
                        SharedBuildLogicUtils.buildMavenArtifactPath(publishedOwnCoordinates(), publishedFileName(file.getName())));
            }
        }
    }

    private void ensurePublicationMetadata() {
        String[] own = SharedBuildLogicUtils.gavParts(publishedOwnCoordinates());
        SharedBuildLogicUtils.ensurePublishedMetadata(
                buildDirectory, own[0], own[1], own[2],
                SharedBuildDependencies.parseDeclaredDependencies(
                        SharedBuildLogicUtils.readBuildScript(buildDirectory)));
    }

    /**
     * Maven Publish writes pom-default.xml / module.json; publish them as name-version.pom / .module.
     */
    private void addPublicationMetadata(List<ArtifactToPublish> toPublish) {
        File publicationsDir = new File(buildDirectory, "build/publications");
        if (!publicationsDir.isDirectory()) {
            return;
        }
        File[] publicationDirs = publicationsDir.listFiles(File::isDirectory);
        if (publicationDirs == null) {
            return;
        }
        String baseName = artifactBaseName();
        for (File pubDir : publicationDirs) {
            addMetadataIfPresent(toPublish, new File(pubDir, "pom-default.xml"), baseName + ".pom");
            addMetadataIfPresent(toPublish, new File(pubDir, "module.json"), baseName + ".module");
        }
    }

    /**
     * When Maven POM publish is off, still pick up Gradle module metadata if present.
     */
    private void addGradleModuleMetadata(List<ArtifactToPublish> toPublish) {
        File publicationsDir = new File(buildDirectory, "build/publications");
        if (!publicationsDir.isDirectory()) {
            return;
        }
        File[] publicationDirs = publicationsDir.listFiles(File::isDirectory);
        if (publicationDirs == null) {
            return;
        }
        String baseName = artifactBaseName();
        for (File pubDir : publicationDirs) {
            addMetadataIfPresent(toPublish, new File(pubDir, "module.json"), baseName + ".module");
        }
    }

    private void addMetadataIfPresent(List<ArtifactToPublish> toPublish, File sourceFile, String publishedName) {
        if (!sourceFile.exists()) {
            return;
        }
        addPublishedArtifact(toPublish, sourceFile,
                SharedBuildLogicUtils.buildMavenArtifactPath(publishedOwnCoordinates(), publishedName));
    }

    private Artifact toBuildInfoArtifact(ArtifactToPublish item) {
        try {
            Map<String, String> checksums = FileChecksumCalculator.calculateChecksums(
                    item.sourceFile, MD5_ALGORITHM, SHA1_ALGORITHM, SHA256_ALGORITHM);
            String publishedName = item.artifactPath.substring(item.artifactPath.lastIndexOf('/') + 1);
            return new ArtifactBuilder(publishedName)
                    .type(artifactType(publishedName))
                    .md5(checksums.get(MD5_ALGORITHM))
                    .sha1(checksums.get(SHA1_ALGORITHM))
                    .sha256(checksums.get(SHA256_ALGORITHM))
                    .remotePath(item.artifactPath)
                    .build();
        } catch (Exception e) {
            log.warn("Could not collect artifact {} for '{}': {}", item.sourceFile.getName(), moduleId, e.getMessage());
            return null;
        }
    }

    private String artifactType(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return "";
        }
        return fileName.substring(dot + 1);
    }

    private String artifactBaseName() {
        String[] parts = SharedBuildLogicUtils.gavParts(publishedOwnCoordinates());
        return parts[1] + "-" + parts[2];
    }

    /**
     * Keep the jar's real name when it already includes the version; otherwise use name-version.ext.
     */
    private String publishedFileName(String fileName) {
        if (StringUtils.isBlank(fileName)) {
            return fileName;
        }
        String[] own = SharedBuildLogicUtils.gavParts(publishedOwnCoordinates());
        if (fileName.contains(own[2])) {
            return fileName;
        }
        int dot = fileName.lastIndexOf('.');
        String ext = dot >= 0 ? fileName.substring(dot) : "";
        return own[1] + "-" + own[2] + ext;
    }

    private String getRepositoryKey() {
        try {
            ArtifactoryPluginConvention extension = ExtensionsUtils.getArtifactoryExtension(anchorProject);
            if (extension == null) {
                return null;
            }
            return extension.getClientConfig().publisher.getRepoKey();
        } catch (Exception e) {
            log.debug("Could not get repository key: {}", e.getMessage());
            return null;
        }
    }

    private File getOrWriteModuleFile() {
        if (cachedModuleFile != null) {
            return cachedModuleFile;
        }
        Module module = getOrExtractModule();
        if (module == null) {
            return null;
        }
        try {
            File moduleFile = anchorProject.getLayout().getBuildDirectory()
                    .file("module-info-" + moduleId.replace(":", "-") + ".json").get().getAsFile();
            File parent = moduleFile.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            ModuleExtractorUtils.saveModuleToFile(module, moduleFile);
            cachedModuleFile = moduleFile;
            return cachedModuleFile;
        } catch (IOException e) {
            log.error("Could not write module-info file for '{}': {}", moduleId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * One Maven path, one artifact. Prefer the file whose name already matches the published name.
     */
    static void addPublishedArtifact(List<ArtifactToPublish> toPublish, File sourceFile, String artifactPath) {
        if (sourceFile == null || StringUtils.isBlank(artifactPath)) {
            return;
        }
        String publishedName = artifactPath.substring(artifactPath.lastIndexOf('/') + 1);
        for (int i = 0; i < toPublish.size(); i++) {
            ArtifactToPublish existing = toPublish.get(i);
            if (!artifactPath.equals(existing.artifactPath)) {
                continue;
            }
            if (publishedName.equals(sourceFile.getName()) && !publishedName.equals(existing.sourceFile.getName())) {
                toPublish.set(i, new ArtifactToPublish(sourceFile, artifactPath));
            }
            return;
        }
        toPublish.add(new ArtifactToPublish(sourceFile, artifactPath));
    }

    /**
     * A shared-build file to deploy, with the Maven path Artifactory and Xray look up.
     */
    public static class ArtifactToPublish {
        public final File sourceFile;
        public final String artifactPath;

        public ArtifactToPublish(File sourceFile, String artifactPath) {
            this.sourceFile = sourceFile;
            this.artifactPath = artifactPath;
        }
    }
}
