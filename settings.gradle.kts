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

if (!JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_11)) {
  throw GradleException("Build requires Java 11")
}

val baseVersion = file("version.txt").readText().trim()

pluginManagement {
  // Cannot use a settings-script global variable/value, so pass the 'versions' Properties via
  // settings.extra around.
  val versions = java.util.Properties()
  settings.extra["pluginBuild.versions"] = versions

  repositories {
    mavenCentral() // prefer Maven Central, in case Gradle's repo has issues
    gradlePluginPortal()
    if (System.getProperty("withMavenLocal").toBoolean()) {
      mavenLocal()
    }
  }

  val versionIdeaExtPlugin = "1.1.6"
  val versionShadowPlugin = "7.1.2"
  val versionSpotlessPlugin = "6.10.0"

  plugins {
    id("com.diffplug.spotless") version versionSpotlessPlugin
    id("com.github.johnrengelman.plugin-shadow") version versionShadowPlugin
    id("com.gradle.plugin-publish") version "1.0.0"
    id("io.github.gradle-nexus.publish-plugin") version "1.1.0"
    id("org.jetbrains.gradle.plugin.idea-ext") version versionIdeaExtPlugin

    versions["versionIdeaExtPlugin"] = versionIdeaExtPlugin
    versions["versionSpotlessPlugin"] = versionSpotlessPlugin
    versions["versionShadowPlugin"] = versionShadowPlugin

    // The project's settings.gradle.kts is "executed" before buildSrc's settings.gradle.kts and
    // build.gradle.kts.
    //
    // Plugin and important dependency versions are defined here and shared with buildSrc via
    // a properties file, and via an 'extra' property with all other modules of the build.
    //
    // This approach works fine with GitHub's dependabot as well
    val pluginVersionsFile = file("build/plugin-versions.properties")
    pluginVersionsFile.parentFile.mkdirs()
    pluginVersionsFile.outputStream().use {
      versions.store(it, "Plugin versions from settings.gradle.kts - DO NOT MODIFY!")
    }
  }
}

gradle.rootProject {
  val prj = this
  val versions = settings.extra["pluginBuild.versions"] as java.util.Properties
  versions.forEach { k, v -> prj.extra[k.toString()] = v }
}

gradle.beforeProject {
  version = baseVersion
  group = "org.caffinitas.gradle.includemavenbuild"
}

rootProject.name = "root"

fun includeProject(name: String, directory: String) {
  include(name)
  project(":$name").projectDir = file(directory)
}

includeProject("include-maven-build", "plugin")
