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

package io.fabric8.crdv2.generator;

import com.github.victools.jsonschema.generator.CustomDefinition;
import com.github.victools.jsonschema.generator.FieldScope;
import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.runtime.RawExtension;
import io.fabric8.kubernetes.client.utils.KubernetesSerialization;
import io.fabric8.kubernetes.client.utils.YamlDumpSettings;
import io.fabric8.kubernetes.client.utils.YamlDumpSettingsBuilder;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Encapsulates the stateful schema generation context for CRD generation.
 * Uses victools jsonschema-generator to produce JSON schemas from Java classes.
 */
public class ResolvingContext {

  final ObjectMapper objectMapper;
  final KubernetesSerialization kubernetesSerialization;
  final boolean implicitPreserveUnknownFields;

  // Captured per-property metadata during schema generation
  // Key: declaringClassName + ":" + jsonPropertyName
  private final Map<String, FieldScope> fieldScopes = new ConcurrentHashMap<>();

  // $defs from the last toJsonSchema() call
  private Map<String, ObjectNode> defs = Collections.emptyMap();

  private static ObjectMapper OBJECT_MAPPER;

  public static ResolvingContext defaultResolvingContext(boolean implicitPreserveUnknownFields) {
    return defaultResolvingContext(implicitPreserveUnknownFields, new YamlDumpSettingsBuilder().build());
  }

  public static ResolvingContext defaultResolvingContext(boolean implicitPreserveUnknownFields,
      YamlDumpSettings yamlDumpSettings) {
    if (OBJECT_MAPPER == null) {
      OBJECT_MAPPER = new ObjectMapper();
    }
    return new ResolvingContext(
        OBJECT_MAPPER,
        new KubernetesSerialization(OBJECT_MAPPER, false, yamlDumpSettings),
        implicitPreserveUnknownFields);
  }

  public ResolvingContext forkContext() {
    return new ResolvingContext(objectMapper, kubernetesSerialization, implicitPreserveUnknownFields);
  }

  public ResolvingContext(ObjectMapper mapper, KubernetesSerialization kubernetesSerialization,
      boolean implicitPreserveUnknownFields) {
    this.objectMapper = mapper;
    this.kubernetesSerialization = kubernetesSerialization;
    this.implicitPreserveUnknownFields = implicitPreserveUnknownFields;
  }

  ObjectNode toJsonSchema(Class<?> clazz) {
    // Clear state from previous generation
    fieldScopes.clear();

    SchemaGeneratorConfigBuilder configBuilder = new SchemaGeneratorConfigBuilder(
        objectMapper,
        SchemaVersion.DRAFT_2020_12,
        OptionPreset.PLAIN_JSON);

    // Jackson module to respect Jackson annotations on model classes
    configBuilder.with(new JacksonModule(JacksonOption.FLATTENED_ENUMS_FROM_JSONPROPERTY));

    // Enable options
    configBuilder.with(
        Option.MAP_VALUES_AS_ADDITIONAL_PROPERTIES,
        Option.FLATTENED_ENUMS);

    // Custom type definitions for Kubernetes special types
    configBuilder.with((javaType, context) -> {
      Class<?> raw = javaType.getErasedType();
      if (raw == IntOrString.class || raw == Quantity.class) {
        ObjectNode schema = context.getGeneratorConfig().createObjectNode();
        schema.put("x-kubernetes-int-or-string", true);
        return new CustomDefinition(schema,
            CustomDefinition.DefinitionType.INLINE,
            CustomDefinition.AttributeInclusion.NO);
      }
      if (raw == RawExtension.class || raw == GenericKubernetesResource.class) {
        ObjectNode schema = context.getGeneratorConfig().createObjectNode();
        schema.put("x-kubernetes-embedded-resource", true);
        return new CustomDefinition(schema,
            CustomDefinition.DefinitionType.INLINE,
            CustomDefinition.AttributeInclusion.NO);
      }
      if (raw == ObjectNode.class) {
        ObjectNode schema = context.getGeneratorConfig().createObjectNode();
        schema.put("type", "object");
        return new CustomDefinition(schema,
            CustomDefinition.DefinitionType.INLINE,
            CustomDefinition.AttributeInclusion.NO);
      }
      if (HasMetadata.class.isAssignableFrom(raw) && raw.isInterface()) {
        ObjectNode schema = context.getGeneratorConfig().createObjectNode();
        schema.put("x-kubernetes-embedded-resource", true);
        return new CustomDefinition(schema,
            CustomDefinition.DefinitionType.INLINE,
            CustomDefinition.AttributeInclusion.NO);
      }
      return null;
    });

    // Capture FieldScope per property for annotation access in AbstractJsonSchema
    configBuilder.forFields()
        .withInstanceAttributeOverride((propSchema, field, context) -> {
          String key = field.getDeclaringType().getErasedType().getName()
              + ":" + field.getSchemaPropertyName();
          fieldScopes.put(key, field);
        });

    SchemaGeneratorConfig config = configBuilder.build();
    SchemaGenerator generator = new SchemaGenerator(config);
    ObjectNode schema = generator.generateSchema(clazz);

    // Extract $defs for $ref resolution
    if (schema.has("$defs")) {
      ObjectNode defsNode = (ObjectNode) schema.get("$defs");
      Map<String, ObjectNode> extractedDefs = new ConcurrentHashMap<>();
      defsNode.properties().forEach(entry -> extractedDefs.put("#/$defs/" + entry.getKey(), (ObjectNode) entry.getValue()));
      this.defs = extractedDefs;
    } else {
      this.defs = Collections.emptyMap();
    }

    return schema;
  }

  FieldScope getFieldScope(String declaringClassName, String propertyName) {
    return fieldScopes.get(declaringClassName + ":" + propertyName);
  }

  Map<String, ObjectNode> getDefs() {
    return defs;
  }
}
