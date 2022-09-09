# Gradle plugin to include Maven builds

[![Build Status](https://github.com/snazy/gradle-include-maven-build/workflows/CI/badge.svg)](https://github.com/snazy/gradle-include-maven-build/actions/workflows/main.yml)

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
* Manage compilation - especially incremental builds - in/via Gradle. Compilation is delegated to
  Maven.
* Become a replacement for _mvnd_ or multi-threaded Maven builds.

## Configuration options

See [class `IncludedMavenBuild`](./plugin/src/main/kotlin/org/caffinitas/gradle/includemavenbuild/IncludeMavenBuildExtension.kt).

## Example

```groovy
// In your settings.gradle file...

apply plugin: 'org.caffinitas.gradle.includemavenbuild'

includeMavenBuild {
    builds {
        create('my-maven-project') {
            rootDirectory.set(file('/path/to/maven-source-tree'))
            // Disable tests, checkstyle, etc
            disableDefaults()
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

Jars built by the included Maven projects are exposed via `apiElements` and `runtimeElements`.

## Known issues

* When a Maven project changes, only that particular project is being rebuilt. The correct behavior
  would be to build the changed Maven project and all dependant Maven projects.
* Detection of the JVM-version used to build the Maven artifacts to properly populate Gradle's
  outgoing variants (set the correct `org.gradle.jvm.version` attribute).
* Only the _main_ jar artifact is available as a built artifact. Test artifacts (the `-tests.jar`s)
  are not.
* Dependencies required by Maven plugins, including annotation processors used by the
  `maven-compiler-plugin` that are _built_ by the included Maven build are not detected and can
  therefore not be resolved. Would need some special handling for to match plugins and their
  configurations. 
* Huge Maven projects (like Quarkus) require bumping the Gradle heap size to at least 1g or more.

## FAQ

* _Where is all my verbose Maven logging output?_

  It is still there! Try `./gradlew --info ...` or `--debug` ;)

## Examples

### Include a [single Maven multi-project](./test-cases/include-presto)

`settings.gradle`
```groovy
apply plugin: 'org.caffinitas.gradle.includemavenbuild'

includeMavenBuild {
  builds {
    create("presto") {
      rootDirectory.set(file('../git-clones/prestodb'))
      disableDefaults()
    }
  }
}
```

`build.gradle`
```groovy
plugins {
    id('java-library')
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":presto:presto-iceberg"))
}
```

### Include a [Maven project and a Gradle project](./test-cases/include-iceberg-presto)

`settings.gradle`

```groovy
apply plugin: 'org.caffinitas.gradle.includemavenbuild'

includeMavenBuild {
  builds {
    create("presto") {
      rootDirectory.set(file('../git-clones/prestodb'))
      disableDefaults()
      // logLevel.set(1) // 0=DEBUG 1=INFO
    }
  }
}

includeBuild("../git-clones/iceberg-for-presto") {
  name = "iceberg"

  def propertyPattern = java.util.regex.Pattern.compile("\\s*(\\S+)\\s*=\\s*(\\S+)\\s*")

  // Iceberg's "dependency recommendation" stuff doesn't work when the Iceberg build is included
  // here. So parse Iceberg's versions.props file here and substitute the affected dependencies.
  def icebergVersions = [:]
  file("$projectDir/versions.props").readLines().each { line ->
    def m = propertyPattern.matcher(line.trim())
    if (m.matches()) {
      icebergVersions[m.group(1)] = m.group(2)
    }
  }
  // TODO These dependencies are pulled from
  //   'com.google.cloud:google-cloud-bom:0.164.0'
  // via
  //   'com.google.cloud:libraries-bom:24.1.0'
  // but that somehow doesn't work in this case with includedBuild + substituted dependencies
  icebergVersions['com.google.cloud:google-cloud-nio'] = "0.123.17"
  icebergVersions['com.google.cloud:google-cloud-storage'] = "2.2.2"

  dependencySubstitution {
    ["iceberg-api",
     "iceberg-bundled-guava",
     "iceberg-common",
     "iceberg-core",
     "iceberg-hive-metastore",
     "iceberg-nessie",
     "iceberg-parquet"].each {
      substitute(module("org.apache.iceberg:$it")).using(project(":$it"))
    }

    all {
      def req = requested
      if (req instanceof ModuleComponentSelector && req.version.isEmpty()) {
        var ver = icebergVersions["${req.group}:${req.module}"]
        if (ver == null) {
          ver = icebergVersions["${req.group}:*"]
        }
        if (ver != null) {
          logger.info(
                  "Iceberg - managed {}:{} with version {}",
                  req.group,
                  req.module,
                  ver
          )
          useTarget(module("${req.group}:${req.module}:${ver}"), "Managed for Iceberg")
        }
      }
    }
  }
}
```


`build.gradle`
```groovy
plugins {
    id('java-library')
}

repositories {
    mavenCentral()
}

// Resolve Presto dependencies to be resolved to the included projects.
// In practice, the following piece of code would live in some projects-conventions-plugin in
// buildSrc/ or so.
configurations.all {
    resolutionStrategy.dependencySubstitution.all {
        if (requested instanceof ModuleComponentSelector && requested.group == 'com.facebook.presto') {
            useTarget(project(":presto:${requested.module}"))
        }
    }
}

// Specify the Presto and Iceberg dependencies using the classic groupId:artifactId notation.
dependencies {
    implementation("com.facebook.presto:presto-iceberg")
    implementation("org.apache.iceberg:iceberg-core")
}
```

## Local development

To run the (integration) tests, you need to setup the source code trees in
[test-cases/git-clones/](./test-cases/git-clones) as it is done in the [CI workflow](./.github/workflows/main.yml).
Three directories are (currently) needed:
* Quarkus source tree
* PrestoDB source tree
* Apache Iceberg (compatible w/ PrestoDB)
