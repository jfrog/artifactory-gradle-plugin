# 📖 Guidelines

- If the existing tests do not already cover your changes, please add tests.
- Pull requests are should be to the ``dev`` branch.

---

## ⚒️ Building the plugin

To build the plugin sources, please follow these steps:

1. Clone the code from Git.
2. Build the plugin by running the following Gradle command:

```bash
./gradlew clean build
```
<details>
<summary> skip tests on build </summary>

To build the code without running the tests, add the "-x" option to exclude task:
```bash
./gradlew clean build -x functionalTest
```

</details>

---

## 🧪 Testing the plugin
Run the plugin tests (without building) by running the following Gradle command:

```bash
./gradlew clean check
```
* The above command run both unit and integration tests.

In order to run tests, you should have an Artifactory instance running with the described repositories.
The tests try to access the instance with default values:
* Platform Url: `http://localhost:8081`
* UserName: `admin`
* Password/Token: `password`
* Local Repo Key: `build-info-tests-gradle-local`
* Virtual Repo Key: `build-info-tests-gradle-virtual`

<details>
<summary>overriding default test values</summary>

overriding those values can be done by using environment variables:
```bash
export BITESTS_PLATFORM_URL='http://localhost:8081'
export BITESTS_PLATFORM_USERNAME=admin
export BITESTS_PLATFORM_ADMIN_TOKEN=password
exeport BITESTS_ARTIFACTORY_LOCAL_REPO=build-info-tests-gradle-local
exeport BITESTS_ARTIFACTORY_VIRTUAL_REPO=build-info-tests-gradle-virtual
```

</details>

---
## 🐞 Debugging the plugin

To debug the plugin code (stop on break points) while running on a sample project follow these steps:
1. Make your changes and publish your them to mavenLocal by running the following command:
    ```bash
    ./gradlew clean build publishToMavenLocal
    ```
2. Add ```mavenLocal()``` repository to the closure in the build script of the sample project, make sure the project applys the plugin.
3. Run the following command in the sample project
    ```bash
    ./gradlew aP -Dorg.gradle.jvmargs=-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005
    ```
4. After running the above command, you should start a remote debugging session on port 5005 and wait until the code reaches the break point.