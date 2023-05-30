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
- [Configurations](#configurations)
  - [Dependencies Resolution](#dependencies-resolution)
  - [Artifactory Publication](#artifactory-publication)
    - [Publication Configuration](#publication-configuration)
    - [Client Configurations](#client-configurations-optionally)
- [🚥 Using the Gradle Artifactory plugin](#-using-gradle-artifactory-plugin)
- [💻 Contribution](#-contributions)

## Overview
```The minimum supported Gradle version to use this plugin is v6.6```

The Gradle Artifactory Plugin provides tight integration with Gradle. All that is needed is a simple modification of your
```build.gradle```
script file with a few configuration parameters and you can:
1. Resolve your build dependencies from Artifactory
2. deploy your build artifacts and build information to Artifactory

Integration Benefits: [JFrog Artifactory and Gradle Repositories](https://jfrog.com/integration/gradle-repository/)


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

## Configurations

### Dependencies Resolution

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

### Artifactory Publication

Configure the plugin once in the project root ```build.gradle.kts``` the Artifactory information needed to upload the project artifacts into Artifactory

```kotlin
artifactory {
    publish {
        repository {
            
        }
    }
}
```

<details>
<summary>build.gradle</summary>

```groovy
artifactory {
    publish {
  
    }
}
```
</details>

#### Publication Configuration

You can configure the plugin task and choose what publications/artifacts will be included in the publications to artifactory by specifying the following object content:

```kotlin
{
    // Specify what publications to include when collecting artifacts to publish to Artifactory
    publifications(
            // Publication can be specified as Object
            publishing.publications.ivyJava,
            // Publication can be specified as String
            'mavenJava',
            // If this plguin constant string is specified the plugin will try to apply all the known publications
            'ALL_PUBLICATIONS'
    )
}
```
* specifying the object as ```artifactoryPublish``` at a specific project object - will apply only to the project it was specify in.
```kotlin
project('example') {
    artifactoryPublish {
        // content..
    }
}
```
* specifying the object as ```defaults``` object under ```publish``` in the main ```artifactory``` convention - will apply to all the projects.
```kotlin
artifactory {
    publish {
        defaults {
            // content..
        }
    }
}
```

#### Client Configurations (Optionally)

Redefine basic properties of the build info object can be applied using the ```clientConfig``` object

```kotlin
import java.util.*

artifactory {
  // Set to true to include environment variables while running the tasks (default: false)
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
  // Artifactory's connection timeout (in seconds). (default: 300 seconds).
  clientConfig.timeout = 600
  // Set to true to skip TLS certificates verification (false: default).
  clientConfig.setInsecureTls(false)  
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

We highly recommend also using our [examples](https://github.com/JFrog/project-examples/tree/master/gradle-examples?_gl=1*pgsvlz*_ga*MTc3OTI0ODE4NS4xNjYyMjgxMjI1*_ga_SQ1NR9VTFJ*MTY4NTM2OTcwMC4yNi4wLjE2ODUzNjk3MDAuNjAuMC4w) as a reference when configuring the DSL in your build scripts.

---

## 🚥 Using Gradle Artifactory plugin

To Generate buildInfo and deploy the information on artifactory run the following task
```text
gradle artifactoryPublish
```
Getting debug information from Gradle
We highly recommend running Gradle with the ``` -d ```
option to get useful and readable information if something goes wrong with your build.

---

## 💻 Contributions

We welcome pull requests from the community. To help us improve this project, please read
our [Contribution](./CONTRIBUTING.md#-guidelines) guide.