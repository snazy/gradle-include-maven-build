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

import java.io.File
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.function.Consumer
import javax.inject.Inject
import org.apache.maven.DuplicateProjectException
import org.apache.maven.artifact.ArtifactUtils
import org.apache.maven.artifact.repository.ArtifactRepository
import org.apache.maven.execution.DefaultMavenExecutionResult
import org.apache.maven.execution.MavenExecutionRequest
import org.apache.maven.execution.MavenSession
import org.apache.maven.execution.ProjectDependencyGraph
import org.apache.maven.graph.GraphBuilder
import org.apache.maven.internal.aether.DefaultRepositorySystemSessionFactory
import org.apache.maven.internal.aether.MavenChainedWorkspaceReader
import org.apache.maven.model.building.ModelProblem
import org.apache.maven.model.building.Result
import org.apache.maven.project.MavenProject
import org.apache.maven.project.ProjectBuildingResult
import org.apache.maven.repository.LocalRepositoryNotAccessibleException
import org.apache.maven.session.scope.internal.SessionScope
import org.codehaus.plexus.PlexusContainer
import org.codehaus.plexus.component.repository.exception.ComponentLookupException
import org.codehaus.plexus.logging.Logger
import org.eclipse.aether.DefaultRepositorySystemSession
import org.eclipse.aether.repository.WorkspaceReader
import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings
import org.gradle.api.logging.LogLevel
import org.gradle.api.model.ObjectFactory
import org.gradle.util.internal.CollectionUtils
import org.slf4j.LoggerFactory

private val LOGGER = LoggerFactory.getLogger(IncludeMavenBuildPlugin::class.java)

