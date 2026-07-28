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

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

@Mojo(name = "set-module-name", defaultPhase = LifecyclePhase.PACKAGE)
public class SetJpmsModuleNameMojo extends AbstractMojo {

  static final String PROPERTY_NAME = "jpms.module.name";
  static final String MANIFEST_HEADER = "Automatic-Module-Name";

  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  MavenProject project;

  @Override
  public void execute() throws MojoExecutionException {
    if ("pom".equals(project.getPackaging())) {
      return;
    }
    File artifactFile = project.getArtifact().getFile();
    if (artifactFile == null || !artifactFile.exists()) {
      getLog().debug("No artifact file for " + project.getArtifactId() + ", skipping");
      return;
    }
    String moduleName = project.getProperties().getProperty(PROPERTY_NAME);
    if (moduleName == null || moduleName.isEmpty()) {
      moduleName = deriveModuleName(project.getGroupId(), project.getArtifactId());
    }
    try {
      if (setModuleNameInManifest(artifactFile.toPath(), moduleName)) {
        getLog().info(MANIFEST_HEADER + "=" + moduleName + " set in " + artifactFile.getName());
      } else {
        getLog().debug(MANIFEST_HEADER + " already present in " + artifactFile.getName());
      }
    } catch (IOException e) {
      throw new MojoExecutionException("Failed to set " + MANIFEST_HEADER + " in " + artifactFile, e);
    }
  }

  static boolean setModuleNameInManifest(Path jarPath, String moduleName) throws IOException {
    URI jarUri = URI.create("jar:" + jarPath.toUri());
    try (FileSystem zipFs = FileSystems.newFileSystem(jarUri, Collections.emptyMap())) {
      Path manifestPath = zipFs.getPath("META-INF", "MANIFEST.MF");
      Manifest manifest;
      if (Files.exists(manifestPath)) {
        try (InputStream is = Files.newInputStream(manifestPath)) {
          manifest = new Manifest(is);
        }
      } else {
        manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
      }
      String existing = manifest.getMainAttributes().getValue(MANIFEST_HEADER);
      if (existing != null && !existing.isEmpty() && !existing.startsWith("${")) {
        return false;
      }
      manifest.getMainAttributes().putValue(MANIFEST_HEADER, moduleName);
      try (OutputStream os = Files.newOutputStream(manifestPath)) {
        manifest.write(os);
        os.flush();
      }
    }
    return true;
  }

  static String deriveModuleName(String groupId, String artifactId) {
    String name = (groupId + "." + artifactId).replace('-', '.');
    for (String segment : name.split("\\.", -1)) {
      if (segment.isEmpty()) {
        throw new IllegalArgumentException(
            "Derived module name '" + name + "' contains an empty segment");
      }
      if (!Character.isJavaIdentifierStart(segment.charAt(0))) {
        throw new IllegalArgumentException(
            "Derived module name '" + name + "' has segment '" + segment + "' starting with invalid character");
      }
      for (int i = 1; i < segment.length(); i++) {
        if (!Character.isJavaIdentifierPart(segment.charAt(i))) {
          throw new IllegalArgumentException(
              "Derived module name '" + name + "' has segment '" + segment + "' containing invalid character");
        }
      }
    }
    return name;
  }
}
