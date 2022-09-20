/*
 * Copyright (C) 2022 Robert Stupp, Koeln, Germany
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins {
  // Apply the Java Gradle plugin development plugin to add support for developing Gradle plugins
  `java-gradle-plugin`
  // Apply the Kotlin JVM plugin to add support for Kotlin.
  `kotlin-dsl`
  `maven-publish`
  alias(libs.plugins.plugin.publish)
  alias(libs.plugins.testrerun)
  `project-conventions`
}

dependencies {
  implementation(platform("org.jetbrains.kotlin:kotlin-bom"))
  implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
  implementation(libs.maven.embedder)
  implementation(libs.maven.compat)
  implementation(libs.maven.resolver.connector.basic)
  implementation(libs.maven.resolver.transport.file)
  implementation(libs.maven.resolver.transport.http)

  testImplementation(gradleTestKit())
  testImplementation(platform(libs.junit.bom))
  testImplementation("org.junit.jupiter:junit-jupiter-api")
  testImplementation("org.junit.jupiter:junit-jupiter-params")
  testImplementation(libs.assertj.core)
  testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
}

gradlePlugin {
  // Define the plugin
  val includeMavenBuildPlugin by
    plugins.creating {
      id = "org.caffinitas.gradle.includemavenbuild"
      displayName = "include-maven-build"
      description =
        "Includes a multi-module Maven project and creates Gradle projects representing every single Maven project."
      implementationClass = "org.caffinitas.gradle.includemavenbuild.IncludeMavenBuildPlugin"
    }
}

pluginBundle {
  vcsUrl = "https://github.com/snazy/gradle-include-maven-build/"
  website = "https://github.com/snazy/gradle-include-maven-build/"
  description =
    "Includes a multi-module Maven project and creates Gradle projects representing every single Maven project."
  tags = setOf("Maven", "Build")
}

// Add a source set for the functional test suite
val functionalTestSourceSet = sourceSets.create("functionalTest") {}

configurations["functionalTestImplementation"].extendsFrom(configurations["testImplementation"])

// Add a task to run the functional tests
val functionalTest by
  tasks.registering(Test::class) {
    testClassesDirs = functionalTestSourceSet.output.classesDirs
    classpath = functionalTestSourceSet.runtimeClasspath
  }

gradlePlugin.testSourceSets(functionalTestSourceSet)

val testResourcesFile = file("build/pluginUnderTestMetadata/plugin-under-test-metadata.properties")

val jar = tasks.named<Jar>("jar")

val writePluginClasspathMetadata by
  tasks.registering {
    dependsOn(jar)
    doFirst {
      testResourcesFile.parentFile.mkdirs()
      testResourcesFile.writeText(
        """
    implementation-classpath=${sourceSets.getByName("main").runtimeClasspath.files.joinToString(":")}
    """.trimIndent(
        )
      )
    }
  }

jar.configure { finalizedBy(writePluginClasspathMetadata) }

tasks.withType<Test>().configureEach {
  dependsOn(writePluginClasspathMetadata)
  minHeapSize = "2g"
  maxHeapSize = "2g"
  doFirst { systemProperty("pluginClasspathFile", testResourcesFile.absolutePath) }
  // TODO add inputs for the test-cases directories
}

tasks.named<Task>("check") {
  // Run the functional tests as part of `check`
  dependsOn(functionalTest)
}

publishing {
  publications {
    withType(MavenPublication::class.java).configureEach {
      val mavenPublication = this

      if (project.hasProperty("release")) {
        if (
          mavenPublication.name != "pluginMaven" &&
            !mavenPublication.name.endsWith("PluginMarkerMaven")
        ) {
          configure<SigningExtension> { sign(mavenPublication) }
        }
      }

      pom {
        val repoName = "gradle-include-maven-build"

        if (mavenPublication.name == "pluginMaven") {
          val pluginBundle =
            project.extensions.getByType<com.gradle.publish.PluginBundleExtension>()
          name.set(project.name)
          description.set(pluginBundle.description)
        }

        inceptionYear.set("2022")
        url.set("https://github.com/snazy/$repoName")
        organization {
          name.set("Robert Stupp, Koeln, Germany")
          url.set("https://caffinitas.org")
        }
        licenses {
          license {
            name.set("The Apache License, Version 2.0")
            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
          }
        }
        scm {
          connection.set("scm:git:https://github.com/snazy/$repoName")
          developerConnection.set("scm:git:https://github.com/snazy/$repoName")
          url.set("https://github.com/snazy/$repoName/tree/main")
          tag.set("main")
        }
        issueManagement {
          system.set("Github")
          url.set("https://github.com/snazy/$repoName/issues")
        }
        developers {
          file(rootProject.file("gradle/developers.csv"))
            .readLines()
            .map { line -> line.trim() }
            .filter { line -> line.isNotEmpty() && !line.startsWith("#") }
            .forEach { line ->
              val args = line.split(",")
              if (args.size < 3) {
                throw GradleException("gradle/developers.csv contains invalid line '${line}'")
              }
              developer {
                id.set(args[0])
                name.set(args[1])
                url.set(args[2])
              }
            }
        }
        contributors {
          file(rootProject.file("gradle/contributors.csv"))
            .readLines()
            .map { line -> line.trim() }
            .filter { line -> line.isNotEmpty() && !line.startsWith("#") }
            .forEach { line ->
              val args = line.split(",")
              if (args.size > 2) {
                throw GradleException("gradle/contributors.csv contains invalid line '${line}'")
              }
              contributor {
                name.set(args[0])
                url.set(args[1])
              }
            }
        }
      }
    }
  }
}

if (project.hasProperty("release")) {
  apply<SigningPlugin>()
  plugins.withType<SigningPlugin>().configureEach {
    configure<SigningExtension> {
      val signingKey: String? by project
      val signingPassword: String? by project
      useInMemoryPgpKeys(signingKey, signingPassword)
    }
  }
}
