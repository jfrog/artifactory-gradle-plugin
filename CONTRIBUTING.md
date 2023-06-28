# 📖 Guidelines

- If the existing tests do not already cover your changes, please add tests.

---

# ⚒️ Building the plugin

To build the plugin sources, please follow these steps:

1. Clone the code from Git.
2. Build the plugin by running the following Gradle command:

```
./gradlew clean build
```


To build the code without running the tests, add to the "clean build" command the "-x test" option, for example:
```
./gradlew clean build -x test -x functionalTest
```
---

# 🧪 Testing the plugin

In order to run tests, you should have an Artifactory instance running.

The tests try to access with default values ('localHost:8081', 'admin', 'password')
overriding those values can be done by using environment variables:
```bash
export BITESTS_PLATFORM_URL='http://localhost:8081'
export BITESTS_PLATFORM_USERNAME=admin
export BITESTS_PLATFORM_ADMIN_TOKEN=admin-access-token

exeport BITESTS_ARTIFACTORY_LOCAL_REPO='some-local-repo'
exeport BITESTS_ARTIFACTORY_VIRTUAL_REPO='some-virtual-repo'
```

### Running the tests with the following command:

```bash
./gradlew clean check
```
* The above command run both unit and integration tests.

### Debugging the tests/plugin with the following command:
```
./gradlew aP -Dorg.gradle.jvmargs=-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005
```
After running the above command, you should start a remote debugging session on port 5005 and wait until the code reaches the break point.