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

import java.util.Date
import javax.inject.Inject
import org.apache.maven.execution.DefaultMavenExecutionResult
import org.apache.maven.execution.MavenSession
import org.apache.maven.lifecycle.internal.LifecycleStarter
import org.apache.maven.project.MavenProject
import org.apache.maven.session.scope.internal.SessionScope
import org.gradle.api.DefaultTask
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

// @CacheableTask
internal open class MavenBuildTask @Inject constructor(objects: ObjectFactory) : DefaultTask() {
  @Input val systemProperties = objects.mapProperty(String::class.java, String::class.java)

  @Internal val includedBuild = objects.property(IncludedMavenBuild::class.java)
  // following input's are there to make Gradle aware of these settings for up-to-date checks
  @Input val includedBuildProfiles = objects.listProperty(String::class.java)
  @Input
  val includedBuildSystemProperties = objects.mapProperty(String::class.java, String::class.java)
  @Input val includedBuildMavenPhases = objects.listProperty(String::class.java)

  @Internal val mavenProject = objects.property(MavenProject::class.java)

  @OutputFiles val outputFile = objects.fileCollection()
  @InputFiles @PathSensitive(PathSensitivity.RELATIVE) val sourceFiles = objects.fileCollection()

  @TaskAction
  fun build() {
    val inclBuild = includedBuild.get()
    val mavenPrj = mavenProject.get()

    val executionRequest = inclBuild.newExecutionRequest(project.gradle)
    executionRequest.goals = inclBuild.mavenPhases.get()
    executionRequest.isRecursive = false
    executionRequest.isShowErrors = true
    executionRequest.pom = inclBuild.rootDirectory.get().file("pom.xml").asFile

    // From org.apache.maven.DefaultMaven.doExecute()
    executionRequest.userProperties.putAll(inclBuild.systemProperties.get())
    executionRequest.userProperties.putAll(systemProperties.get())
    executionRequest.startTime = Date()

    executionRequest.selectedProjects = listOf(mavenPrj.groupArtifact())

    val container = inclBuild.container.get()

    val repoSession = inclBuild.repositorySession.get()

    val result = DefaultMavenExecutionResult()

    val discoverySession = inclBuild.projectDiscoverySession.get()
    val session = MavenSession(container, repoSession, executionRequest, result)
    session.currentProject = discoverySession.currentProject
    session.projects = listOf(mavenPrj)
    session.allProjects = discoverySession.allProjects
    session.projectDependencyGraph = discoverySession.projectDependencyGraph
    session.projectMap = discoverySession.projectMap

    // We enter the session scope right after the MavenSession creation and before any of the
    // AbstractLifecycleParticipant lookups
    // so that @SessionScoped components can be @Injected into AbstractLifecycleParticipants.
    val sessionScope = container.lookup(SessionScope::class.java)
    try {
      sessionScope.enter()
      sessionScope.seed(MavenSession::class.java, session)

      val lifecycleStarter = container.lookup(LifecycleStarter::class.java)
      lifecycleStarter.execute(session)

      if (result.hasExceptions()) {
        val ex = result.exceptions[0]
        for (i in 1 until result.exceptions.size) {
          ex.addSuppressed(result.exceptions[i])
        }
        throw ex
      }
    } finally {
      sessionScope.exit()
    }
  }

  private fun submit() {}

  private fun submitWorker() {}

  internal fun applyIncludedBuild(inclBuild: IncludedMavenBuild) {
    includedBuild.set(inclBuild)
    includedBuildProfiles.set(inclBuild.profiles.get())
    includedBuildSystemProperties.set(inclBuild.systemProperties.get())
    includedBuildMavenPhases.set(inclBuild.mavenPhases)
  }
}
