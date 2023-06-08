package org.jfrog.buildinfo.tasks;

import org.apache.commons.lang3.StringUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.TaskAction;
import org.jfrog.build.extractor.BuildInfoExtractorUtils;
import org.jfrog.build.extractor.ci.BuildInfo;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.buildinfo.Constant;
import org.jfrog.buildinfo.extractor.GradleBuildInfoExtractor;
import org.jfrog.buildinfo.extractor.ModuleInfoFileProducer;
import org.jfrog.buildinfo.utils.ConventionUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration.addDefaultPublisherAttributes;

public class ExtractBuildInfoTask extends DefaultTask {

    private static final Logger log = Logging.getLogger(ExtractBuildInfoTask.class);

    private final List<ModuleInfoFileProducer> moduleInfoFileProducers = new ArrayList<>();

    @TaskAction
    public void extractBuildInfo() throws IOException {
        log.info("<ASSAF> Task '{}' activated", getPath());
        ArtifactoryClientConfiguration accRoot = ConventionUtils.getArtifactoryConvention(getProject()).getClientConfig();
        // Reset the default properties, they may have changed
        addDefaultPublisherAttributes(accRoot, getProject().getRootProject().getName(), Constant.GRADLE, getProject().getGradle().getGradleVersion());
        // Extract Build Info.
        GradleBuildInfoExtractor gbie = new GradleBuildInfoExtractor(accRoot, moduleInfoFileProducers);
        BuildInfo buildInfo = gbie.extract(getProject().getRootProject());
        // Export in Json format to file system
        exportBuildInfoToFileSystem(accRoot, buildInfo);
    }

    private void exportBuildInfoToFileSystem(ArtifactoryClientConfiguration accRoot, BuildInfo buildInfo) throws IOException {
        log.info("<ASSAF> trying to export to:\nExport: {}\nGeneratedPath: {}", getExportFile(accRoot), accRoot.info.getGeneratedBuildInfoFilePath());
        try {
            exportBuildInfo(buildInfo, getExportFile(accRoot));
            // We offer option to create a copy in additional place if requested
            if (!StringUtils.isEmpty(accRoot.info.getGeneratedBuildInfoFilePath())) {
                exportBuildInfo(buildInfo, new File(accRoot.info.getGeneratedBuildInfoFilePath()));
            }
        } catch (Exception e) {
            log.error("Failed writing build info to file: ", e);
            throw new IOException("Failed writing build info to file", e);
        }

    }

    private void exportBuildInfo(BuildInfo buildInfo, File toFile) throws IOException {
        log.debug("Exporting generated build info to '{}'", toFile.getAbsolutePath());
        BuildInfoExtractorUtils.saveBuildInfoToFile(buildInfo, toFile);
    }

    private File getExportFile(ArtifactoryClientConfiguration clientConf) {
        // Configured path
        String fileExportPath = clientConf.getExportFile();
        if (StringUtils.isNotBlank(fileExportPath)) {
            return new File(fileExportPath);
        }
        // Default path
        Project rootProject = getProject().getRootProject();
        return new File(rootProject.getBuildDir(), Constant.BUILD_INFO_FILE_NAME);
    }

    public void registerModuleInfoProducer(ModuleInfoFileProducer moduleInfoFileProducer) {
        this.moduleInfoFileProducers.add(moduleInfoFileProducer);
    }

    @InputFiles
    public FileCollection getModuleInfoFiles() {
        ConfigurableFileCollection moduleInfoFiles = getProject().files();
        moduleInfoFileProducers.forEach(moduleInfoFileProducer -> {
            moduleInfoFiles.from(moduleInfoFileProducer.getModuleInfoFiles());
            moduleInfoFiles.builtBy(moduleInfoFileProducer.getModuleInfoFiles().getBuildDependencies());
        });
        return moduleInfoFiles;
    }
}
