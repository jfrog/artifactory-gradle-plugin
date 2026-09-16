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
    private String cachedPublishedVersion;
    private String cachedPublishedOwnCoordinates;
    private List<ArtifactToPublish> cachedArtifactsToPublish;
    // Set when writing the module-info file failed, so hasModules() reports false for this
    // producer instead of GradleBuildInfoExtractor later trying to read a file that was never
    // written. getOrExtractModule() alone can't signal this: extraction itself still succeeds
    // (it's the write to disk that failed), so cachedModule would stay non-null either way.
    private boolean moduleFileWriteFailed;
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
        if (cachedPublishedVersion == null) {
            String[] own = SharedBuildLogicUtils.gavParts(artifactModuleId);
            cachedPublishedVersion = SharedBuildLogicUtils.resolvePublishedVersion(own[2], own[1], buildDirectory);
        }
        return cachedPublishedVersion;
    }

    private String publishedOwnCoordinates() {
        if (cachedPublishedOwnCoordinates == null) {
            String[] own = SharedBuildLogicUtils.gavParts(artifactModuleId);
            cachedPublishedOwnCoordinates = SharedBuildLogicUtils.ownModuleCoordinates(own[0], own[1], publishedVersion());
        }
        return cachedPublishedOwnCoordinates;
    }

    private String publishedModuleId() {
        return SharedBuildLogicUtils.withVersion(moduleId, publishedVersion());
    }

    @Override
    public boolean hasModules() {
        if (moduleFileWriteFailed) {
            return false;
        }
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
        // Memoized: this scans the filesystem, and both DeployTask (to upload) and
        // getOrExtractModule() (to build the module's Artifact list) call this - they must see
        // the exact same ArtifactToPublish instances so a checksum computed by one side is
        // reused by the other instead of being hashed twice (see ArtifactToPublish.getOrComputeChecksums).
        if (cachedArtifactsToPublish == null) {
            cachedArtifactsToPublish = computeArtifactsToPublish();
        }
        return cachedArtifactsToPublish;
    }

    private List<ArtifactToPublish> computeArtifactsToPublish() {
        List<ArtifactToPublish> toPublish = new ArrayList<>();
        if (buildDirectory == null || !buildDirectory.exists()) {
            return toPublish;
        }

        addBinaryArtifacts(toPublish);
        if (isPublishMaven()) {
            ensurePublicationMetadata();
            addPublicationMetadata(toPublish, true);
        } else {
            addPublicationMetadata(toPublish, false);
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
        for (ArtifactoryTask task : TaskUtils.getArtifactoryPublishTasksForProject(anchorProject)) {
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
     * The pom is only included when includePom is true (Maven POM publish is on) - when it's off,
     * Gradle module metadata is still picked up if present. Merged form of what used to be two
     * near-identical methods (addPublicationMetadata / addGradleModuleMetadata) differing only
     * in whether the pom was included.
     */
    private void addPublicationMetadata(List<ArtifactToPublish> toPublish, boolean includePom) {
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
            if (includePom) {
                addMetadataIfPresent(toPublish, new File(pubDir, "pom-default.xml"), baseName + ".pom");
            }
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
            // Reuses the checksums DeployTask.publishNestedBuildArtifacts already computed for
            // this exact artifact (same cached instance, see getArtifactsToPublish) instead of
            // hashing the file a second time.
            Map<String, String> checksums = item.getOrComputeChecksums();
            String publishedName = item.artifactPath.substring(item.artifactPath.lastIndexOf('/') + 1);
            return new ArtifactBuilder(publishedName)
                    .type(StringUtils.substringAfterLast(publishedName, "."))
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
            // hasModules() must report false from here on: the module object extracted fine, so
            // cachedModule staying non-null would make hasModules() true forever, and
            // GradleBuildInfoExtractor would then try to read a module-info file that was never
            // written and throw, failing build-info generation for the whole build over one
            // shared-build's write failure (RTECO-136 review notes).
            moduleFileWriteFailed = true;
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
        // Computed once and shared: DeployTask (upload) and getOrExtractModule() (build-info
        // Artifact) both need this file's checksums for the same file - see toBuildInfoArtifact.
        private Map<String, String> checksums;

        public ArtifactToPublish(File sourceFile, String artifactPath) {
            this.sourceFile = sourceFile;
            this.artifactPath = artifactPath;
        }

        public synchronized Map<String, String> getOrComputeChecksums() throws Exception {
            if (checksums == null) {
                checksums = FileChecksumCalculator.calculateChecksums(
                        sourceFile, MD5_ALGORITHM, SHA1_ALGORITHM, SHA256_ALGORITHM);
            }
            return checksums;
        }
    }
}
