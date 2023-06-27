<div align="center">

# 🐸 Artifactory Gradle Plugin 🐘

[![Scanned by Frogbot](https://raw.github.com/jfrog/frogbot/master/images/frogbot-badge.svg)](https://github.com/jfrog/frogbot#readme)

</div>

---

<div align="center">

| Branch |                                                                                                       Main                                                                                                        |                                                                                                       Dev                                                                                                       |
|:------:|:-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------:|:---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------:|
| Status | [![Test](https://github.com/jfrog/artifactory-gradle-plugin/actions/workflows/test.yml/badge.svg?branch=main)](https://github.com/jfrog/artifactory-gradle-plugin/actions/workflows/test.yml?query=branch%3Amain) | [![Test](https://github.com/jfrog/artifactory-gradle-plugin/actions/workflows/test.yml/badge.svg?branch=dev)](https://github.com/jfrog/artifactory-gradle-plugin/actions/workflows/test.yml?query=branch%3Adev) |

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
```The minimum supported Gradle version to use this plugin is v6.9```

The Gradle Artifactory Plugin provides tight integration with Gradle. All that is needed is a simple modification of your
```build.gradle```
script file with a few configuration parameters, and you can deploy your build artifacts and build information to Artifactory.

The plugin adds the ```artifactoryPublish``` task for each project, in the 'publishing' group.
The task performs the following actions on the project and its submodules:
1. Collects all the publication artifacts - follow this [documentation](https://docs.gradle.org/current/userguide/publishing_setup.html) about defining publications.
2. Extracts module-info (intermediate file) that describes each module's build information.
3. Extracts the [build-info](https://www.buildinfo.org/) file in the root project that describes all the information about the build.
4. Deploys the generated artifacts and build-info file to your Artifactory repository.

---
## 📦 Installation
To use the Artifactory Gradle Plugin, add the following snippet to your build script:
```kotlin
// Replace <plugin version> with the version of the Gradle Artifactory Plugin.
plugins {
    id("com.jfrog.artifactory") version "<plugin version>"
}
```
<details>
<summary>Groovy Format</summary>

```groovy
plugins {
    id "com.jfrog.artifactory" version "<plugin version>"
}
```
</details>

---
## 🚀 Usage
Deploy the project artifacts and build info to Artifactory by running: 
```bash
./gradlew artifactoryPublish
```
To use the `artifactoryPublish` task, you need to define the `artifactory` convention in the root project build script. 

<details>
<summary>Using CI Server</summary>

The task configurations or the Artifactory convention, when using CI Server on a Gradle project, is done from the CI client UI. You can still add the `artifactory` closure to the build script and have default values configured there, but the values configured in the CI Server will override them.
</details>

### ⚙️ Task Configurations
You can configure the attributes for your root project and/or its submodules (projects) to control the task operation for specific projects. Configure the attributes as follows:
```kotlin
artifactoryPublish {
    // Specify what publications to include when collecting artifacts to publish to Artifactory
    publifications(
            // Publication can be specified as an Object
            publishing.publications.ivyJava,
            // Publication can be specified as a String
            'mavenJava',
            // If this plugin constant string is specified, the plugin will try to apply all the known publications
            'ALL_PUBLICATIONS'
    )
    // Properties to be attached to the published artifacts.
    setProperties(mapOf(
            'qa.level' to 'basic',
            'dev.team' to 'core'
    ))
    // (default: false) Skip this task for the project (don't include its artifacts when publishing) 
    skip = true
    // (default: true) Publish generated artifacts to Artifactory, can be specified as boolean/string
    publishArtifacts = false
    // (default: true) Publish generated POM files to Artifactory, can be specified as boolean/string
    publishPom = false
    // (default: true) Publish generated Ivy descriptor files to Artifactory, can be specified as boolean/string
    publishIvy = false
}
```

<details>
<summary>Groovy Format</summary>

```groovy
artifactoryPublish {
    publifications('ALL_PUBLICATIONS')
  
    properties = ['qa.level': 'basic', 'dev.team' : 'core']
  // Properties can also be defined with a closure in the format: configName artifactSpec, key1:val1, key2:val2
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

### ⚙️ Artifactory Convention
This configuration defines the information needed by the tasks to access the Artifactory instance to which the artifacts will be published.
```kotlin
artifactory {
    publish {
        // Define the Artifactory URL to publish the artifacts
        contextUrl = uri('http://127.0.0.1:8081/artifactory')
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
        // (default: true) Publish the generated build-info file to Artifactory
        publishBuildInfo(false)
        // (default: 3) Number of threads that will work and deploy artifacts to Artifactory
        forkCount = 5
    }

    // Optionally, you can specify global configurations. These configurations will be added for all projects instead of configuring them for each project.
    defaults {
         // artifactoryPublish task attributes...
    }
  
    // Configure and control the information and attributes of the generated build-info file.
    // Alternatively, you can configure the attributes by using the `clientConfig.info` object.
    buildInfo {
        // Set specific build and project information for the build-info
        setBuildName('new-strange-name')
        setBuildNumber('' + Random(System.currentTimeMillis()).nextInt(20000))
        setProject('project-key')
        // Add a dynamic property to the build-info
        addEnvironmentProperty('test.adding.dynVar',Date().toString())
        // Generate a copy of the build-info.json file in the following path
        setGeneratedBuildInfoFilePath("/Users/gradle-example-publish/myBuildInfoCopy.json")
        // Generate a file with all the deployed artifacts' information in the following path
        setDdeployableArtifactsFilePath("/Users/gradle-example-publish/myArtifactsInBuild.json")
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

---
## 💡 Examples
The following are links to the build scripts of different types of projects that are configured to use the plugin.

#### [Multi Modules Project (Groovy)](./src/functionalTest/resources/gradle-example-publish/build.gradle)
Sample project that uses the Gradle Artifactory Plugin with Gradle Publications.
#### [Multi Modules Project (Kotlin)](./src/functionalTest/resources/gradle-kts-example-publish/build.gradle.kts)
Sample project that configures the Gradle Artifactory Plugin with the Gradle Kotlin DSL.

We highly recommend also using our [gradle project examples](https://github.com/JFrog/project-examples/tree/master/gradle-examples?_gl=1*pgsvlz*_ga*MTc3OTI0ODE4NS4xNjYyMjgxMjI1*_ga_SQ1NR9VTFJ*MTY4NTM2OTcwMC4yNi4wLjE2ODUzNjk3MDAuNjAuMC4w) as a reference when configuring your build scripts.

---
## 🐞 Reporting Issues
We highly recommend running Gradle with the ```-d```
option to get useful and readable debug information if something goes wrong with your build.

Please help us improve the plugin by [reporting any issues](https://github.com/jfrog/artifactory-gradle-plugin/issues/new/choose) you encounter.

---
## 🫱🏻‍🫲🏼 Contributions

We welcome pull requests from the community. To help us improve this project, please read
our [Contribution](./CONTRIBUTING.md#-guidelines) guide.
