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
import org.apache.maven.artifact.Artifact
import org.apache.maven.project.MavenProject

fun MavenProject.groupArtifact() = "${this.groupId}:${this.artifactId}"

fun MavenProject.dependencyNotation() =
  mapOf(
    "group" to this.groupId,
    "name" to this.artifactId,
    "version" to this.version,
  )

fun org.apache.maven.model.Dependency.groupArtifact() = "${this.groupId}:${this.artifactId}"

fun org.apache.maven.model.Dependency.dependencyNotation() =
  mapOf(
    "group" to this.groupId,
    "name" to this.artifactId,
    "version" to this.version,
    "classifier" to this.classifier
  )

fun MavenProject.jarOutputFile(): File = File("${build.directory}/${build.finalName}.jar")

fun MavenProject.outputFile(artifact: Artifact): File =
  File("${build.directory}/${artifact.getFileName()}")

fun Artifact.getFileName(): String {
  val sb = java.lang.StringBuilder()
  sb.append(this.artifactId)
  sb.append('-').append(this.version)
  if (this.classifier != null) {
    sb.append('-').append(this.classifier)
  }
  sb.append('.').append(this.artifactHandler.extension)
  return sb.toString()
}
