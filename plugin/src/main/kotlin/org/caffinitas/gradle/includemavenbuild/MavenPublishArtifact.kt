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
import java.util.Date
import org.gradle.api.internal.artifacts.publish.AbstractPublishArtifact

internal class MavenPublishArtifact
constructor(
  private val name: String,
  private val extension: String,
  private val type: String,
  private val classifier: String?,
  private val file: File,
  private val date: Date?,
  vararg tasks: Any
) : AbstractPublishArtifact(tasks) {
  override fun getName(): String = name

  override fun getExtension(): String = extension

  override fun getType(): String = type

  override fun getClassifier(): String? = classifier

  override fun getFile(): File = file

  override fun getDate(): Date? = date

  override fun shouldBePublished(): Boolean = true
}
