<div align="center">

# 🐸 Artifactory Gradle Plugin 🐘

[![Scanned by Frogbot](https://raw.github.com/jfrog/frogbot/master/images/frogbot-badge.svg)](https://github.com/jfrog/frogbot#readme)

</div>

---
| Branch |                                                                                                       Main                                                                                                        |                                                                                                       Dev                                                                                                       |
|:------:|:-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------:|:---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------:|
| Status | [![Test](https://github.com/jfrog/artifactory-gradle-plugin/actions/workflows/test.yml/badge.svg?branch=main)](https://github.com/jfrog/artifactory-gradle-plugin/actions/workflows/test.yml?query=branch%3Amain) | [![Test](https://github.com/jfrog/artifactory-gradle-plugin/actions/workflows/test.yml/badge.svg?branch=dev)](https://github.com/jfrog/artifactory-gradle-plugin/actions/workflows/test.yml?query=branch%3Adev) |
---

# Table of Contents
- [Overview](#overview)
- [🖥️ Download and Installation](#-download-and-installation)
- [Dependencies Resolution](#dependencies-resolution)
- [Artifactory Publication](#artifactory-publication)
  - [Artifactory Configuration](#artifactory-configuration)
  - [Task Configurations](#task-configurations)
  - [Client Configurations](#client-configurations)
- [Examples](#examples)
- [💻 Contribution](#-contributions)

---

## Overview
```The minimum supported Gradle version to use this plugin is v6.6```

The Gradle Artifactory Plugin provides tight integration with Gradle. All that is needed is a simple modification of your
```build.gradle```
script file with a few configuration parameters and you can:
1. Resolve your build dependencies from Artifactory
2. Deploy your build artifacts and build information to Artifactory

Integration Benefits: [JFrog Artifactory and Gradle Repositories](https://jfrog.com/integration/gradle-repository/)

---

## 🖥️ Download and Installation
Add the following snippet to your ```build.gradle.kts```
```kotlin
// Replace <plugin version> with the version of the Gradle Artifactory Plugin.
plugins {
    id("com.jfrog.artifactory") version "<plugin version>"
}
```
<details>
<summary>build.gradle</summary>

```groovy
plugins {
  id "com.jfrog.artifactory" version "<plugin version>"
}
```
</details>

---

## Dependencies Resolution

Define the project to preform dependency resolution resolve dependencies from the default dependency resolution from Artifactory:
```kotlin
repositories { 
    maven {
        // The Artifactory (preferably virtual) repository to resolve from 
        url = uri("http://repo.myorg.com/artifactory/libs-releases")
        // Optional resolver credentials (leave out to use anonymous resolution)
        credentials {
            // Artifactory user name
            username = "resolver"
            // Password or API Key
            password = "resolverPaS*" 
        } 
    }

    ivy {
        url = uri("http://localhost:8081/artifactory/ivy-releases")
        // Optional section for configuring Ivy-style resolution.
        patternLayout {
            ivy("[organization]/[module]/[revision]/ivy.xml")
            artifact("[organization]/[module]/[revision]/[module]-[revision](-[classifier]).[ext]")
            // Convert any dots in an [organization] layout value to path separators, similar to Maven's groupId-to-path conversion. False if not specified.
            setM2compatible(true)
        }
    }
}
```

<details>
<summary>build.gradle</summary>

```groovy
repositories {
  maven {
    url = "http://repo.myorg.com/artifactory/libs-releases"
    credentials {
      username = "resolver"
      password = "resolverPaS"
    }
  }
  ivy {
    url = "http://localhost:8081/artifactory/ivy-releases"
    patternLayout {
      ivy = "[organization]/[module]/[revision]/ivy.xml"
      artifact = "[organization]/[module]/[revision]/[module]-[revision](-[classifier]).[ext]"
      m2Compatible = true
    }
  }
}
```
</details>

Follow this [documentation](https://docs.gradle.org/current/userguide/userguide.html) for different ways to configure your repositories.

---

## Artifactory Publication

The plugin adds the following task for each project at the 'publishing' group and can be run with:
```text
gradle artifactoryPublish
```
* Getting debug information from Gradle
  We highly recommend running Gradle with the ``` -d ```
  option to get useful and readable information if something goes wrong with your build.

The task does the following to the project and its submodules:
1. Collects all the publication artifacts as configured.


### Artifactory Configuration

To use the ```artifactoryPublish``` task you need to define,  at the root project ```build.gradle.kts```, the Artifactory convention.
This configuration will define the information needed to access the Artifactory instance that the artifacts will be published to from the task. 

```kotlin
artifactory {
    publish {
        // Define the Artifactory URL to publish the artifacts
        contextUrl = 'http://127.0.0.1:8081/artifactory'
        // Define the project repository that the artifacts will be published to
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
    }
}
```

<details>
<summary>build.gradle</summary>

```groovy
artifactory {
    publish {
      contextUrl = 'http://127.0.0.1:8081/artifactory'
      repository {
        repoKey = 'libs-snapshot-local'
        username = "${artifactory_user}"
        password = "${artifactory_password}"
      }
    }
}
```
</details>

* In addition to the required configuration above, you are required to configure what publications will be included in the ```artifactoryPublish``` task for each project.

### Task Configurations

You can configure your root project or/and under its submodules (projects) to control the task operation for specific project.

Configure the task attributes (or only part of them) for each project in the following way:

```kotlin
artifactoryPublish {
    // Specify what publications to include when collecting artifacts to publish to Artifactory
    publifications(
            // Publication can be specified as Object
            publishing.publications.ivyJava,
            // Publication can be specified as String
            'mavenJava',
            // If this plguin constant string is specified the plugin will try to apply all the known publications
            'ALL_PUBLICATIONS'
    )

    // Properties to be attached to the published artifacts.
    properties = ['qa.level': 'basic', 'dev.team' : 'core']
    // Properties can be also defined with closure in the format: configName artifactSpec, key1:val1, key2:val2
    properties { 
        simpleFile '**:**:**:*@*', simpleFile: 'only on settings file'
    }
  
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
#### Defaults - Global task Configurations to apply to all the projects
You can specify the configurations of ```artifactoryPublish``` task as ```defaults``` under ```publish``` in the main ```artifactory``` convention. 
```kotlin
artifactory {
    publish {
        repository {
            // Required repository information...
        }
        defaults {
            // artifactoryPublish task attributes...
        }
    }
}
```

This task configurations will be added to the specific tasks configurations and apply to all the projects, allowing you to configure global configurations at one place that are the same for all the projects instead of configuring them for each project.
### Client Configurations

Redefine basic properties of the build info object can be applied using the ```clientConfig``` object under the main ```artifactory``` convention.

```kotlin
import java.util.*

artifactory {
    publish {
        // Required publish information...
    }
    
    // (default: 300 seconds) Artifactory's connection timeout (in seconds).
    clientConfig.timeout = 600
    // (default: false) Set to true to skip TLS certificates verification.
    clientConfig.setInsecureTls(false)
    // (default: false) Set to true to include environment variables while running the tasks
    clientConfig.setIncludeEnvVars(true)
    // Set patterns of environment variables to include/exclude while running the tasks
    clientConfig.setEnvVarsExcludePatterns('*password*,*secret*')
    clientConfig.setEnvVarsIncludePatterns('*not-secret*')
    // Add a dynamic environment variable for the tasks
    clientConfig.info.addEnvironmentProperty('test.adding.dynVar',Date().toString())
    // Set specific build and project information for the build-info
    clientConfig.info.setBuildName('new-strange-name')
    clientConfig.info.setBuildNumber('' + Random(System.currentTimeMillis()).nextInt(20000))
    clientConfig.info.setProject('project-key')
}
```

<details>
<summary>build.gradle</summary>

```groovy
artifactory {
  clientConfig.setIncludeEnvVars(true)
  clientConfig.setEnvVarsExcludePatterns('*password*,*secret*')
  clientConfig.setEnvVarsIncludePatterns('*not-secret*')
  clientConfig.info.addEnvironmentProperty('test.adding.dynVar',new Date().toString())
  clientConfig.info.setBuildName('new-strange-name')
  clientConfig.info.setBuildNumber('' + new Random(System.currentTimeMillis()).nextInt(20000))
  clientConfig.info.setProject('project-key')
  clientConfig.timeout = 600
  clientConfig.setInsecureTls(false)
}
```
</details>

---

## Examples

We highly recommend also using our [examples](https://github.com/JFrog/project-examples/tree/master/gradle-examples?_gl=1*pgsvlz*_ga*MTc3OTI0ODE4NS4xNjYyMjgxMjI1*_ga_SQ1NR9VTFJ*MTY4NTM2OTcwMC4yNi4wLjE2ODUzNjk3MDAuNjAuMC4w) as a reference when configuring the DSL in your build scripts.

---

## 💻 Contributions

We welcome pull requests from the community. To help us improve this project, please read
our [Contribution](./CONTRIBUTING.md#-guidelines) guide.