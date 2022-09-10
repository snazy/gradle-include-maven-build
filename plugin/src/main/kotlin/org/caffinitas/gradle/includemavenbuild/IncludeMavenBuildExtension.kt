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

package org.caffinitas.gradle.includemavenbuild

import java.util.Properties
import javax.inject.Inject
import org.apache.maven.cli.event.ExecutionEventLogger
import org.apache.maven.cli.transfer.Slf4jMavenTransferListener
import org.apache.maven.execution.DefaultMavenExecutionRequest
import org.apache.maven.execution.MavenExecutionRequest
import org.apache.maven.execution.MavenExecutionRequestPopulator
import org.apache.maven.execution.MavenSession
import org.apache.maven.project.MavenProject
import org.apache.maven.project.ProjectBuilder
import org.codehaus.plexus.DefaultContainerConfiguration
import org.codehaus.plexus.DefaultPlexusContainer
import org.codehaus.plexus.PlexusConstants
import org.codehaus.plexus.PlexusContainer
import org.codehaus.plexus.classworlds.ClassWorld
import org.eclipse.aether.DefaultRepositorySystemSession
import org.gradle.api.Transformer
import org.gradle.api.invocation.Gradle
import org.gradle.api.model.ObjectFactory
import org.gradle.internal.SystemProperties

open class IncludeMavenBuildExtension(objects: ObjectFactory) {

  /**
   * All included Maven builds. Use this using the standard Gradle accessors for named domain
   * objects like this:
   * ```
   * includeMavenBuild {
   *   builds {
   *   create('presto') {
   *   }
   * }```
   * ```
   */
  val builds = objects.domainObjectContainer(IncludedMavenBuild::class.java)

  internal val perProjectActions: MutableMap<String, GradleProjectConfigurer> = mutableMapOf()
  internal val includedGroupArtifacts: MutableMap<String, String> = mutableMapOf()
}

