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

import java.lang.IllegalStateException
import javax.inject.Inject
import org.apache.maven.project.MavenProject
import org.caffinitas.gradle.includemavenbuild.attributes.ArtifactAttributes
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.PublishArtifact
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.api.attributes.java.TargetJvmVersion
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.plugins.JavaPlatformPlugin
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.repositories
import org.gradle.util.internal.GUtil

private const val MAVEN_TASKS_GROUP = "include-maven"

private const val MAVEN_BUILD_TASK = "mavenBuild"

private const val MAVEN_PLUGINS_CONFIGURATION_NAME = "mavenPlugins"

internal open class GradleProjectConfigurer
@Inject
constructor(
  private val inclBuild: IncludedMavenBuild,
  private val mavenPrj: MavenProject,
  private val ext: IncludeMavenBuildExtension,
  private val objects: ObjectFactory
) {

  fun configure(gradleProject: Project) {
    gradleProject.plugins.apply(JavaBasePlugin::class.java)

    gradleProject.group = mavenPrj.groupId
    gradleProject.version = mavenPrj.version

    gradleProject.repositories { mavenCentral() }

    addMavenInfoTasks(gradleProject)
    addMavenBuildTask(gradleProject)

    val mavenPluginsConfiguration =
      createConfig(gradleProject, "Maven plugins", MAVEN_PLUGINS_CONFIGURATION_NAME, "main")
    mavenPluginsConfiguration.isVisible = true
    mavenPluginsConfiguration.isTransitive = true

    when (mavenPrj.packaging) {
      "pom" -> configurePomPackaging(gradleProject)
      else -> configureJarPackaging(gradleProject)
    }

    handlePluginDependencies(gradleProject)

    registerCrossProjectDependencies(gradleProject)

    ArtifactAttributes.registerCustomAttributes(gradleProject)
  }

  /**
   * Adds dependencies to the build-tasks of Maven plugins that are used _and_ built in the included
   * Maven build.
   */
  private fun handlePluginDependencies(gradleProject: Project) {
    gradleProject.logger.debug(
      "Handling dependencies of plugin in {}, {} plugins",
      gradleProject,
      mavenPrj.buildPlugins.size
    )
    for (plugin in mavenPrj.buildPlugins) {
      gradleProject.logger.debug(
        "Handling dependencies of plugin {}:{} in {}",
        plugin.groupId,
        plugin.artifactId,
        gradleProject
      )
      val pluginGroupArtifact = "${plugin.groupId}:${plugin.artifactId}"
      // Note: using the "raw" `MavenProject` here, because the Gradle `Project` that wraps
      // the plugin's Maven build might not have been initialized, so the necessary fields
      // (Plugin version) might not have been initialized. Using the `MavenProject` there solves
      // that issue.
      val pluginMavenProject = inclBuild.reactorProjects.get()[pluginGroupArtifact]
      if (pluginMavenProject != null && pluginMavenProject.version == plugin.version) {
        gradleProject.logger.debug(
          "Adding plugin build dependency for {}:{} to included project {}",
          plugin.groupId,
          plugin.artifactId,
          pluginGroupArtifact
        )
        gradleProject.dependencies.add(
          MAVEN_PLUGINS_CONFIGURATION_NAME,
          gradleProject.dependencies.project(
            mapOf("path" to ext.includedGroupArtifacts[pluginGroupArtifact])
          )
        )
      }
      for (pluginDep in plugin.dependencies) {
        val depGroupArtifact = "${pluginDep.groupId}:${pluginDep.artifactId}"
        val depMavenProject = inclBuild.reactorProjects.get()[depGroupArtifact]
        if (depMavenProject != null && depMavenProject.version == pluginDep.version) {
          gradleProject.logger.info(
            "Adding plugin dependency for {}:{} to included project {}",
            plugin.groupId,
            plugin.artifactId,
            depGroupArtifact
          )
          gradleProject.dependencies.add(
            MAVEN_PLUGINS_CONFIGURATION_NAME,
            gradleProject.dependencies.project(
              mapOf("path" to ext.includedGroupArtifacts[depGroupArtifact])
            )
          )
        }
      }
    }
  }

  /** Adds Gradle task Dependencies across Gradle projects. */
  private fun registerCrossProjectDependencies(gradleProject: Project) {
    gradleProject.tasks.named(MAVEN_BUILD_TASK).configure {
      val mavenBuildTask = this

      addDependsOnTaskInOtherProjects(mavenBuildTask, true, MAVEN_PLUGINS_CONFIGURATION_NAME)

      addDependsOnTaskInOtherProjects(
        mavenBuildTask,
        true,
        JavaPlugin.API_ELEMENTS_CONFIGURATION_NAME
      )
      addDependsOnTaskInOtherProjects(
        mavenBuildTask,
        true,
        JavaPlugin.RUNTIME_ELEMENTS_CONFIGURATION_NAME
      )
      addDependsOnTaskInOtherProjects(
        mavenBuildTask,
        true,
        JavaPlugin.TEST_RUNTIME_CLASSPATH_CONFIGURATION_NAME
      )
    }

    gradleProject.tasks.named(JavaBasePlugin.BUILD_NEEDED_TASK_NAME).configure {
      addDependsOnTaskInOtherProjects(
        this,
        true,
        JavaPlugin.TEST_RUNTIME_CLASSPATH_CONFIGURATION_NAME
      )
    }
    gradleProject.tasks.named(JavaBasePlugin.BUILD_DEPENDENTS_TASK_NAME).configure {
      addDependsOnTaskInOtherProjects(
        this,
        false,
        JavaPlugin.TEST_RUNTIME_CLASSPATH_CONFIGURATION_NAME
      )
    }
  }

  private fun addDependsOnTaskInOtherProjects(
    task: Task,
    useDependedOn: Boolean,
    configurationName: String
  ) {
    val configuration = task.project.configurations.findByName(configurationName)
    if (configuration != null) {
      task.project.logger.debug(
        "Adding task dependency for {} to task '{}' with configuration {}",
        task,
        task.name,
        configuration
      )
      task.dependsOn(configuration.getTaskDependencyFromProjectDependency(useDependedOn, task.name))
    }
  }

  private fun addMavenBuildTask(gradleProject: Project) {
    val mavenBuildTask =
      gradleProject.tasks.register(MAVEN_BUILD_TASK, MavenBuildTask::class.java) {
        group = "build"
        description =
          "Builds Maven project ${mavenPrj.groupArtifact()} using ${inclBuild.mavenPhases.get()}"
        applyIncludedBuild(inclBuild)

        mavenProject.set(mavenPrj)
      }

    gradleProject.tasks.named(JavaBasePlugin.BUILD_TASK_NAME).configure {
      dependsOn(mavenBuildTask)
    }
  }

  private fun configurePomPackaging(gradleProject: Project) {
    gradleProject.pluginManager.apply(JavaPlatformPlugin::class.java)

    if (mavenPrj.dependencyManagement != null) {
      gradleProject.dependencies.constraints {
        val depConstHandler = this
        mavenPrj.dependencyManagement.dependencies.forEach {
          val mavenDep = it
          depConstHandler.add("api", mavenDep.dependencyNotation())
        }
      }
    }
  }

  private fun configureJarPackaging(gradleProject: Project) {
    val sourceSets = gradleProject.extensions.getByType(SourceSetContainer::class.java)

    val sourceSet = sourceSets.maybeCreate("main")
    val testSourceSet = sourceSets.maybeCreate("test")

    configurationsForSourceSets(gradleProject, sourceSet, testSourceSet)

    handleDependencies(gradleProject, sourceSet, testSourceSet)

    publishedArtifacts(gradleProject, sourceSet)
  }

  private fun publishedArtifacts(gradleProject: Project, sourceSet: SourceSet) {
    val jarFile = mavenPrj.jarOutputFile()

    val mavenArtifacts =
      (listOf(mavenPrj.artifact) + mavenPrj.artifacts + mavenPrj.attachedArtifacts)

    val mavenBuildTask = gradleProject.tasks.named(MAVEN_BUILD_TASK, MavenBuildTask::class.java)
    mavenBuildTask.configure {
      sourceFiles.from(mavenPrj.file)
      sourceFiles.from(mavenPrj.compileSourceRoots)
      sourceFiles.from(mavenPrj.testCompileSourceRoots)

      outputFile.from(jarFile)
      for (artifact in mavenArtifacts) {
        outputFile.from(mavenPrj.outputFile(artifact))
      }
    }

    val jarPublishedArtifact =
      MavenPublishArtifact(
        jarFile.name,
        jarFile.extension,
        jarFile.extension,
        null,
        jarFile,
        null,
        mavenBuildTask
      )

    for (cfgName in
      listOf(sourceSet.apiElementsConfigurationName, sourceSet.runtimeElementsConfigurationName)) {
      gradleProject.configurations.getByName(cfgName).outgoing.artifacts.add(jarPublishedArtifact)
    }

    for (mavenArtifact in mavenArtifacts) {
      val file = mavenPrj.outputFile(mavenArtifact)
      val typeAndClassifier =
        "${mavenArtifact.type}${if (mavenArtifact.classifier != null) "-${mavenArtifact.classifier}" else ""}"
      val configName = GUtil.toLowerCamelCase("$typeAndClassifier-maven-artifact")
      val publishArtifact: PublishArtifact =
        MavenPublishArtifact(
          file.name,
          file.extension,
          file.extension,
          null,
          file,
          null,
          mavenBuildTask
        )
      val artifactType =
        when (mavenArtifact.artifactHandler.extension) {
          "jar" -> ArtifactTypeDefinition.JAR_TYPE
          "zip" -> ArtifactTypeDefinition.ZIP_TYPE
          else -> ArtifactTypeDefinition.BINARY_DATA_TYPE
        }
      val configuration =
        artifactElementsConfiguration(
          gradleProject,
          mavenArtifact.type,
          configName,
          sourceSet.name,
          artifactType,
        )

      configuration.outgoing.artifacts.add(publishArtifact)

      configuration.outgoing.capability(
        mapOf(
          "group" to mavenArtifact.groupId,
          "name" to "${mavenArtifact.artifactId}-$typeAndClassifier",
          "version" to mavenArtifact.version
        )
      )
      ArtifactAttributes.addCustomArtifactAttributes(configuration, mavenArtifact)
    }
  }

  private fun handleDependencies(
    gradleProject: Project,
    sourceSet: SourceSet,
    testSourceSet: SourceSet
  ) {
    // Add Maven project's parents as platform()s (aka: dependencyManagement)
    var p = mavenPrj.parent
    while (p != null) {
      gradleProject.dependencies.add(
        sourceSet.apiConfigurationName,
        gradleProject.dependencies.platform(projectOrExternal(gradleProject, p))
      )
      p = p.parent
    }

    mavenPrj.dependencies.forEach {
      val dependency =
        when (it.scope) {
          "compile" -> {
            gradleProject.dependencies.add(
              if (it.isOptional) sourceSet.compileOnlyConfigurationName
              else sourceSet.apiConfigurationName,
              projectOrExternal(gradleProject, it)
            )
          }
          "runtime" -> {
            gradleProject.dependencies.add(
              sourceSet.runtimeOnlyConfigurationName,
              projectOrExternal(gradleProject, it)
            )
          }
          "provided" -> {
            gradleProject.dependencies.add(
              sourceSet.runtimeOnlyConfigurationName,
              projectOrExternal(gradleProject, it)
            )
          }
          "test" -> {
            gradleProject.dependencies.add(
              if (it.isOptional) testSourceSet.compileOnlyConfigurationName
              else testSourceSet.apiConfigurationName,
              projectOrExternal(gradleProject, it)
            )
          }
          else ->
            throw IllegalStateException(
              "Unknown scope ${it.scope} in dependency in Maven project ${mavenPrj.id}"
            )
        }
          as ModuleDependency

      // TODO check how exclude(groupId==artifactId=="*") translates to a <type>pom</type> as
      //  for e.g. io.quarkus:quarkus-core regarding the io.quarkus.quarkus-extension-processor
      //  import.
      if (it.exclusions.any { it.groupId == "*" && it.artifactId == "*" }) {
        // Wildcard exclusion
        dependency.isTransitive = false
      } else {
        for (exclusion in it.exclusions) {
          val groupId = if (exclusion.groupId == "*") null else exclusion.groupId
          val artifactId = if (exclusion.artifactId == "*") null else exclusion.artifactId
          dependency.exclude(mapOf("group" to groupId, "module" to artifactId))
        }
      }
    }
  }

  private fun configurationsForSourceSets(
    gradleProject: Project,
    sourceSet: SourceSet,
    testSourceSet: SourceSet
  ): SourceSet {
    val configurations = gradleProject.configurations

    // main

    val apiConfiguration =
      createConfig(gradleProject, "API", sourceSet.apiConfigurationName, sourceSet.name)

    val compileOnlyApiConfiguration =
      createConfig(
        gradleProject,
        "Compile only",
        sourceSet.compileOnlyApiConfigurationName,
        sourceSet.name
      )

    jarElementsConfiguration(
        artifactElementsConfiguration(
          gradleProject,
          "API elements",
          sourceSet.apiElementsConfigurationName,
          sourceSet.name,
          ArtifactTypeDefinition.JAR_TYPE
        ),
        Usage.JAVA_API
      )
      .extendsFrom(apiConfiguration, compileOnlyApiConfiguration)

    val implementationConfiguration =
      configurations
        .getByName(sourceSet.implementationConfigurationName)
        .extendsFrom(apiConfiguration)

    configurations
      .getByName(sourceSet.compileOnlyConfigurationName)
      .extendsFrom(compileOnlyApiConfiguration)

    val runtimeOnlyConfiguration =
      gradleProject.configurations.getByName(sourceSet.runtimeOnlyConfigurationName)

    val runtimeElementsConfiguration =
      jarElementsConfiguration(
          artifactElementsConfiguration(
            gradleProject,
            "Elements of runtime",
            sourceSet.runtimeElementsConfigurationName,
            sourceSet.name,
            ArtifactTypeDefinition.JAR_TYPE
          ),
          Usage.JAVA_RUNTIME
        )
        .extendsFrom(implementationConfiguration)
        .extendsFrom(runtimeOnlyConfiguration)

    gradleProject.configurations
      .getByName(Dependency.DEFAULT_CONFIGURATION)
      .extendsFrom(runtimeElementsConfiguration)

    // test

    val testApiConfiguration =
      createConfig(
        gradleProject,
        "Test API",
        testSourceSet.apiConfigurationName,
        testSourceSet.name
      )

    val testCompileOnlyApiConfiguration =
      createConfig(
        gradleProject,
        "Test compile only",
        testSourceSet.compileOnlyApiConfigurationName,
        testSourceSet.name
      )

    configurations
      .getByName(testSourceSet.implementationConfigurationName)
      .extendsFrom(testApiConfiguration)
      .extendsFrom(implementationConfiguration)

    configurations
      .getByName(testSourceSet.compileOnlyConfigurationName)
      .extendsFrom(testCompileOnlyApiConfiguration)
      .extendsFrom(compileOnlyApiConfiguration)

    gradleProject.configurations
      .getByName(testSourceSet.runtimeOnlyConfigurationName)
      .extendsFrom(apiConfiguration)
      .extendsFrom(runtimeOnlyConfiguration)

    return sourceSet
  }

  private fun artifactElementsConfiguration(
    gradleProject: Project,
    kind: String,
    configName: String,
    name: String,
    artifactType: String
  ): Configuration {
    val configuration = createConfig(gradleProject, kind, configName, name)
    configuration.isCanBeConsumed = true

    configuration.outgoing.attributes.attribute(
      ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
      artifactType
    )

    return configuration
  }

  private fun jarElementsConfiguration(configuration: Configuration, usage: String): Configuration {
    configuration.attributes.attribute(
      Usage.USAGE_ATTRIBUTE,
      objects.named(Usage::class.java, usage)
    )
    configuration.attributes.attribute(
      Category.CATEGORY_ATTRIBUTE,
      objects.named(Category::class.java, Category.LIBRARY)
    )
    configuration.attributes.attribute(
      LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
      objects.named(LibraryElements::class.java, LibraryElements.JAR)
    )
    configuration.attributes.attribute(
      Bundling.BUNDLING_ATTRIBUTE,
      objects.named(Bundling::class.java, Bundling.EXTERNAL)
    )
    val jvmVersion = 8 // TODO inquire this somehow...
    configuration.attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, jvmVersion)
    configuration.attributes.attribute(
      TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
      objects.named(TargetJvmEnvironment::class.java, TargetJvmEnvironment.STANDARD_JVM)
    )

    return configuration
  }

  private fun projectOrExternal(
    gradleProject: Project,
    mavenDep: org.apache.maven.model.Dependency
  ): Any {
    val projectPath = ext.includedGroupArtifacts[mavenDep.groupArtifact()]
    val dependency =
      if (projectPath != null) gradleProject.dependencies.project(mapOf("path" to projectPath))
      else gradleProject.dependencies.create(mavenDep.dependencyNotation())
    return if (mavenDep.type == "pom") gradleProject.dependencies.platform(dependency)
    else dependency
  }

  private fun projectOrExternal(gradleProject: Project, mavenDep: MavenProject): Any {
    val projectPath = ext.includedGroupArtifacts[mavenDep.groupArtifact()]
    return if (projectPath != null) gradleProject.project(projectPath)
    else mavenDep.dependencyNotation()
  }

  private fun createConfig(
    gradleProject: Project,
    kind: String,
    configName: String,
    name: String
  ): Configuration {
    val cfg = gradleProject.configurations.maybeCreate(configName)
    cfg.description = "$kind dependencies for $name"
    cfg.isVisible = false
    cfg.isCanBeResolved = false
    cfg.isCanBeConsumed = false
    return cfg
  }

  private fun addMavenInfoTasks(gradleProject: Project) {
    gradleProject.tasks.register("mavenInfo") {
      group = MAVEN_TASKS_GROUP
      description = "Maven build info"

      doLast {
        val header = "Maven POM of ${mavenPrj.id} from ${mavenPrj.file}"
        println(header)
        println("-".repeat(header.length))
        println()
        println("ID:          ${mavenPrj.id}")
        println("Group ID:    ${mavenPrj.groupId}")
        println("Artifact ID: ${mavenPrj.artifactId}")
        println("Version:     ${mavenPrj.version}")
        println("Packaging:   ${mavenPrj.packaging}")
        println("POM:         ${mavenPrj.file}")
        println("Jar file:    ${mavenPrj.jarOutputFile()}")
        println("Base dir:    ${mavenPrj.basedir}")
      }
    }
    gradleProject.tasks.register("mavenPomDump") {
      group = MAVEN_TASKS_GROUP
      description = "Dump the Maven pom"

      doLast {
        val header = "Maven POM of ${mavenPrj.id} from ${mavenPrj.file}"
        println(header)
        println("-".repeat(header.length))
        println(mavenPrj.file.readText())
      }
    }
  }
}
