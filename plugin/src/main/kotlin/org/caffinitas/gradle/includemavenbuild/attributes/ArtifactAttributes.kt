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

package org.caffinitas.gradle.includemavenbuild.attributes

import org.apache.maven.artifact.Artifact
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.AttributeContainer

interface ArtifactAttributes {
  companion object {
    fun addCustomArtifactAttributes(configuration: Configuration, mavenArtifact: Artifact) {
      val attributes = configuration.outgoing.attributes
      addArtifactAttribute(attributes, ArtifactType.ARTIFACT_TYPE_ATTRIBUTE, mavenArtifact.type)
      addArtifactAttribute(
        attributes,
        ArtifactClassifier.ARTIFACT_CLASSIFIER_ATTRIBUTE,
        mavenArtifact.classifier
      )
      addArtifactAttribute(attributes, ArtifactScope.ARTIFACT_SCOPE_ATTRIBUTE, mavenArtifact.scope)
      addArtifactAttribute(
        attributes,
        ArtifactExtension.ARTIFACT_EXTENSION_ATTRIBUTE,
        mavenArtifact.artifactHandler.extension
      )
      addArtifactAttribute(
        attributes,
        ArtifactLanguage.ARTIFACT_LANGUAGE_ATTRIBUTE,
        mavenArtifact.artifactHandler.language
      )
      addArtifactAttribute(
        attributes,
        ArtifactPackaging.ARTIFACT_PACKAGING_ATTRIBUTE,
        mavenArtifact.artifactHandler.extension
      )
    }

    private fun addArtifactAttribute(
      attributeContainer: AttributeContainer,
      attribute: Attribute<String>,
      name: String?
    ) {
      if (name != null) {
        attributeContainer.attribute(attribute, name)
      }
    }

    fun registerCustomAttributes(gradleProject: Project) {
      gradleProject.dependencies.attributesSchema {
        attribute(ArtifactClassifier.ARTIFACT_CLASSIFIER_ATTRIBUTE)
        attribute(ArtifactExtension.ARTIFACT_EXTENSION_ATTRIBUTE)
        attribute(ArtifactLanguage.ARTIFACT_LANGUAGE_ATTRIBUTE)
        attribute(ArtifactPackaging.ARTIFACT_PACKAGING_ATTRIBUTE)
        attribute(ArtifactScope.ARTIFACT_SCOPE_ATTRIBUTE)
        attribute(ArtifactType.ARTIFACT_TYPE_ATTRIBUTE)
      }
    }
  }
}
