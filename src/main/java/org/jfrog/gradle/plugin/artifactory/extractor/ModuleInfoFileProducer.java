package org.jfrog.gradle.plugin.artifactory.extractor;

import org.gradle.api.file.FileCollection;

/**
 * Represents a producer of module-info files
 */
public interface ModuleInfoFileProducer {

    /**
     * Check whether the module info file will actually contain modules or not.
     * @return true if the module info file will contain modules
     */
    boolean hasModules();

    /**
     * Get the module info file.
     * @return the module info file
     */
    FileCollection getModuleInfoFiles();
}