@Suppress("unused")
open class IncludeMavenBuildPlugin @Inject constructor(private val objects: ObjectFactory) :
  Plugin<Settings> {

  override fun apply(settings: Settings): Unit =
    settings.run {
      val ext = extensions.create("includeMavenBuild", IncludeMavenBuildExtension::class.java)

      settings.gradle.beforeProject { ext.perProjectActions[this.path]?.configure(this) }

      settings.gradle.settingsEvaluated {
        ext.builds.forEach { mavenBuild -> addMavenBuild(mavenBuild, settings, ext) }
      }
    }

  private fun Settings.addMavenBuild(
    inclBuild: IncludedMavenBuild,
    settings: Settings,
    ext: IncludeMavenBuildExtension
  ) {
    val rootDir = inclBuild.rootDirectory.get()
    val pomFile = rootDir.file("pom.xml").asFile

    LOGGER.info("Including Maven build named '{}' in '{}'", inclBuild.name, rootDir)

    inclBuild.logLevel.convention(
      settings.providers.provider {
        gradleLogLevelToPlexusLogLevel(settings.gradle.startParameter.logLevel)
      }
    )

    val container = inclBuild.createPlexusContainer()

    inclBuild.container.set(container)

    val executionRequest = inclBuild.newExecutionRequest(gradle)
    executionRequest.pom = pomFile

    validateLocalRepository(executionRequest)

    val builder = inclBuild.projectBuilder()

    val repoSession = createRepositorySession(container, executionRequest)
    inclBuild.repositorySession.set(repoSession)

    val buildingRequest = executionRequest.projectBuildingRequest
    buildingRequest.remoteRepositories.forEach(
      Consumer { repository: ArtifactRepository ->
        if (repository.id == "central") {
          repository.url = "https://repo.maven.apache.org/maven2/"
        }
      }
    )
    buildingRequest.repositorySession = repoSession

    LOGGER.info("Getting Maven build's '{}' root project ...", inclBuild.name)
    val mavenProject = builder.build(pomFile, buildingRequest).project
    LOGGER.info(
      "Included Maven build's '{}' root project is '{}'",
      inclBuild.name,
      mavenProject.groupArtifact()
    )

    val result = DefaultMavenExecutionResult()
    result.project = mavenProject

    val session = MavenSession(container, repoSession, executionRequest, result)
    session.currentProject = mavenProject
    // We enter the session scope right after the MavenSession creation and before any of the
    // AbstractLifecycleParticipant lookups
    // so that @SessionScoped components can be @Injected into AbstractLifecycleParticipants.
    val sessionScope = container.lookup(SessionScope::class.java)
    try {
      sessionScope.enter()
      sessionScope.seed(MavenSession::class.java, session)

      LOGGER.info("Getting all projects of included Maven build '{}' ...", inclBuild.name)
      val allProjects = builder.build(listOf(pomFile), true, buildingRequest)
      LOGGER.info("Got {} projects of included Maven build '{}'", allProjects.size, inclBuild.name)

      session.allProjects = allProjects.map { prj -> prj.project }.toList()
      session.projects = allProjects.map { prj -> prj.project }.toList()
      session.projectMap = getProjectMap(session.projects)

      setupWorkspaceReader(container, session, repoSession)

      val graphResult = buildGraph(container, session)
      if (graphResult.hasErrors()) {
        throw graphResult.problems.iterator().next().exception
      }

      inclBuild.projectDiscoverySession.set(session)

      val reactorProjects: MutableSet<MavenProject> = LinkedHashSet()
      reactorProjects.add(mavenProject)

      CollectionUtils.collect<MavenProject, ProjectBuildingResult, Set<MavenProject?>>(
        allProjects,
        reactorProjects
      ) { obj: ProjectBuildingResult -> obj.project }

      inclBuild.reactorProjects.set(reactorProjects)

      reactorProjects.forEach { prj -> addMavenProject(prj, inclBuild, settings, ext) }
    } finally {
      sessionScope.exit()
    }
  }

  private fun createRepositorySession(
    container: PlexusContainer,
    executionRequest: MavenExecutionRequest
  ): DefaultRepositorySystemSession {
    val repositorySystemFactory =
      container.lookup(DefaultRepositorySystemSessionFactory::class.java)
    return repositorySystemFactory.newRepositorySession(executionRequest)
  }

  private fun getProjectMap(projects: Collection<MavenProject>): Map<String, MavenProject> {
    val index = LinkedHashMap<String, MavenProject>()
    val collisions = LinkedHashMap<String, MutableList<File>>()
    for (project in projects) {
      val projectId = ArtifactUtils.key(project.groupId, project.artifactId, project.version)
      val collision = index[projectId]
      if (collision == null) {
        index[projectId] = project
      } else {
        val pomFiles = collisions[projectId]
        if (pomFiles == null) {
          collisions[projectId] = mutableListOf(collision.file, project.file)
        } else {
          pomFiles.add(project.file)
        }
      }
    }
    if (collisions.isNotEmpty()) {
      throw DuplicateProjectException(
        "Two or more projects in the reactor" +
          " have the same identifier, please make sure that <groupId>:<artifactId>:<version>" +
          " is unique for each project: " +
          collisions,
        collisions
      )
    }
    return index
  }

  private fun setupWorkspaceReader(
    container: PlexusContainer,
    session: MavenSession,
    repoSession: DefaultRepositorySystemSession
  ) {
    // Desired order of precedence for workspace readers before querying the local artifact
    // repositories
    val workspaceReaders = mutableListOf<WorkspaceReader>()
    // 1) Reactor workspace reader
    workspaceReaders.add(
      container.lookup(
        WorkspaceReader::class.java,
        "reactor" // ReactorReader.HINT - ReactorReader is package-private
      )
    )
    // 2) Repository system session-scoped workspace reader
    val repoWorkspaceReader = repoSession.workspaceReader
    if (repoWorkspaceReader != null) {
      workspaceReaders.add(repoWorkspaceReader)
    }
    // 3) .. n) Project-scoped workspace readers
    for (workspaceReader in
      getProjectScopedExtensionComponents(
        container,
        session.projects,
        WorkspaceReader::class.java
      )) {
      if (workspaceReaders.contains(workspaceReader)) {
        continue
      }
      workspaceReaders.add(workspaceReader)
    }
    repoSession.workspaceReader = MavenChainedWorkspaceReader.of(workspaceReaders)
  }

  private fun <T> getProjectScopedExtensionComponents(
    container: PlexusContainer,
    projects: Collection<MavenProject>,
    role: Class<T>
  ): Collection<T> {
    val foundComponents = java.util.LinkedHashSet<T>()
    val scannedRealms = HashSet<ClassLoader>()
    val currentThread = Thread.currentThread()
    val originalContextClassLoader = currentThread.contextClassLoader
    return try {
      for (project in projects) {
        val projectRealm = project.classRealm
        if (projectRealm != null && scannedRealms.add(projectRealm)) {
          currentThread.contextClassLoader = projectRealm
          try {
            foundComponents.addAll(container.lookupList(role))
          } catch (e: ComponentLookupException) {
            // this is just silly, lookupList should return an empty list!
            LOGGER.warn("Failed to lookup {}: {}", role, e.message)
          }
        }
      }
      foundComponents
    } finally {
      currentThread.contextClassLoader = originalContextClassLoader
    }
  }

  private fun buildGraph(
    container: PlexusContainer,
    session: MavenSession
  ): Result<out ProjectDependencyGraph> {
    val graphBuilder = container.lookup(GraphBuilder::class.java, GraphBuilder.HINT)
    val graphResult = graphBuilder.build(session)
    for (problem in graphResult.problems) {
      if (problem.severity == ModelProblem.Severity.WARNING) {
        LOGGER.warn("{}", problem.toString())
      } else {
        LOGGER.error("{}", problem.toString())
      }
    }

    if (!graphResult.hasErrors()) {
      val projectDependencyGraph: ProjectDependencyGraph = graphResult.get()
      session.projects = projectDependencyGraph.sortedProjects
      session.allProjects = projectDependencyGraph.allProjects
      session.projectDependencyGraph = projectDependencyGraph
    }

    return graphResult
  }

  private fun gradleLogLevelToPlexusLogLevel(logLevel: LogLevel): Int =
    when (logLevel) {
      LogLevel.DEBUG -> Logger.LEVEL_DEBUG
      LogLevel.INFO -> Logger.LEVEL_INFO
      LogLevel.LIFECYCLE -> Logger.LEVEL_WARN
      LogLevel.WARN -> Logger.LEVEL_WARN
      LogLevel.QUIET -> Logger.LEVEL_ERROR
      LogLevel.ERROR -> Logger.LEVEL_FATAL
      else -> Logger.LEVEL_DISABLED
    }

  private fun addMavenProject(
    mavenProject: MavenProject,
    mavenBuild: IncludedMavenBuild,
    settings: Settings,
    ext: IncludeMavenBuildExtension
  ) {
    val groupArtifact = mavenProject.groupArtifact()
    val projectPath = mavenBuild.groupArtifactToProjectPath.transform(groupArtifact)

    LOGGER.debug(
      "Including Maven build's '{}' project '{}:{}' ({}) as Gradle project path '{}'",
      mavenBuild.name,
      groupArtifact,
      mavenProject.version,
      mavenProject.packaging,
      projectPath
    )

    settings.include(projectPath)
    val gradleProject = settings.project(projectPath)
    gradleProject.projectDir = mavenProject.basedir
    gradleProject.name = mavenBuild.groupArtifactToProjectName.transform(groupArtifact)

    ext.perProjectActions[projectPath] =
      objects.newInstance(GradleProjectConfigurer::class.java, mavenBuild, mavenProject, ext)
    ext.includedGroupArtifacts[groupArtifact] = projectPath
  }

  private fun validateLocalRepository(request: MavenExecutionRequest) {
    val localRepoDir = request.localRepositoryPath
    LOGGER.debug("Using local repository at {}", localRepoDir)
    localRepoDir.mkdirs()
    if (!localRepoDir.isDirectory) {
      throw LocalRepositoryNotAccessibleException(
        "Could not create local repository at $localRepoDir"
      )
    }
  }
}
