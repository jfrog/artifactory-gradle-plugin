[![](https://github.com/jfrog/artifactory-gradle-plugin/assets/29822394/151248d7-8e7b-4bae-98ff-9fd4976e5e8d)](#readme)

<div align="center">

# 🐸 Artifactory Gradle Plugin 🐘

</div>

---

<div align="center">

[![Test](https://github.com/jfrog/artifactory-gradle-plugin/actions/workflows/test.yml/badge.svg?branch=main)](https://github.com/jfrog/artifactory-gradle-plugin/actions/workflows/test.yml?query=branch%3Amain)
[![Test](https://github.com/jfrog/artifactory-gradle-plugin/actions/workflows/gradle.yml/badge.svg?branch=main)](https://github.com/jfrog/artifactory-gradle-plugin/actions/workflows/gradle.yml??query=branch%3Amain)
[![Scanned by Frogbot](https://raw.github.com/jfrog/frogbot/master/images/frogbot-badge.svg)](https://github.com/jfrog/frogbot#readme)

</div>

---

## Table of Contents

- [📚 Overview](#-overview)
- [📦 Installation](#-installation)
- [🚀 Usage](#-usage)
- [💡 Examples](#-examples)
- [🐞 Reporting Issues](#-reporting-issues)
- [🫱🏻‍🫲🏼 Contributions](#-contributions)

---

## 📚 Overview

The Gradle Artifactory Plugin provides tight integration with Gradle. All that is needed is a simple modification of
your `build.gradle` script file with a few configuration parameters, and you can deploy your build artifacts and build
information to Artifactory.

The plugin adds the `artifactoryPublish` task for each project, in the 'publishing' group. The task performs the
following actions on the project and its submodules:

1. Extracting the [build-info](https://www.buildinfo.org/) file located in the root project. This file contains
   comprehensive information about the build, such as its configuration, dependencies, and other relevant details.
2. Deploying both the generated artifacts and the build-info file to your Artifactory repository. This ensures that the
   artifacts, which are the output of the build process, and the accompanying build-info file are stored and organized
   in your Artifactory repository for easy access and management.

> **_NOTE:_** The minimum supported Gradle version to use this plugin is v6.8.1

<details>
<summary> 🚚 Migrating from Version 4 to Version 5 of the Plugin</summary>

---

#### Version 5 of the Gradle Artifactory Plugin includes the following breaking changes compared to version 4

* The minimum version of Gradle required to use this plugin has been upgraded to version 6.9.
* The below convention attributes have been removed:

  | Attribute | Migration action                                                                                                                                                                                                                             |
  |:---------:|:---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
  |  parent   | No longer supported.                                                                                                                                                                                                                         |
  |  resolve  | To define the Artifactory resolution repositories for your build, declare the repositories under the repositories section as described [here](https://docs.gradle.org/current/userguide/declaring_repositories.html#declaring-repositories). |

</details>

---

## 📦 Installation

<details>

<summary>Step 1 - Add the plugin to your project</summary>

---

Add the following snippet to your build script:

<details open>
<summary>Kotlin Format</summary>

```kotlin
plugins {
    id("com.jfrog.artifactory") version "5.+"
}
```

</details>

<details>
<summary>Groovy Format</summary>

```groovy
plugins {
    id "com.jfrog.artifactory" version "5.+"
}
```

</details>

---

</details>

<details>

<summary>Step 2 - Configure the plugin with your Artifactory</summary>

---

To configure the plugin with your Artifactory, add the following basic snippet to your project root build script, and
make the necessary adjustments based on your platform information:

<details open>
<summary>Kotlin Format</summary>

```kotlin
configure<ArtifactoryPluginConvention> {
    publish {
        // Define the Artifactory URL for publishing artifacts
        contextUrl = "http://127.0.0.1:8081/artifactory"
        // Define the project repository to which the artifacts will be published
        repository {
            // Set the Artifactory repository key
            repoKey = "libs-snapshot-local"
            // Specify the publisher username
            username = project.property("artifactory_user") as String
            // Provide the publisher password
            password = project.property("artifactory_password") as String
        }

        // Include all configured publications for all the modules
        defaults {
            publications("ALL_PUBLICATIONS")
        }
    }
}
```

</details>

<details>
<summary>Groovy Format</summary>

```groovy
artifactory {
    publish {
        // Define the Artifactory URL for publishing artifacts
        contextUrl = 'http://127.0.0.1:8081/artifactory'
        // Define the project repository to which the artifacts will be published
        repository {
            // Set the Artifactory repository key
            repoKey = 'libs-snapshot-local'
            // Specify the publisher username
            username = "${artifactory_user}"
            // Provide the publisher password
            password = "${artifactory_password}"
        }

        // Include all configured publications for all the modules
        defaults {
            publications('ALL_PUBLICATIONS')
        }
    }
}
```

</details>

### ⚙️ Advance Configurations

For advanced configurations and finer control over the plugin's operations, refer to the following documentation that
outlines all the available configuration options. These options allow you to customize the behavior of the plugin
according to your specific needs.

<details>
<summary>🏢🔧 Artifactory Configurations</summary>

---

The provided code snippet showcases the configuration options for the Artifactory plugin. It demonstrates how to
fine-tune the plugin's behavior to meet specific project requirements, access the Artifactory instance to which the
artifacts will be published, and configure other global settings such as Proxy and Build-Info extraction configurations.

<details open>
<summary>Kotlin Format</summary>

```kotlin
configure<ArtifactoryPluginConvention> {
    publish {
        // Define the Artifactory URL for publishing artifacts
        contextUrl = "http://127.0.0.1:8081/artifactory"
        // Define the project repository to which the artifacts will be published
        repository {
            // Set the Artifactory repository key
            repoKey = "libs-snapshot-local"
            // Specify the publisher username
            username = project.property("artifactory_user") as String
            // Provide the publisher password
            password = project.property("artifactory_password") as String

            // This is an optional section (relevant only when publishIvy = true) for configuring Ivy publication.
            ivy {
                ivyLayout = "[organization]/[module]/ivy-[revision].xml"
                artifactLayout = "[organization]/[module]/[revision]/[module]-[revision](-[classifier]).[ext]"
                // Convert any dots in an [organization] layout value to path separators, similar to Maven's groupId-to-path conversion. True if not specified
                mavenCompatible = true
            }
        }

        // Optionally, you can specify global configurations. These configurations will be added for all projects instead of configuring them for each project.
        defaults {
            // artifactoryPublish task attributes...
        }

        // (default: true) Publish the generated build-info file to Artifactory
        publishBuildInfo = false
        // (default: 3) Number of threads that will work and deploy artifacts to Artifactory
        forkCount = 5
    }

    // Optionally, configure and control the information and attributes of the generated build-info file.
    // Alternatively, you can configure the attributes by using the `clientConfig.info` object.
    buildInfo {
        // Set specific build and project information for the build-info
        buildName = "new-strange-name"
        buildNumber = "" + Random(System.currentTimeMillis()).nextInt(20000)
        project = "project-key"
        // Add a dynamic property to the build-info
        addEnvironmentProperty("test.adding.dynVar", Date().toString())
        // Generate a copy of the build-info.json file in the following path
        generatedBuildInfoFilePath = "/Users/gradle-example-publish/myBuildInfoCopy.json"
        // Generate a file with all the deployed artifacts' information in the following path
        deployableArtifactsFilePath = "/Users/gradle-example-publish/myArtifactsInBuild.json"
    }

    // Optionally, you can use and configure your proxy information to use in the task.
    // Alternatively, you can configure the attributes by using the clientConfig.proxy object.
    proxy {
        host = "ProxyHost"
        port = 60
        username = "ProxyUserName"
        password = "ProxyPassword"
    }

    // (default: 300 seconds) Artifactory's connection timeout (in seconds).
    clientConfig.timeout = 600
    // (default: 0 retries) Artifactory's connection retires
    clientConfig.connectionRetries = 4
    // (default: false) Set to true to skip TLS certificates verification.
    clientConfig.insecureTls = false
    // (default: false) Set to true to include environment variables while running the tasks
    clientConfig.isIncludeEnvVars = true
    // Set patterns of environment variables to include/exclude while running the tasks
    clientConfig.envVarsExcludePatterns = "*password*,*secret*"
    clientConfig.envVarsIncludePatterns = "*not-secret*"
}
```

</details>

<details>
<summary>Groovy Format</summary>

```groovy
artifactory {
    publish {
        // Define the Artifactory URL to publish the artifacts
        contextUrl = 'http://127.0.0.1:8081/artifactory'
        // Define the project repository to which the artifacts will be published
        repository {
            // The Artifactory repository key
            repoKey = 'libs-snapshot-local'
            // The publisher username
            username = "${artifactory_user}"
            // The publisher password
            password = "${artifactory_password}"

            // This is an optional section (relevant only when publishIvy = true) for configuring Ivy publication.
            ivy {
                ivyLayout = '[organization]/[module]/ivy-[revision].xml'
                artifactLayout = '[organization]/[module]/[revision]/[module]-[revision](-[classifier]).[ext]'
                //Convert any dots in an [organization] layout value to path separators, similar to Maven's groupId-to-path conversion. True if not specified
                mavenCompatible = true
            }
        }

        // Optionally, you can specify global configurations. These configurations will be added for all projects instead of configuring them for each project.
        defaults {
            // artifactoryPublish task attributes...
        }

        // (default: true) Publish the generated build-info file to Artifactory
        publishBuildInfo = false
        // (default: 3) Number of threads that will work and deploy artifacts to Artifactory
        forkCount = 5
    }

    // Optionally, configure and control the information and attributes of the generated build-info file.
    // Alternatively, you can configure the attributes by using the `clientConfig.info` object.
    buildInfo {
        // Set specific build and project information for the build-info
        setBuildName('new-strange-name')
        setBuildNumber('' + new Random(System.currentTimeMillis()).nextInt(20000))
        setProject('project-key')
        // Add a dynamic property to the build-info
        addEnvironmentProperty('test.adding.dynVar', new java.util.Date().toString())
        // Generate a copy of the build-info.json file in the following path
        setGeneratedBuildInfoFilePath("/Users/gradle-example-publish/myBuildInfoCopy.json")
        // Generate a file with all the deployed artifacts' information in the following path
        setDeployableArtifactsFilePath("/Users/gradle-example-publish/myArtifactsInBuild.json")
    }

    // Optionally, you can use and configure your proxy information to use in the task.
    // Alternatively, you can configure the attributes by using the clientConfig.proxy object.
    proxy {
        host = "ProxyHost"
        port = 60
        username = "ProxyUserName"
        password = "ProxyPassword"
    }

    // (default: 300 seconds) Artifactory's connection timeout (in seconds).
    clientConfig.timeout = 600
    // (default: 0 retries) Artifactory's connection retires
    clientConfig.setConnectionRetries(4)
    // (default: false) Set to true to skip TLS certificates verification.
    clientConfig.setInsecureTls(false)
    // (default: false) Set to true to include environment variables while running the tasks
    clientConfig.setIncludeEnvVars(true)
    // Set patterns of environment variables to include/exclude while running the tasks
    clientConfig.setEnvVarsExcludePatterns('*password*,*secret*')
    clientConfig.setEnvVarsIncludePatterns('*not-secret*')
}
```

</details>


---

</details>

<details>

<summary>📋🔧 Project Publication Configurations</summary>

---

The `artifactoryPublish` task configuration allows you to customize the task operation and behavior for a specific
project, in addition to the global defaults. The global defaults configuration applies to the project and its
submodules, while the artifactoryPublish configuration allows you to define additional settings specifically for the
project.
When using artifactoryPublish, you have the flexibility to configure additional values without overriding the global
defaults. This means that the project can have its own specific configuration in addition to the common settings applied
globally.

<details open>
<summary>Kotlin Format</summary>

```kotlin
tasks.named<ArtifactoryTask>("artifactoryPublish") {
    // Specify what publications to include when collecting artifacts for publishing to Artifactory
    publications(
            // Publication can be specified as an Object
            publishing.publications["ivyJava"],
            // Publication can be specified as a String
            "mavenJava",
            // If this plugin constant string is specified, the plugin will try to apply all the known publications
            "ALL_PUBLICATIONS"
    )

    // Optionally, configure properties to be attached to the published artifacts.
    setProperties(mapOf(
            "key1" to "value1",
            "key2" to "value2"
    ))
    // (default: false) Skip this task for the project (don't include its artifacts when publishing)
    skip = true
    // (default: true) Publish generated artifacts to Artifactory, can be specified as boolean/string
    setPublishArtifacts(false)
    // (default: true) Publish generated POM files to Artifactory, can be specified as boolean/string
    setPublishPom(false)
    // (default: true) Publish generated Ivy descriptor files to Artifactory, can be specified as boolean/string
    setPublishIvy(false)
}
```

</details>

<details>
<summary>Groovy Format</summary>

```groovy
artifactoryPublish {
    publications('ALL_PUBLICATIONS')

    properties = ['qa.level': 'basic', 'dev.team': 'core']
    // In Groovy format, properties can also be defined with a closure in the format: configName artifactSpec, key1:val1, key2:val2
    properties {
        simpleFile '**:**:**:*@*', simpleFile: 'only on settings file'
    }

    skip = true
    publishArtifacts = false
    publishPom = false
    publishIvy = false
}
```

</details>

</details>

</details>

---

## 🚀 Usage

To deploy the project artifacts and build info to Artifactory, execute the following Gradle task

```bash
./gradlew artifactoryPublish
```

---

## 💡 Examples

The following are links to the build scripts of different types of projects that are configured to use the plugin.

#### [Multi Modules Project (Groovy)](./src/functionalTest/resources/gradle-example-publish/build.gradle)

Sample project that uses the Gradle Artifactory Plugin with Gradle Publications.

#### [Multi Modules Project (Kotlin)](./src/functionalTest/resources/gradle-kts-example-publish/build.gradle.kts)

Sample project that configures the Gradle Artifactory Plugin with the Gradle Kotlin DSL.

We highly recommend also using
our [gradle project examples](https://github.com/JFrog/project-examples/tree/master/gradle-examples?_gl=1*pgsvlz*_ga*MTc3OTI0ODE4NS4xNjYyMjgxMjI1*_ga_SQ1NR9VTFJ*MTY4NTM2OTcwMC4yNi4wLjE2ODUzNjk3MDAuNjAuMC4w)
as a reference when configuring your build scripts.

---

## 🐞 Reporting Issues

We highly recommend running Gradle with the ```-d```
option to get useful and readable debug information if something goes wrong with your build.

Please help us improve the plugin
by [reporting any issues](https://github.com/jfrog/artifactory-gradle-plugin/issues/new/choose) you encounter.

---

## 🫱🏻‍🫲🏼 Contributions

We welcome pull requests from the community. To help us improve this project, please read
our [Contribution](./CONTRIBUTING.md#-guidelines) guide.
