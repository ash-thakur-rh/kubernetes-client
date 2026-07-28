/*
 * Copyright (C) 2015 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fabric8.maven.jpms;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SetJpmsModuleNameMojoTest {

  @ParameterizedTest(name = "{0}:{1} -> {2}")
  @DisplayName("derives module name from groupId and artifactId by replacing hyphens with dots")
  @CsvSource({
      "io.fabric8, kubernetes-client-api, io.fabric8.kubernetes.client.api",
      "io.fabric8, kubernetes-client, io.fabric8.kubernetes.client",
      "io.fabric8, kubernetes-model-core, io.fabric8.kubernetes.model.core",
      "io.fabric8, openshift-client, io.fabric8.openshift.client",
      "io.fabric8, zjsonpatch, io.fabric8.zjsonpatch",
      "io.fabric8, kubernetes-model-admissionregistration, io.fabric8.kubernetes.model.admissionregistration",
      "io.fabric8, certmanager-client, io.fabric8.certmanager.client",
      "io.fabric8, generator-annotations, io.fabric8.generator.annotations",
      "io.fabric8, mockwebserver, io.fabric8.mockwebserver",
  })
  void derivesModuleNameFromGroupIdAndArtifactId(String groupId, String artifactId, String expected) {
    assertThat(SetJpmsModuleNameMojo.deriveModuleName(groupId, artifactId)).isEqualTo(expected);
  }

  @Test
  @DisplayName("sets Automatic-Module-Name in a JAR that has no such entry")
  void setsModuleNameInJarWithoutEntry(@TempDir Path tempDir) throws IOException {
    Path jar = createJarWithManifest(tempDir, "test.jar", null);

    boolean changed = SetJpmsModuleNameMojo.setModuleNameInManifest(jar, "io.fabric8.test");

    assertThat(changed).isTrue();
    assertThat(readModuleNameFromJar(jar)).isEqualTo("io.fabric8.test");
  }

  @Test
  @DisplayName("does not overwrite an existing Automatic-Module-Name")
  void doesNotOverwriteExistingModuleName(@TempDir Path tempDir) throws IOException {
    Path jar = createJarWithManifest(tempDir, "test.jar", "io.fabric8.original");

    boolean changed = SetJpmsModuleNameMojo.setModuleNameInManifest(jar, "io.fabric8.new.name");

    assertThat(changed).isFalse();
    assertThat(readModuleNameFromJar(jar)).isEqualTo("io.fabric8.original");
  }

  @Test
  @DisplayName("overwrites an unresolved property placeholder in Automatic-Module-Name")
  void overwritesUnresolvedPlaceholder(@TempDir Path tempDir) throws IOException {
    Path jar = createJarWithManifest(tempDir, "test.jar", "${jpms.module.name}");

    boolean changed = SetJpmsModuleNameMojo.setModuleNameInManifest(jar, "io.fabric8.resolved");

    assertThat(changed).isTrue();
    assertThat(readModuleNameFromJar(jar)).isEqualTo("io.fabric8.resolved");
  }

  @Test
  @DisplayName("rejects derived module name with segment starting with a digit")
  void rejectsModuleNameWithDigitSegment() {
    assertThatThrownBy(() -> SetJpmsModuleNameMojo.deriveModuleName("io.fabric8", "123-start"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("invalid character");
  }

  @Test
  @DisplayName("rejects derived module name with empty segment from consecutive hyphens")
  void rejectsModuleNameWithEmptySegment() {
    assertThatThrownBy(() -> SetJpmsModuleNameMojo.deriveModuleName("io.fabric8", "module--test"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("empty segment");
  }

  @Test
  @DisplayName("preserves other manifest entries when setting module name")
  void preservesOtherManifestEntries(@TempDir Path tempDir) throws IOException {
    Path jar = createJarWithCustomManifest(tempDir, "test.jar",
        "Bundle-SymbolicName", "io.fabric8.test.bundle");

    SetJpmsModuleNameMojo.setModuleNameInManifest(jar, "io.fabric8.test");

    try (JarFile jarFile = new JarFile(jar.toFile())) {
      Manifest manifest = jarFile.getManifest();
      assertThat(manifest.getMainAttributes().getValue("Automatic-Module-Name"))
          .isEqualTo("io.fabric8.test");
      assertThat(manifest.getMainAttributes().getValue("Bundle-SymbolicName"))
          .isEqualTo("io.fabric8.test.bundle");
    }
  }

  private Path createJarWithManifest(Path dir, String name, String moduleName) throws IOException {
    Path jar = dir.resolve(name);
    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    if (moduleName != null) {
      manifest.getMainAttributes().putValue("Automatic-Module-Name", moduleName);
    }
    return writeJar(jar, manifest);
  }

  private Path createJarWithCustomManifest(Path dir, String name, String key, String value)
      throws IOException {
    Path jar = dir.resolve(name);
    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    manifest.getMainAttributes().putValue(key, value);
    return writeJar(jar, manifest);
  }

  private Path writeJar(Path jar, Manifest manifest) throws IOException {
    URI jarUri = URI.create("jar:" + jar.toUri());
    try (FileSystem zipFs = FileSystems.newFileSystem(jarUri, Map.of("create", "true"))) {
      Path manifestDir = zipFs.getPath("META-INF");
      Files.createDirectories(manifestDir);
      try (OutputStream os = Files.newOutputStream(manifestDir.resolve("MANIFEST.MF"))) {
        manifest.write(os);
      }
    }
    return jar;
  }

  private String readModuleNameFromJar(Path jar) throws IOException {
    try (JarFile jarFile = new JarFile(jar.toFile())) {
      return jarFile.getManifest().getMainAttributes().getValue("Automatic-Module-Name");
    }
  }
}
