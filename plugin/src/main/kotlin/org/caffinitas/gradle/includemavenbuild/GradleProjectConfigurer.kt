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

import javax.inject.Inject
import org.apache.maven.project.MavenProject
import org.caffinitas.gradle.includemavenbuild.attributes.ArtifactAttributes
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.PublishArtifact
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.api.attributes.java.TargetJvmVersion
import org.gradle.api.internal.artifacts.dsl.LazyPublishArtifact
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.plugins.JavaPlatformPlugin
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPlugin.API_ELEMENTS_CONFIGURATION_NAME
import org.gradle.api.plugins.JavaPlugin.RUNTIME_ELEMENTS_CONFIGURATION_NAME
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.repositories
import org.gradle.util.internal.GUtil

private const val MAVEN_TASKS_GROUP = "include-maven"

private const val MAVEN_PACKAGE_TASK = "mavenPackage"

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
    addMavenPackageTask(gradleProject)

    // TODO iterate over mavenPrj.artifact AND mavenPrj.attachedArtifacts -> published artifacts

    when (mavenPrj.packaging) {
      "pom" -> configurePomPackaging(gradleProject)
      else -> configureJarPackaging(gradleProject)
    }

    configureBuild(gradleProject)

    ArtifactAttributes.registerCustomAttributes(gradleProject)
  }

  private fun configureBuild(gradleProject: Project) {
    gradleProject.tasks.named(MAVEN_PACKAGE_TASK).configure {
      val mavenBuildTask = this

      addDependsOnTaskInOtherProjects(mavenBuildTask, true, Dependency.DEFAULT_CONFIGURATION)
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
    val project = task.project
    val configuration = project.configurations.findByName(configurationName)
    if (configuration != null) {
      task.dependsOn(configuration.getTaskDependencyFromProjectDependency(useDependedOn, task.name))
    }
  }

  private fun addMavenPackageTask(gradleProject: Project) {
    val mavenPackageTask =
      gradleProject.tasks.register(MAVEN_PACKAGE_TASK, MavenBuildTask::class.java) {
        group = "build"
        description = "Packages Maven project ${mavenPrj.groupArtifact()}"
        includedBuild.set(inclBuild)
        mavenProject.set(mavenPrj)
      }

    gradleProject.tasks.named(JavaBasePlugin.BUILD_TASK_NAME).configure {
      dependsOn(mavenPackageTask)
    }
  }

  private fun configurePomPackaging(gradleProject: Project) {
    gradleProject.pluginManager.apply(JavaPlatformPlugin::class.java)

    gradleProject.dependencies.constraints {
      val depConstHandler = this
      mavenPrj.dependencyManagement.dependencies.forEach {
        val mavenDep = it
        depConstHandler.add("api", mavenDep.dependencyNotion())
      }
    }
  }

  private fun configureJarPackaging(gradleProject: Project) {
    val sourceSets = gradleProject.extensions.getByType(SourceSetContainer::class.java)

    val sourceSet = configureForSourceSet(gradleProject, sourceSets.maybeCreate("main"))
    val testSourceSet = configureForSourceSet(gradleProject, sourceSets.maybeCreate("test"))

    gradleProject.configurations
      .getByName(testSourceSet.runtimeOnlyConfigurationName)
      .extendsFrom(
        gradleProject.configurations.getByName(sourceSet.apiConfigurationName),
        gradleProject.configurations.getByName(sourceSet.runtimeOnlyConfigurationName)
      )
    gradleProject.configurations
      .getByName(testSourceSet.implementationConfigurationName)
      .extendsFrom(
        gradleProject.configurations.getByName(sourceSet.implementationConfigurationName)
      )

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
          throw java.lang.IllegalStateException(
            "Unknown scope ${it.scope} in dependency in Maven project ${mavenPrj.id}"
          )
      }
    }

    //

    val jarFile = mavenPrj.jarOutputFile()

    val mavenArtifacts =
      (listOf(mavenPrj.artifact) + mavenPrj.artifacts + mavenPrj.attachedArtifacts)

    gradleProject.tasks.named(MAVEN_PACKAGE_TASK, MavenBuildTask::class.java).configure {
      sourceFiles.from(mavenPrj.file)
      sourceFiles.from(mavenPrj.compileSourceRoots)
      sourceFiles.from(mavenPrj.testCompileSourceRoots)

      outputFile.from(jarFile)
      for (artifact in mavenArtifacts) {
        outputFile.from(mavenPrj.outputFile(artifact))
      }
    }

    val fileResolver = (gradleProject as ProjectInternal).fileResolver

    val jarPublishedArtifact =
      LazyPublishArtifact(objects.fileProperty().fileValue(jarFile), fileResolver)

    jarElementsConfiguration(
      gradleProject,
      "API elements",
      API_ELEMENTS_CONFIGURATION_NAME,
      sourceSet.name,
      jarPublishedArtifact,
      Usage.JAVA_API
    )
    val runtimeElementsConfiguration =
      jarElementsConfiguration(
        gradleProject,
        "Elements of runtime",
        RUNTIME_ELEMENTS_CONFIGURATION_NAME,
        sourceSet.name,
        jarPublishedArtifact,
        Usage.JAVA_RUNTIME
      )

    runtimeElementsConfiguration.extendsFrom(
      gradleProject.configurations.getByName(sourceSet.implementationConfigurationName),
      gradleProject.configurations.getByName(sourceSet.runtimeOnlyConfigurationName)
    )

    gradleProject.configurations
      .getByName(Dependency.DEFAULT_CONFIGURATION)
      .extendsFrom(runtimeElementsConfiguration)

    for (mavenArtifact in mavenArtifacts) {
      val file = mavenPrj.outputFile(mavenArtifact)
      val typeAndClassifier =
        "${mavenArtifact.type}${if (mavenArtifact.classifier!=null) "-${mavenArtifact.classifier}" else ""}"
      val configName = GUtil.toLowerCamelCase("$typeAndClassifier-maven-artifact")
      val publishArtifact: PublishArtifact =
        LazyPublishArtifact(objects.fileProperty().fileValue(file), fileResolver)
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
          publishArtifact,
          artifactType,
        )
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

  private fun configureForSourceSet(gradleProject: Project, sourceSet: SourceSet): SourceSet {
    val configurations = gradleProject.configurations

    val apiConfiguration =
      createConfig(gradleProject, "API", sourceSet.apiConfigurationName, sourceSet.name)
    val implementationConfiguration =
      configurations.getByName(sourceSet.implementationConfigurationName)
    configurations.getByName(sourceSet.runtimeOnlyConfigurationName)
    configurations.getByName(sourceSet.compileOnlyConfigurationName)
    implementationConfiguration.extendsFrom(apiConfiguration)

    return sourceSet
  }

  private fun artifactElementsConfiguration(
    gradleProject: Project,
    kind: String,
    configName: String,
    name: String,
    publishArtifact: PublishArtifact,
    artifactType: String
  ): Configuration {
    val configuration = createConfig(gradleProject, kind, configName, name)
    configuration.isCanBeConsumed = true

    val configOutgoing = configuration.outgoing
    configOutgoing.artifacts.add(publishArtifact)
    configOutgoing.attributes.attribute(
      ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
      artifactType
    )

    return configuration
  }

  private fun jarElementsConfiguration(
    gradleProject: Project,
    kind: String,
    configName: String,
    name: String,
    jarArtifact: PublishArtifact,
    usage: String
  ): Configuration {
    val configuration =
      artifactElementsConfiguration(
        gradleProject,
        kind,
        configName,
        name,
        jarArtifact,
        ArtifactTypeDefinition.JAR_TYPE,
      )

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
    return if (projectPath != null) gradleProject.project(projectPath)
    else gradleProject.dependencies.module(mavenDep.dependencyNotion())
  }

  private fun projectOrExternal(gradleProject: Project, mavenDep: MavenProject): Any {
    val projectPath = ext.includedGroupArtifacts[mavenDep.groupArtifact()]
    return if (projectPath != null) gradleProject.project(projectPath)
    else mavenDep.dependencyNotion()
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
