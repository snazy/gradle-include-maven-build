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
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class IncludeMavenBuildPluginTest {

  @TempDir var testProjectDir: Path? = null

  var projectDir: File? = null
  var settingsFile: File? = null
  var buildFile: File? = null

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
  fun includePresto() {
    val p = Paths.get("../test-cases/include-presto")
    // Includes a dependency to :presto:presto-iceberg
    var result = createGradleRunner(p, ":build").build()
  }

  // @Test
  fun includeQuarkus() {
    val p = Paths.get("../test-cases/include-quarkus")
    var result = createGradleRunner(p, "help").build()
  }

  // @Test
  fun somkeTest() {
    settingsFile!!.appendText(
      """
      
      includeMavenBuild {
        builds {
          create("foobar") {
            rootDirectory.set(file('/home/snazy/devel/caffinitas/ohc'))
          }
        }
      }
    """.trimIndent()
    )

    var result = createGradleRunner(testProjectDir!!, "help").build()
  }

  private fun createGradleRunner(path: Path, task: String): GradleRunner {
    return GradleRunner.create()
      .withProjectDir(path.toFile())
      .withArguments("--build-cache", "--info", "--stacktrace", task)
      .withDebug(true)
      .forwardOutput()
  }
}