@Suppress("MemberVisibilityCanBePrivate", "unused")
open class IncludedMavenBuild
@Inject
constructor(
  objects: ObjectFactory,
  /** Name of the included build. */
  val name: String
) {
  val rootDirectory = objects.directoryProperty()

  /**
   * Log level used by Maven as defined by [org.codehaus.plexus.logging.Logger]. By default, it is
   * configured to match the Gradle log level.
   */
  val logLevel = objects.property(Int::class.java)

  /** Active Maven profiles. */
  val activeProfiles = objects.listProperty(String::class.java)

  /** Inactive Maven profiles. */
  val inactiveProfiles = objects.listProperty(String::class.java)

  /** User properties set for the Maven builds. */
  val userProperties = objects.mapProperty(String::class.java, String::class.java)

  /** System properties set for the Maven builds. */
  val systemProperties = objects.mapProperty(String::class.java, String::class.java)

  /** The target Maven phases. Defaults to `package`. */
  val mavenPhases = objects.listProperty(String::class.java).convention(mutableListOf("package"))

  /**
   * Name of the Gradle build file (defaults to `build.gradle`, as the default). Setting this to
   * another value can be useful if the included Maven builds already contains `build.gradle[.kts]`
   * files for its own purposes.
   */
  val gradleBuildFileName = objects.property(String::class.java).convention("build.gradle")

  /**
   * Transformer function to map a Maven `groupId:artifactId` to a Gradle project **path**. Default
   * is a string `:includedBuildName:artifactId`.
   *
   * Potentially useful to re-implement when there are multiple different `groupId`s in use and/or
   * duplicate `artifactId`s in the included build.
   *
   * **Note**: In Gradle builds, it is highly recommended to let the Gradle project name be equal to
   * the `artifactId`.
   */
  var groupArtifactToProjectPath: Transformer<String, String> = Transformer { groupArtifact ->
    ":$name:${groupArtifact.split(":")[1]}"
  }

  /**
   * Transformer function to map a Maven `groupId:artifactId` to a Gradle project **name**. Default
   * is a string `:artifactId`.
   *
   * Potentially useful to re-implement when there are multiple different `groupId`s in use and/or
   * duplicate `artifactId`s in the included build.
   *
   * **Note**: In Gradle builds, it is highly recommended to let the Gradle project name be equal to
   * the `artifactId`.
   */
  var groupArtifactToProjectName: Transformer<String, String> = Transformer { groupArtifact ->
    groupArtifact.split(":")[1]
  }

  /**
   * Disables a bunch of Maven "actions" by adding the following system properties to `true`:
   * * `skipTests`
   * * `checkstyle.skip`
   * * `license.skipCheckLicense`
   * * `enforcer.skip`
   * * `mdep.analyze.skip`
   * * `mdep.analyze.failBuild`
   */
  fun disableDefaults() {
    systemProperties.put("skipTests", "true")
    systemProperties.put("checkstyle.skip", "true")
    systemProperties.put("license.skipCheckLicense", "true")
    // TODO keep the following??
    systemProperties.put("format.skip", "true")
    systemProperties.put("enforcer.skip", "true")
    systemProperties.put("mdep.analyze.skip", "true")
    systemProperties.put("mdep.analyze.failBuild", "false")
  }

  internal val container = objects.property(PlexusContainer::class.java)

  internal val repositorySession = objects.property(DefaultRepositorySystemSession::class.java)

  internal val projectDiscoverySession = objects.property(MavenSession::class.java)

  internal val reactorProjects = objects.mapProperty(String::class.java, MavenProject::class.java)

  internal fun projectBuilder() = container.get().lookup(ProjectBuilder::class.java)

  internal fun newExecutionRequest(gradle: Gradle): MavenExecutionRequest {
    val executionRequest = DefaultMavenExecutionRequest()
    executionRequest.systemProperties =
      SystemProperties.getInstance().withSystemProperties {
        val currentProperties = Properties()
        currentProperties.putAll(System.getProperties())
        currentProperties
      }
    val populator = container.get().lookup(MavenExecutionRequestPopulator::class.java)
    // TODO Maven settings??   populator.populateFromSettings(executionRequest, settings)
    populator.populateDefaults(executionRequest)
    executionRequest.isShowErrors = true
    executionRequest.isInteractiveMode = false
    executionRequest.isOffline = gradle.startParameter.isOffline
    executionRequest.setUseReactor(true)
    activeProfiles.get().forEach { p -> executionRequest.addActiveProfile(p) }
    inactiveProfiles.get().forEach { p -> executionRequest.addInactiveProfile(p) }
    systemProperties.get().forEach { (k, v) -> executionRequest.systemProperties[k] = v }
    userProperties.get().forEach { (k, v) -> executionRequest.userProperties[k] = v }
    executionRequest.setBaseDirectory(rootDirectory.get().asFile)
    executionRequest.loggingLevel = logLevel.get()
    executionRequest.systemProperties["maven.version"] = mavenRuntimeVersion()
    executionRequest.transferListener = Slf4jMavenTransferListener()
    executionRequest.executionListener = ExecutionEventLogger()
    return executionRequest
  }

  private fun mavenRuntimeVersion(): String {
    val props = Properties()
    MavenProject::class
      .java
      .classLoader
      .getResource("META-INF/maven/org.apache.maven/maven-core/pom.properties")
      .openConnection()
      .getInputStream()
      .use { props.load(it) }
    return props.getProperty("version")
  }

  internal fun createPlexusContainer(): PlexusContainer {
    val classWorld = ClassWorld("plexus.core", ProjectBuilder::class.java.classLoader)
    val containerConfiguration =
      DefaultContainerConfiguration()
        .setClassWorld(classWorld)
        .setName("mavenCore")
        .setClassPathScanning(PlexusConstants.SCANNING_INDEX)
        .setAutoWiring(true)
    val container = DefaultPlexusContainer(containerConfiguration)
    container.loggerManager.threshold = logLevel.get()
    return container
  }
}
