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

import java.util.*
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

  val builds = objects.domainObjectContainer(IncludedMavenBuild::class.java)

  internal val perProjectActions: MutableMap<String, GradleProjectConfigurer> = mutableMapOf()
  internal val includedGroupArtifacts: MutableMap<String, String> = mutableMapOf()
}

open class IncludedMavenBuild @Inject constructor(objects: ObjectFactory, val name: String) {
  val rootDirectory = objects.directoryProperty()

  val logLevel = objects.property(Int::class.java)

  val profiles = objects.listProperty(String::class.java)

  val systemProperties = objects.mapProperty(String::class.java, String::class.java)

  var groupArtifactToProjectPath: Transformer<String, String> = Transformer { groupArtifact ->
    ":$name:${groupArtifact.split(":")[1]}"
  }

  var groupArtifactToProjectName: Transformer<String, String> = Transformer { groupArtifact ->
    groupArtifact.split(":")[1]
  }

  internal val container = objects.property(PlexusContainer::class.java)

  internal val repositorySession = objects.property(DefaultRepositorySystemSession::class.java)

  internal val projectDiscoverySession = objects.property(MavenSession::class.java)

  internal val reactorProjects = objects.setProperty(MavenProject::class.java)

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
    executionRequest.activeProfiles = profiles.get()
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
