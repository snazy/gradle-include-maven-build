# Gradle plugin to include Maven builds

**PROJECT STATUS: IN DEVELOPMENT - NOT RELEASED YET**

**THIS PROJECT IS IN AN EARLY STAGE. Many things already work.**

This plugin is similar to Gradle's `includeBuild(...)` functionality, but instead can include
arbitrary Maven projects.

The intention of this plugin is to provide a way to build Maven artifacts "on demand", so when a
Gradle project really needs them, from a Maven source tree.

When Maven projects need to be built, the actual build is delegated to an embedded Maven runtime.
Leveraging Gradle's sophisticated dependency resolution and 

It is a _Settings_-plugin, which means it must be applied to a `settings.gradle[.kts]` file.

Non goals:
* Run tests in Maven projects (aka expose the whole testing infra) from Gradle.
* Manage compilation - especially incremental builds - via Gradle. Compilation is delegated to
  Maven.
* Become a replacement for _mvnd_ or multi-threaded Maven builds.

## Example

```groovy
# In your settings.gradle file...

apply plugin: 'org.caffinitas.gradle.includemavenbuild'

includeMavenBuild {
    builds {
        create('my-maven-project') {
            rootDirectory.set(file('/path/to/maven-source-tree'))
            systemProperties["skipTests"] = "true"
        }
    }
}
```

A `./gradlew projects` will show something like this:
```
> Task :projects

Root project 'some-project-name'
\--- Project ':my-maven-project'
     +--- Project ':my-maven-project:some-maven-artifact'
     +--- Project ':my-maven-project:another-maven-artifact'
    ...
```

## Things that already work

### Dependency evaluation

Dependencies of Maven projects are "passed through" to Gradle, so both dependency resolution and
related functionality works.

Example: `./gradlew :my-maven-project:some-maven-artifact:dependencies` to show the dependencies

### Outgoing variants

Jars build Maven projects are exposed via `apiElements` and `runtimeElements`.

## Known issues

* System properties configured for an included-build are currently not respected as Gradle-task-inputs.
* When a Maven project changes, only that particular project is being rebuilt. The correct behavior
  would be to build the changed Maven project and all dependant Maven projects.
* Detection of the JVM-version (the one exposed in the outgoing variants).
* Including the Quarkus source tree results in an `OutOfMemoryError`.

## FAQ

* _Where is all my verbose Maven logging output?_

  It is still there! Try `./gradlew --info ...` or `--debug` ;)
