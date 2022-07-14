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

import kotlin.test.Test
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/** A simple functional test for the 'org.caffinitas.gradle.includemavenbuild' plugin. */
class IncludeMavenBuildPluginFunctionalTest {
  @get:Rule val tempFolder = TemporaryFolder()

  private fun getProjectDir() = tempFolder.root
  private fun getBuildFile() = getProjectDir().resolve("build.gradle")
  private fun getSettingsFile() = getProjectDir().resolve("settings.gradle")

  @Test
  fun `can run task`() {
    // Setup the test build
    getSettingsFile().writeText("")
    getBuildFile().writeText("""
plugins {
    id('org.caffinitas.gradle.includemavenbuild')
}
""")

    // Run the build
    val runner = GradleRunner.create()
    runner.forwardOutput()
    runner.withPluginClasspath()
    runner.withArguments("greeting")
    runner.withProjectDir(getProjectDir())
    val result = runner.build()

    // Verify the result
    assertTrue(
      result.output.contains("Hello from plugin 'org.caffinitas.gradle.includemavenbuild'")
    )
  }
}
