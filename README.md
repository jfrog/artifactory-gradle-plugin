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
- [Download and Installation](#download-and-installation)
- [Configurations](#configurations)
  - [Dependencies Resolution](#dependencies-resolution)
- [Using the Gradle Artifactory plugin](#using-gradle-artifactory-plugin)
  - [Plugin tasks](#plugin-tasks)
- [Contribution](#-contributions)

## Overview
```The minimum supported Gradle version to use this plugin is v6.6```

The Gradle Artifactory Plugin provides tight integration with Gradle. All that is needed is a simple modification of your
```build.gradle```
script file with a few configuration parameters and you can:
1. Resolve your build dependencies from Artifactory
2. deploy your build artifacts and build information to Artifactory

Integration Benefits: [JFrog Artifactory and Gradle Repositories](https://jfrog.com/integration/gradle-repository/)


## Download and Installation
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

Define the default dependency resolution from Artifactory:
```kotlin
repositories { 
    mavenCentral()
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
<summary>Groovy format</summary>

```groovy
repositories {
  mavenCentral()
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

## Using Gradle Artifactory plugin

### Plugin tasks


---
## 💻 Contributions

We welcome pull requests from the community. To help us improve this project, please read
our [Contribution](./CONTRIBUTING.md#-guidelines) guide.