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
import java.nio.file.Path
import java.nio.file.Paths
import java.util.function.Consumer
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class IncludeMavenBuildPluginTest {

  @TempDir var testProjectDir: Path? = null

  private var projectDir: File? = null
  private var settingsFile: File? = null
  private var buildFile: File? = null

  @BeforeEach
  fun locateBuildFiles() {
    projectDir = testProjectDir!!.toFile()
    settingsFile = testProjectDir!!.resolve("settings.gradle").toFile()
    buildFile = testProjectDir!!.resolve("build.gradle").toFile()

    settingsFile!!.writeText(
      """
      buildscript {
        dependencies {
          def props = new Properties()
          file(System.getProperty('pluginClasspathFile')).withInputStream {
            props.load(it)
          }
          classpath(files(props.getProperty("implementation-classpath").split(":")))
        }
      }
      
      apply plugin: 'org.caffinitas.gradle.includemavenbuild'
      
    """.trimIndent()
    )
  }

  @Test
  fun includePrestoDependencies() {
    val p = Paths.get("../test-cases/include-presto")

    // Includes a dependency to :presto:presto-iceberg
    val result = createGradleRunner(p, ":dependencies").build()

    verifyNoPrestoSnapshotDownloads(result)
  }

  @Test
  fun includePrestoBuild() {
    val p = Paths.get("../test-cases/include-presto")

    // Includes a dependency to :presto:presto-iceberg
    val result = createGradleRunner(p, ":build").build()

    verifyNoPrestoSnapshotDownloads(result)
  }

  @Test
  fun includePrestoIcebergDependencies() {
    val p = Paths.get("../test-cases/include-iceberg-presto")

    // Verify that the dependencies from Presto to Iceberg are substituted with the included
    // Gradle build.
    val result = createGradleRunner(p, ":dependencies").build()

    verifyNoPrestoSnapshotDownloads(result)

    // Verify that Presto dependencies are properly substituted
    assertThat(
        result.output.lines().filter {
          it.contains("org.apache.iceberg:iceberg-") && !it.endsWith(" (n)")
        }
      )
      .allSatisfy(Consumer { ln -> assertThat(ln).contains(" -> project :iceberg:iceberg-") })
  }

  @Test
  fun includePrestoIcebergBuild() {
    val p = Paths.get("../test-cases/include-iceberg-presto")

    // Includes a dependency to :presto:presto-iceberg
    val result = createGradleRunner(p, ":build").build()

    verifyNoPrestoSnapshotDownloads(result)
  }

  private fun verifyNoPrestoSnapshotDownloads(result: BuildResult) {
    // Verify that no Presto dependencies are downloaded
    assertThat(
        result.output.lines().filter { it.contains("Downloaded from sonatype-nexus-snapshots") }
      )
      .allSatisfy(Consumer { ln -> assertThat(ln).doesNotContain("com.facebook.presto:presto-") })
  }

  @Test
  fun includeQuarkus() {
    val p = Paths.get("../test-cases/include-quarkus")
    var result = createGradleRunner(p, ":build").build()
  }

  private fun createGradleRunner(path: Path, task: String): GradleRunner {
    return GradleRunner.create()
      .withProjectDir(path.toFile())
      .withArguments("--rerun-tasks", "--info", "--stacktrace", task)
      .withDebug(true)
      .forwardOutput()
  }
}
