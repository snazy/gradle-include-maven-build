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

import com.diffplug.spotless.extra.wtp.EclipseWtpFormatterStep
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.extra
import org.gradle.kotlin.dsl.repositories

plugins { id("com.diffplug.spotless") }

repositories {
  gradlePluginPortal()
  mavenCentral()
  if (System.getProperty("withMavenLocal").toBoolean()) {
    mavenLocal()
  }
}

spotless {
  format("xml") {
    target("src/**/*.xml", "src/**/*.xsd")
    eclipseWtp(EclipseWtpFormatterStep.XML)
      .configFile(rootProject.projectDir.resolve("codestyle/org.eclipse.wst.xml.core.prefs"))
  }
  kotlinGradle {
    ktfmt().googleStyle()
    licenseHeaderFile(rootProject.file("codestyle/copyright-header-java.txt"), "$")
    if (project == rootProject) {
      target("*.gradle.kts", "buildSrc/*.gradle.kts")
    }
  }
  if (project == rootProject) {
    kotlin {
      ktfmt().googleStyle()
      licenseHeaderFile(rootProject.file("codestyle/copyright-header-java.txt"), "$")
      target("buildSrc/src/**/kotlin/**")
      targetExclude("buildSrc/build/**")
    }
  }

  val srcMain = projectDir.resolve("src/main")
  val srcTest = projectDir.resolve("src/test")

  if (srcMain.resolve("antlr4").exists() || srcTest.resolve("antlr4").exists()) {
    antlr4 {
      licenseHeaderFile(rootProject.file("codestyle/copyright-header-java.txt"))
      target("src/**/antlr4/**")
      targetExclude("build/**")
    }
  }
  if (srcMain.resolve("java").exists() || srcTest.resolve("java").exists()) {
    java {
      googleJavaFormat(dependencyVersion("versionGoogleJavaFormat"))
      licenseHeaderFile(rootProject.file("codestyle/copyright-header-java.txt"))
      target("src/**/java/**")
      targetExclude("build/**")
    }
  }
  if (srcMain.resolve("scala").exists() || srcTest.resolve("scala").exists()) {
    scala {
      scalafmt()
      licenseHeaderFile(
        rootProject.file("codestyle/copyright-header-java.txt"),
        "^(package|import) .*$"
      )
      target("src/**/scala/**")
      targetExclude("buildSrc/build/**")
    }
  }
  if (srcMain.resolve("kotlin").exists() || srcTest.resolve("kotlin").exists()) {
    kotlin {
      ktfmt().googleStyle()
      licenseHeaderFile(rootProject.file("codestyle/copyright-header-java.txt"), "$")
      target("src/**/kotlin/**")
      targetExclude("build/**")
    }
  }
}

fun Project.dependencyVersion(key: String) = rootProject.extra[key].toString()

tasks.withType<JavaCompile>().configureEach {
  targetCompatibility = JavaVersion.VERSION_11.toString()
}

tasks.withType<Test>().configureEach {
  useJUnitPlatform {}
  systemProperty("file.encoding", "UTF-8")
  systemProperty("user.language", "en")
  systemProperty("user.country", "US")
  systemProperty("user.variant", "")
}
