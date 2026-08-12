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

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.github.victools.jsonschema.generator.FieldScope;
import io.fabric8.crd.generator.annotation.AdditionalPrinterColumn;
import io.fabric8.crd.generator.annotation.AdditionalSelectableField;
import io.fabric8.crd.generator.annotation.PreserveUnknownFields;
import io.fabric8.crd.generator.annotation.PrinterColumn;
import io.fabric8.crd.generator.annotation.SchemaFrom;
import io.fabric8.crd.generator.annotation.SchemaSwap;
import io.fabric8.crd.generator.annotation.SelectableField;
import io.fabric8.crdv2.generator.InternalSchemaSwaps.SwapResult;
import io.fabric8.crdv2.generator.v1.JsonSchema.V1JSONSchemaProps;
import io.fabric8.crdv2.generator.v1.SchemaCustomizer;
import io.fabric8.generator.annotation.Default;
import io.fabric8.generator.annotation.Max;
import io.fabric8.generator.annotation.Min;
import io.fabric8.generator.annotation.Nullable;
import io.fabric8.generator.annotation.Pattern;
import io.fabric8.generator.annotation.Required;
import io.fabric8.generator.annotation.Size;
import io.fabric8.generator.annotation.ValidationRule;
import io.fabric8.generator.annotation.ValidationRules;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.apiextensions.v1.JSONSchemaProps;
import io.fabric8.kubernetes.api.model.runtime.RawExtension;
import io.fabric8.kubernetes.client.utils.Utils;
import io.fabric8.kubernetes.model.annotation.LabelSelector;
import io.fabric8.kubernetes.model.annotation.SpecReplicas;
import io.fabric8.kubernetes.model.annotation.StatusReplicas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.introspect.AnnotatedClass;
import tools.jackson.databind.introspect.BeanPropertyDefinition;
import tools.jackson.databind.introspect.ClassIntrospector;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.util.Optional.ofNullable;

/**
 * Encapsulates the common logic supporting OpenAPI schema generation for CRD generation.
 *
 * @param <T> the concrete type of the generated JSON Schema
 * @param <V> the concrete type of the validation rule
 */
public abstract class AbstractJsonSchema<T extends KubernetesJSONSchemaProps, V extends KubernetesValidationRule> {

  private static final Logger logger = LoggerFactory.getLogger(AbstractJsonSchema.class);

  private final ResolvingContext resolvingContext;
  private final T root;
  private final Set<String> dependentClasses = new HashSet<>();
  private final Set<AdditionalPrinterColumn> additionalPrinterColumns = new HashSet<>();
  private final Set<AdditionalSelectableField> additionalSelectableFields = new HashSet<>();

  public static class AnnotationMetadata {
    public final Annotation annotation;
    public final KubernetesJSONSchemaProps schema;

    public AnnotationMetadata(Annotation annotation, KubernetesJSONSchemaProps schema) {
      this.annotation = annotation;
      this.schema = schema;
    }
  }

  private final Map<Class<? extends Annotation>, LinkedHashMap<String, AnnotationMetadata>> pathMetadata = new HashMap<>();

  public AbstractJsonSchema(ResolvingContext resolvingContext, Class<?> def) {
    this.resolvingContext = resolvingContext;
    // TODO: could make this configurable, and could stop looking for single valued ones - or warn
    Stream.of(
        SpecReplicas.class,
        StatusReplicas.class,
        LabelSelector.class,
        PrinterColumn.class,
        SelectableField.class).forEach(clazz -> pathMetadata.put(clazz, new LinkedHashMap<>()));

    this.root = resolveRoot(def);
  }

  public T getSchema() {
    return root;
  }

  public Set<String> getDependentClasses() {
    return dependentClasses;
  }

  public Optional<String> getSinglePath(Class<? extends Annotation> clazz) {
    return ofNullable(pathMetadata.get(clazz)).flatMap(m -> m.keySet().stream().findFirst());
  }

  public Map<String, AnnotationMetadata> getAllPaths(Class<? extends Annotation> clazz) {
    return ofNullable(pathMetadata.get(clazz)).orElse(new LinkedHashMap<>());
  }

  /**
   * Creates the JSON schema for the class. This is template method where
   * subclasses are supposed to provide specific implementations of abstract methods.
   *
   * @param definition The definition.
   * @return The schema.
   */
  private T resolveRoot(Class<?> definition) {
    InternalSchemaSwaps schemaSwaps = new InternalSchemaSwaps();
    ObjectNode schema = resolvingContext.toJsonSchema(definition);
    consumeRepeatingAnnotation(definition, AdditionalPrinterColumn.class,
        additionalPrinterColumns::add);
    consumeRepeatingAnnotation(definition, AdditionalSelectableField.class,
        additionalSelectableFields::add);

    // Resolve top-level $ref (victools may emit $ref at the root for some types)
    JsonNode resolved = resolveRef(schema);
    if (resolved != schema && resolved instanceof ObjectNode) {
      schema = (ObjectNode) resolved;
    }

    String type = resolveSchemaType(schema);
    if ("object".equals(type)) {
      return resolveObject(new LinkedHashMap<>(), schemaSwaps, schema, definition,
          "kind", "apiVersion", "metadata");
    }
    JavaType javaType = resolvingContext.objectMapper.serializationConfig()
        .constructType(definition);
    return resolveProperty(new LinkedHashMap<>(), schemaSwaps, null, javaType, schema, null);
  }

  /**
   * Resolves the schema type from a schema node, handling both scalar type values
   * and array type values (e.g. ["string", "null"] for Optional types).
   */
  private static String resolveSchemaType(ObjectNode schemaNode) {
    JsonNode typeNode = schemaNode.get("type");
    if (typeNode == null) {
      return "";
    }
    if (typeNode.isString()) {
      return typeNode.asString();
    }
    if (typeNode.isArray()) {
      // Multi-type (e.g., ["string", "null"]) - return the first non-null type
      for (JsonNode t : typeNode) {
        String tv = t.asString();
        if (!"null".equals(tv)) {
          return tv;
        }
      }
      return "null";
    }
    return "";
  }

  /**
   * Walks up the class hierarchy to consume the repeating annotation
   */
  private static <A extends Annotation> void consumeRepeatingAnnotation(Class<?> beanClass, Class<A> annotation,
      Consumer<A> consumer) {
    while (beanClass != null && beanClass != Object.class) {
      Stream.of(beanClass.getAnnotationsByType(annotation)).forEach(consumer);
      beanClass = beanClass.getSuperclass();
    }
  }

  /**
   * Walks up the class hierarchy to find the first (most specific) occurrence of the annotation.
   */
  private static <A extends Annotation> A findClassAnnotation(Class<?> beanClass, Class<A> annotation) {
    while (beanClass != null && beanClass != Object.class) {
      A found = beanClass.getAnnotation(annotation);
      if (found != null) {
        return found;
      }
      beanClass = beanClass.getSuperclass();
    }
    return null;
  }

  void collectValidationRules(FieldScope fieldScope, Class<?> ownerClass, List<V> validationRules) {
    if (fieldScope == null) {
      return;
    }
    // Collect from the field itself
    Field rawField = fieldScope.getRawMember();
    collectValidationAnnotations(rawField, validationRules);

    // Also collect from the getter method (if present and different from field annotations).
    // getAnnotationConsideringFieldAndGetter returns one or the other, but when both field
    // and getter carry @ValidationRule, both sets must be included.
    // Use ownerClass (the most specific class being processed) to find getters that may
    // override the field's declaring class getter (e.g., K8sValidation.getSpec() overrides
    // CustomResource.getSpec() with additional validation rules).
    String fieldName = rawField.getName();
    String getterName = "get" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
    Class<?> lookupClass = ownerClass != null ? ownerClass : rawField.getDeclaringClass();
    try {
      Method getter = lookupClass.getMethod(getterName);
      collectValidationAnnotations(getter, validationRules);
    } catch (NoSuchMethodException e) {
      // Try boolean getter pattern
      if (rawField.getType() == boolean.class || rawField.getType() == Boolean.class) {
        String isGetterName = "is" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        try {
          Method getter = lookupClass.getMethod(isGetterName);
          collectValidationAnnotations(getter, validationRules);
        } catch (NoSuchMethodException e2) {
          // No getter found, nothing to do
        }
      }
    }
  }

  private void collectValidationAnnotations(AnnotatedElement element, List<V> validationRules) {
    ValidationRules container = element.getAnnotation(ValidationRules.class);
    if (container != null) {
      Stream.of(container.value()).map(this::from).forEach(validationRules::add);
    } else {
      ofNullable(element.getAnnotation(ValidationRule.class)).map(this::from)
          .ifPresent(validationRules::add);
    }
  }

  class PropertyMetadata {

    private boolean required;
    private final String description;
    private final JsonNode defaultValue;
    private Double min;
    private Boolean exclusiveMinimum;
    private Double max;
    private Boolean exclusiveMaximum;
    private String pattern;
    private Long minLength;
    private Long maxLength;
    private Long minItems;
    private Long maxItems;
    private Long minProperties;
    private Long maxProperties;
    private boolean nullable;
    private String format;
    private List<V> validationRules = new ArrayList<>();
    private boolean preserveUnknownFields;
    private Class<?> schemaFrom;

    public PropertyMetadata(ObjectNode schemaNode, FieldScope fieldScope, Class<?> ownerClass) {
      required = fieldScope.getAnnotationConsideringFieldAndGetter(Required.class) != null;

      description = ofNullable(fieldScope.getAnnotationConsideringFieldAndGetter(JsonPropertyDescription.class))
          .map(JsonPropertyDescription::value)
          .orElse(null);

      schemaFrom = ofNullable(fieldScope.getAnnotationConsideringFieldAndGetter(SchemaFrom.class))
          .map(SchemaFrom::type).orElse(null);
      preserveUnknownFields = fieldScope.getAnnotationConsideringFieldAndGetter(
          PreserveUnknownFields.class) != null;

      String schemaType = resolveSchemaType(schemaNode);
      this.format = schemaNode.path("format").asString(null);

      if ("string".equals(schemaType)) {
        pattern = ofNullable(fieldScope.getAnnotationConsideringFieldAndGetter(Pattern.class))
            .map(Pattern::value)
            .or(() -> ofNullable(schemaNode.path("pattern").asString(null)))
            .orElse(null);
        minLength = findMinInSizeAnnotation(fieldScope)
            .or(() -> ofNullable(schemaNode.has("minLength") ? (long) schemaNode.get("minLength").intValue() : null))
            .orElse(null);
        maxLength = findMaxInSizeAnnotation(fieldScope)
            .or(() -> ofNullable(schemaNode.has("maxLength") ? (long) schemaNode.get("maxLength").intValue() : null))
            .orElse(null);
      } else if ("integer".equals(schemaType) || "number".equals(schemaType)) {
        Double minimum = schemaNode.has("minimum") ? schemaNode.get("minimum").doubleValue() : null;
        Boolean exclMin = schemaNode.has("exclusiveMinimum") ? schemaNode.get("exclusiveMinimum").booleanValue() : null;
        Double maximum = schemaNode.has("maximum") ? schemaNode.get("maximum").doubleValue() : null;
        Boolean exclMax = schemaNode.has("exclusiveMaximum") ? schemaNode.get("exclusiveMaximum").booleanValue() : null;
        setMinMax(fieldScope, minimum, exclMin, maximum, exclMax);
      } else if ("array".equals(schemaType)) {
        minItems = findMinInSizeAnnotation(fieldScope)
            .or(() -> ofNullable(schemaNode.has("minItems") ? (long) schemaNode.get("minItems").intValue() : null))
            .orElse(null);
        maxItems = findMaxInSizeAnnotation(fieldScope)
            .or(() -> ofNullable(schemaNode.has("maxItems") ? (long) schemaNode.get("maxItems").intValue() : null))
            .orElse(null);
      } else if ("object".equals(schemaType)) {
        // TODO: Could be also applied only on Maps instead of "all the rest"
        minProperties = findMinInSizeAnnotation(fieldScope).orElse(null);
        maxProperties = findMaxInSizeAnnotation(fieldScope).orElse(null);
      }

      collectValidationRules(fieldScope, ownerClass, validationRules);

      // TODO: should probably move to a standard annotations
      nullable = fieldScope.getAnnotationConsideringFieldAndGetter(Nullable.class) != null;

      // TODO: should the following be deprecated?
      required = fieldScope.getAnnotationConsideringFieldAndGetter(Required.class) != null;
      defaultValue = toDefault(fieldScope);
    }

    JsonNode toDefault(FieldScope fieldScope) {
      // @Default annotation takes precedence
      Optional<String> defaultAnnotationValue = ofNullable(
          fieldScope.getAnnotationConsideringFieldAndGetter(Default.class)).map(Default::value);

      // Fall back to @JsonProperty(defaultValue = "...")
      Optional<String> jsonPropertyDefault = ofNullable(
          fieldScope.getAnnotationConsideringFieldAndGetter(JsonProperty.class))
          .map(JsonProperty::defaultValue)
          .filter(v -> !v.isEmpty());

      boolean fromDefaultAnnotation = defaultAnnotationValue.isPresent();
      String value = defaultAnnotationValue.or(() -> jsonPropertyDefault).orElse(null);

      if (value == null) {
        return null;
      }
      // Use Java reflection for the declared field type (victools may unwrap arrays/collections)
      Class<?> rawType = fieldScope.getRawMember().getType();
      try {
        if (rawType == String.class) {
          // Strings don't need JSON parsing - use the value directly
          return tools.jackson.databind.node.StringNode.valueOf(value);
        }
        // For non-string types, parse as JSON
        Object typedValue = resolvingContext.objectMapper.readValue(value, rawType);
        return resolvingContext.objectMapper.convertValue(typedValue, JsonNode.class);
      } catch (Exception e) {
        if (fromDefaultAnnotation) {
          throw new IllegalArgumentException("Cannot parse default value: '" + value + "' as valid YAML or JSON.", e);
        }
        // For @JsonProperty(defaultValue), silently ignore invalid values
        return null;
      }
    }

    private void setMinMax(FieldScope fieldScope,
        Double minimum, Boolean exclusiveMinimum, Double maximum, Boolean exclusiveMaximum) {
      ofNullable(minimum).ifPresent(v -> {
        this.min = v;
        if (Boolean.TRUE.equals(exclusiveMinimum)) {
          this.exclusiveMinimum = true;
        }
      });
      ofNullable(fieldScope.getAnnotationConsideringFieldAndGetter(Min.class)).ifPresent(a -> {
        min = a.value();
        if (!a.inclusive()) {
          this.exclusiveMinimum = true;
        }
      });
      ofNullable(maximum).ifPresent(v -> {
        this.max = v;
        if (Boolean.TRUE.equals(exclusiveMaximum)) {
          this.exclusiveMaximum = true;
        }
      });
      ofNullable(fieldScope.getAnnotationConsideringFieldAndGetter(Max.class)).ifPresent(a -> {
        this.max = a.value();
        if (!a.inclusive()) {
          this.exclusiveMaximum = true;
        }
      });
    }

    public void updateSchema(T schema) {
      if (Utils.isNotNullOrEmpty(description)) {
        schema.setDescription(description);
      }
      schema.setDefault(ResolvingContext.toJackson2(defaultValue));
      if (nullable) {
        schema.setNullable(true);
      }
      schema.setMaximum(max);
      schema.setExclusiveMaximum(exclusiveMaximum);
      schema.setMinimum(min);
      schema.setExclusiveMinimum(exclusiveMinimum);

      schema.setMinLength(minLength);
      schema.setMaxLength(maxLength);

      schema.setMinItems(minItems);
      schema.setMaxItems(maxItems);

      schema.setMinProperties(minProperties);
      schema.setMaxProperties(maxProperties);

      schema.setPattern(pattern);
      schema.setFormat(format);
      if (preserveUnknownFields) {
        schema.setXKubernetesPreserveUnknownFields(true);
      }

      addToValidationRules(schema, validationRules);
    }

    private Optional<Long> findMinInSizeAnnotation(FieldScope fieldScope) {
      return ofNullable(fieldScope.getAnnotationConsideringFieldAndGetter(Size.class))
          .map(Size::min)
          .filter(v -> v > 0);
    }

    private Optional<Long> findMaxInSizeAnnotation(FieldScope fieldScope) {
      return ofNullable(fieldScope.getAnnotationConsideringFieldAndGetter(Size.class))
          .map(Size::max)
          .filter(v -> v < Long.MAX_VALUE);
    }
  }

  private T resolveObject(LinkedHashMap<String, String> visited, InternalSchemaSwaps schemaSwaps,
      ObjectNode schemaNode, Class<?> rawClass, String... ignore) {

    Set<String> ignores = ignore.length > 0 ? new LinkedHashSet<>(Arrays.asList(ignore)) : Collections.emptySet();

    T objectSchema = singleProperty("object");

    schemaSwaps = schemaSwaps.branchAnnotations();
    final InternalSchemaSwaps swaps = schemaSwaps;

    SerializationConfig config = resolvingContext.objectMapper.serializationConfig();
    JavaType javaType = config.constructType(rawClass);
    ClassIntrospector ci = config.classIntrospectorInstance().forOperation(config);
    AnnotatedClass ac = ci.introspectClassAnnotations(javaType);
    BeanDescription bd = ci.introspectForSerialization(javaType, ac);
    boolean preserveUnknownFields = false;
    if (resolvingContext.implicitPreserveUnknownFields) {
      preserveUnknownFields = bd.findAnyGetter() != null || bd.findAnySetterAccessor() != null;
    }

    Map<String, JavaType> propertyTypes = new HashMap<>();
    for (BeanPropertyDefinition bpd : bd.findProperties()) {
      propertyTypes.put(bpd.getName(), bpd.getPrimaryType());
    }

    collectDependentClasses(rawClass);

    JsonClassDescription classDescription = findClassAnnotation(rawClass, JsonClassDescription.class);
    if (classDescription != null && Utils.isNotNullOrEmpty(classDescription.value())) {
      objectSchema.setDescription(classDescription.value());
    }

    // while it should not be repeating, we reuse this method to look for preserve unknown on the class hierarchy
    consumeRepeatingAnnotation(rawClass, PreserveUnknownFields.class,
        ignored -> objectSchema.setXKubernetesPreserveUnknownFields(true));

    consumeRepeatingAnnotation(rawClass, SchemaSwap.class, ss -> {
      swaps.registerSwap(rawClass,
          ss.originalType(),
          ss.fieldName(),
          ss.targetType(), ss.depth());
    });

    List<String> required = new ArrayList<>();

    // Pre-cache field scopes before iterating, because toJsonSchema calls during
    // schema swaps clear the fieldScopes map in ResolvingContext
    Map<String, FieldScope> cachedFieldScopes = new LinkedHashMap<>();

    // Safely extract properties node - may be missing for classes with no serializable properties
    JsonNode propertiesNode = schemaNode.get("properties");
    ObjectNode properties = propertiesNode instanceof ObjectNode ? (ObjectNode) propertiesNode : null;
    if (properties != null) {
      for (var entry : nodeToMap(properties).entrySet()) {
        FieldScope fs = resolvingContext.getFieldScope(rawClass, entry.getKey());
        if (fs != null) {
          cachedFieldScopes.put(entry.getKey(), fs);
        }
      }

      for (var it = new TreeMap<>(nodeToMap(properties)).entrySet().iterator(); it.hasNext();) {
        var property = it.next();
        String name = property.getKey();
        if (ignores.contains(name)) {
          continue;
        }
        schemaSwaps = schemaSwaps.branchDepths();
        SwapResult swapResult = schemaSwaps.lookupAndMark(rawClass, name);
        LinkedHashMap<String, String> savedVisited = visited;
        if (swapResult.onGoing) {
          visited = new LinkedHashMap<>();
        }

        final FieldScope fieldScope = cachedFieldScopes.get(name);
        if (fieldScope == null) {
          continue; // skip properties we couldn't introspect
        }
        if (fieldScope.getAnnotationConsideringFieldAndGetter(JsonIgnore.class) != null) {
          continue;
        }

        // Resolve $ref to get the actual property schema
        JsonNode resolvedPropertyNode = resolveRef(property.getValue());
        ObjectNode propertySchemaNode = resolvedPropertyNode instanceof ObjectNode
            ? (ObjectNode) resolvedPropertyNode
            : property.getValue() instanceof ObjectNode ? (ObjectNode) property.getValue()
                : resolvingContext.objectMapper.createObjectNode();
        PropertyMetadata propertyMetadata = new PropertyMetadata(propertySchemaNode, fieldScope, rawClass);

        if (propertyMetadata.required) {
          required.add(name);
        }

        // Use BeanDescription property types (with proper generics) over victools erased types
        JavaType propertyType = propertyTypes.getOrDefault(name,
            config.constructType(fieldScope.getType().getErasedType()));
        if (swapResult.classRef != null) {
          propertyMetadata.schemaFrom = swapResult.classRef;
        }
        if (propertyMetadata.schemaFrom != null) {
          if (propertyMetadata.schemaFrom == void.class) {
            // fully omit - this is a little inconsistent with the NullSchema handling
            continue;
          }
          // Use toJsonSchemaForSwap to preserve the primary schema's $defs and rootSchema
          propertySchemaNode = resolvingContext.toJsonSchemaForSwap(propertyMetadata.schemaFrom);
          propertyType = config.constructType(propertyMetadata.schemaFrom);
        }

        T schema = resolveProperty(visited, schemaSwaps, name, propertyType, propertySchemaNode, fieldScope);

        propertyMetadata.updateSchema(schema);

        if (!swapResult.onGoing) {
          for (Entry<Class<? extends Annotation>, LinkedHashMap<String, AnnotationMetadata>> entry2 : pathMetadata
              .entrySet()) {
            ofNullable(fieldScope.getAnnotationConsideringFieldAndGetter(entry2.getKey())).ifPresent(
                ann -> entry2.getValue().put(toFQN(savedVisited, name),
                    new AnnotationMetadata(ann, schema)));
          }
        }

        visited = savedVisited;

        addProperty(name, objectSchema, schema);
      }
    }

    swaps.throwIfUnmatchedSwaps();

    objectSchema.setRequired(required);
    if (preserveUnknownFields) {
      objectSchema.setXKubernetesPreserveUnknownFields(true);
    }
    List<V> validationRules = new ArrayList<>();
    consumeRepeatingAnnotation(rawClass, ValidationRule.class,
        v -> validationRules.add(from(v)));
    addToValidationRules(objectSchema, validationRules);
    return handleSchemaCustomizer(objectSchema, rawClass);
  }

  private T handleSchemaCustomizer(T objectSchema, Class<?> rawClass) {
    if (objectSchema instanceof JSONSchemaProps) {
      JSONSchemaProps[] props = new JSONSchemaProps[] { (JSONSchemaProps) objectSchema };
      consumeRepeatingAnnotation(rawClass, SchemaCustomizer.class, sc -> {
        try {
          props[0] = sc.value().getConstructor().newInstance().apply(props[0], sc.input(),
              this.resolvingContext.kubernetesSerialization);
        } catch (ReflectiveOperationException e) {
          throw new RuntimeException("Failed to instantiate or apply SchemaCustomizer: " + sc.value().getName(), e);
        } catch (RuntimeException e) {
          throw new RuntimeException("Failed to apply SchemaCustomizer: " + sc.value().getName(), e);
        }
      });
      if (props[0] != objectSchema) {
        // hack to convert back to V1JSONSchemaProps
        objectSchema = (T) resolvingContext.kubernetesSerialization.convertValue(props[0], V1JSONSchemaProps.class);
      }
    }
    return objectSchema;
  }

  private void collectDependentClasses(Class<?> rawClass) {
    if (rawClass != null && !rawClass.getName().startsWith("java.") && dependentClasses.add(rawClass.getName())) {
      Stream.of(rawClass.getInterfaces()).forEach(this::collectDependentClasses);
      collectDependentClasses(rawClass.getSuperclass());
    }
  }

  static String toFQN(LinkedHashMap<String, String> visited, String name) {
    if (visited.isEmpty()) {
      return "." + name;
    }
    return visited.values().stream().collect(Collectors.joining(".", ".", ".")) + name;
  }

  /**
   * Resolves a JSON $ref node by looking up the referenced definition.
   * Handles both $defs references and "#" self-references.
   */
  private JsonNode resolveRef(JsonNode node) {
    if (node.has("$ref")) {
      String ref = node.get("$ref").asString();
      if ("#".equals(ref)) {
        // Self-reference to the root schema
        ObjectNode rootSchema = resolvingContext.getRootSchema();
        if (rootSchema != null) {
          return rootSchema;
        }
      }
      ObjectNode resolved = resolvingContext.getDefs().get(ref);
      if (resolved != null) {
        return resolved;
      }
    }
    return node;
  }

  /**
   * Converts an ObjectNode to a Map of property name to JsonNode.
   */
  private static Map<String, JsonNode> nodeToMap(ObjectNode node) {
    Map<String, JsonNode> map = new LinkedHashMap<>();
    node.properties().forEach(e -> map.put(e.getKey(), e.getValue()));
    return map;
  }

  private T resolveProperty(LinkedHashMap<String, String> visited, InternalSchemaSwaps schemaSwaps, String name,
      JavaType type, ObjectNode schemaNode, FieldScope fieldScope) {

    // Resolve $ref first
    JsonNode resolved = resolveRef(schemaNode);
    if (resolved instanceof ObjectNode) {
      schemaNode = (ObjectNode) resolved;
    }

    String schemaType = resolveSchemaType(schemaNode);

    if ("array".equals(schemaType)) {
      JsonNode items = schemaNode.path("items");
      if (items.isMissingNode()) {
        throw new IllegalStateException(String.format("Untyped collection %s", name));
      }
      JsonNode resolvedItems = resolveRef(items);
      ObjectNode arrayItemSchema = resolvedItems instanceof ObjectNode
          ? (ObjectNode) resolvedItems
          : resolvingContext.objectMapper.createObjectNode();
      // Get the content type; if null (victools may resolve to element type), use type directly
      JavaType contentType = type != null ? type.getContentType() : null;
      if (contentType == null) {
        // Fallback: construct from schema or use Object
        contentType = type != null ? type : resolvingContext.objectMapper.serializationConfig().constructType(Object.class);
      }
      final T schema = resolveProperty(visited, schemaSwaps, name,
          contentType, arrayItemSchema, null);
      handleTypeAnnotations(schema, fieldScope, List.class, 0);
      return arrayLikeProperty(schema);
    } else if ("integer".equals(schemaType)) {
      return singleProperty("integer");
    } else if ("number".equals(schemaType)) {
      return singleProperty("number");
    } else if ("boolean".equals(schemaType)) {
      return singleProperty("boolean");
    } else if ("string".equals(schemaType)) {
      JsonNode enumNode = schemaNode.path("enum");
      if (type != null && type.isEnumType()) {
        // Build enum values from the Java enum using Jackson serialization.
        // This correctly handles @JsonProperty renaming and @JsonIgnore filtering,
        // which victools' FLATTENED_ENUMS may not do accurately.
        // Also handles cases where victools doesn't generate enum values at all
        // (e.g., for package-private inner enums).
        final JsonNode[] enumValues = buildEnumValuesFromJavaType(type);
        if (enumValues.length > 0) {
          return enumProperty(enumValues);
        }
      } else if (!enumNode.isMissingNode() && enumNode.isArray()) {
        final JsonNode[] enumValues = StreamSupport
            .stream(enumNode.spliterator(), false)
            .map(JsonNode::asString)
            .sorted()
            .map(JsonNodeFactory.instance::stringNode)
            .toArray(JsonNode[]::new);
        return enumProperty(enumValues);
      }
      return singleProperty("string");
    } else if ("null".equals(schemaType)) {
      return singleProperty("object"); // TODO: this may not be the right choice, but rarely will someone be using Void
    } else if (schemaNode.has("x-kubernetes-int-or-string")) {
      return intOrString();
    } else if (schemaNode.has("x-kubernetes-embedded-resource")) {
      return raw();
    } else if (schemaNode.has("x-kubernetes-preserve-unknown-fields")) {
      T schema = singleProperty(null);
      schema.setXKubernetesPreserveUnknownFields(true);
      return schema;
    } else if (type != null && type.isMapLikeType()
        && (schemaNode.has("additionalProperties") || "object".equals(schemaType) || schemaType.isEmpty())) {
      // Map-like type: detect by Java type. victools may emit additionalProperties, or just
      // {"type":"object"} for maps with Object/raw values, or even an empty schema for some cases.
      final JavaType keyType = type.getKeyType();
      final JavaType valueType = type.getContentType();

      if (keyType != null && keyType.getRawClass() != String.class) {
        logger.warn("Property '{}' with '{}' key type is mapped to 'string' because of CRD schemas limitations", name,
            keyType);
      }

      JsonNode additionalPropsNode = resolveRef(schemaNode.path("additionalProperties"));
      ObjectNode additionalProps = additionalPropsNode instanceof ObjectNode
          ? (ObjectNode) additionalPropsNode
          : resolvingContext.objectMapper.createObjectNode();
      JavaType resolvedValueType = valueType != null ? valueType
          : resolvingContext.objectMapper.serializationConfig().constructType(Object.class);
      T component = resolveProperty(visited, schemaSwaps, name, resolvedValueType, additionalProps, null);
      handleTypeAnnotations(component, fieldScope, Map.class, 1);
      return mapLikeProperty(component);
    } else if (schemaType.isEmpty() && !schemaNode.has("properties") && !schemaNode.has("additionalProperties")) {
      // "any" schema -- no type, no properties, no additionalProperties
      if (type != null) {
        if (type.getRawClass() == IntOrString.class || type.getRawClass() == Quantity.class) {
          return intOrString();
        }
        if (type.getRawClass() == RawExtension.class) {
          return raw();
        }
        // Map-like type with empty schema (e.g., Map<String, Object>):
        // victools may not generate additionalProperties for maps with Object values
        if (type.isMapLikeType()) {
          final JavaType keyType = type.getKeyType();
          final JavaType valueType = type.getContentType();
          if (keyType != null && keyType.getRawClass() != String.class) {
            logger.warn("Property '{}' with '{}' key type is mapped to 'string' because of CRD schemas limitations", name,
                keyType);
          }
          ObjectNode emptySchema = resolvingContext.objectMapper.createObjectNode();
          JavaType resolvedValueType = valueType != null ? valueType
              : resolvingContext.objectMapper.serializationConfig().constructType(Object.class);
          T component = resolveProperty(visited, schemaSwaps, name, resolvedValueType, emptySchema, null);
          handleTypeAnnotations(component, fieldScope, Map.class, 1);
          return mapLikeProperty(component);
        }
        if (JsonNode.class.isAssignableFrom(type.getRawClass())) {
          T schema = singleProperty(null);
          schema.setXKubernetesPreserveUnknownFields(true);
          return schema;
        }
        if (type.getRawClass() == ObjectNode.class) {
          T schema = singleProperty("object");
          schema.setXKubernetesPreserveUnknownFields(true);
          return schema;
        }
        if (type.getRawClass() == Object.class) {
          T schema = singleProperty("object");
          if (fieldScope != null) {
            schema.setXKubernetesPreserveUnknownFields(true);
          }
          return schema;
        }
        return singleProperty(null);
      }
      return singleProperty(null);
    }

    // Object type -- recurse
    if (type == null) {
      // No type info available, treat as generic object
      T schema = singleProperty("object");
      schema.setXKubernetesPreserveUnknownFields(true);
      return schema;
    }

    Class<?> def = type.getRawClass();

    // KubernetesResource is too broad, but we can check for several common subclasses
    if (def == GenericKubernetesResource.class
        || (def.isInterface() && HasMetadata.class.isAssignableFrom(def))) {
      return raw();
    }

    // Unknown interfaces (not Kubernetes-related) produce "any type" schemas
    if (def.isInterface()) {
      T schema = singleProperty(null);
      schema.setXKubernetesPreserveUnknownFields(true);
      return schema;
    }

    // Free-form JSON object produced by victools custom definition
    if (def == ObjectNode.class) {
      T schema = singleProperty("object");
      schema.setXKubernetesPreserveUnknownFields(true);
      return schema;
    }

    if (visited.put(def.getName(), name) != null) {
      throw new IllegalArgumentException(
          "Found a cyclic reference involving the field of type " + def.getName() + " starting a field "
              + visited.entrySet().stream().map(e -> e.getValue() + " >>\n" + e.getKey()).collect(Collectors.joining("."))
              + "." + name);
    }

    T res = resolveObject(visited, schemaSwaps, schemaNode, def);
    visited.remove(def.getName());
    return res;
  }

  private void handleTypeAnnotations(final T schema, FieldScope fieldScope, Class<?> containerType, int typeIndex) {
    if (fieldScope == null) {
      return;
    }
    Class<?> erasedType = fieldScope.getType().getErasedType();
    // Check both the exact container type and whether it's assignable (for subclass collections)
    if (!containerType.equals(erasedType) && !containerType.isAssignableFrom(erasedType)) {
      return;
    }

    Field rawField = fieldScope.getRawMember();
    AnnotatedType fieldType = rawField.getAnnotatedType();

    Stream.of(fieldType)
        .filter(Objects::nonNull)
        .filter(AnnotatedParameterizedType.class::isInstance)
        .map(AnnotatedParameterizedType.class::cast)
        .map(AnnotatedParameterizedType::getAnnotatedActualTypeArguments)
        .filter(a -> typeIndex < a.length) // Guard against subtype wrappers with fewer type params
        .map(a -> a[typeIndex])
        .forEach(at -> {
          if ("string".equals(schema.getType())) {
            ofNullable(at.getAnnotation(Pattern.class))
                .ifPresent(a -> schema.setPattern(a.value()));

            ofNullable(at.getAnnotation(Size.class))
                .map(Size::min)
                .filter(v -> v > 0)
                .ifPresent(schema::setMinLength);

            ofNullable(at.getAnnotation(Size.class))
                .map(Size::max)
                .filter(v -> v < Long.MAX_VALUE)
                .ifPresent(schema::setMaxLength);

          } else if ("number".equals(schema.getType()) || "integer".equals(schema.getType())) {
            ofNullable(at.getAnnotation(Min.class)).ifPresent(a -> {
              schema.setMinimum(a.value());
              if (!a.inclusive()) {
                schema.setExclusiveMinimum(true);
              }
            });
            ofNullable(at.getAnnotation(Max.class)).ifPresent(a -> {
              schema.setMaximum(a.value());
              if (!a.inclusive()) {
                schema.setExclusiveMaximum(true);
              }
            });
          }
        });
  }

  /**
   * Builds sorted enum values from a Java enum type using Jackson serialization.
   * This correctly handles @JsonProperty renaming and @JsonIgnore filtering.
   */
  /**
   * Builds sorted enum values from a Java enum type.
   * Uses getEnumConstants() instead of field.get() to avoid access issues with
   * package-private enum classes. Handles @JsonProperty renaming and @JsonIgnore filtering.
   */
  private JsonNode[] buildEnumValuesFromJavaType(JavaType type) {
    Class<?> enumClass = type.getRawClass();
    Object[] constants = enumClass.getEnumConstants();
    if (constants == null) {
      return new JsonNode[0];
    }

    Set<String> values = new TreeSet<>();
    for (Object constant : constants) {
      Enum<?> enumConstant = (Enum<?>) constant;
      String constantName = enumConstant.name();
      try {
        Field field = enumClass.getField(constantName);
        if (field.getAnnotation(JsonIgnore.class) != null) {
          continue;
        }
        // Check for @JsonProperty to get the serialized name
        JsonProperty jsonProp = field.getAnnotation(JsonProperty.class);
        if (jsonProp != null && !jsonProp.value().isEmpty()) {
          values.add(jsonProp.value());
        } else {
          values.add(constantName);
        }
      } catch (NoSuchFieldException e) {
        values.add(constantName);
      }
    }

    return values.stream()
        .map(JsonNodeFactory.instance::stringNode)
        .toArray(JsonNode[]::new);
  }

  V from(ValidationRule validationRule) {
    V result = newKubernetesValidationRule();
    result.setRule(validationRule.value());
    result.setReason(mapNotEmpty(validationRule.reason()));
    result.setMessage(mapNotEmpty(validationRule.message()));
    result.setMessageExpression(mapNotEmpty(validationRule.messageExpression()));
    result.setFieldPath(mapNotEmpty(validationRule.fieldPath()));
    result.setOptionalOldSelf(validationRule.optionalOldSelf() ? true : null);
    return result;
  }

  private static String mapNotEmpty(String s) {
    return Utils.isNullOrEmpty(s) ? null : s;
  }

  protected abstract V newKubernetesValidationRule();

  /**
   * Adds the specified property to the specified builder
   *
   * @param name the property to add to the currently being built schema
   * @param objectSchema the schema being built
   * @param schema the built schema for the property being added
   */
  protected abstract void addProperty(String name, T objectSchema, T schema);

  /**
   * Builds the schema for specifically for intOrString properties
   *
   * @return the property schema
   */
  protected abstract T intOrString();

  /**
   * Builds the schema for array-like properties
   *
   * @param schema the schema for the extracted element type for this array-like property
   * @return the schema for the array-like property
   */
  protected abstract T arrayLikeProperty(T schema);

  /**
   * Builds the schema for map-like properties
   *
   * @param schema the schema for the extracted element type for the values of this map-like property
   * @return the schema for the map-like property
   */
  protected abstract T mapLikeProperty(T schema);

  /**
   * Builds the schema for standard, simple (e.g. string) property types
   *
   * @param typeName the mapped name of the property type
   * @return the schema for the property
   */
  protected abstract T singleProperty(String typeName);

  protected abstract T enumProperty(JsonNode... enumValues);

  protected abstract void addToValidationRules(T schema, List<V> validationRules);

  protected abstract T raw();

  public Set<AdditionalPrinterColumn> getAdditionalPrinterColumns() {
    return additionalPrinterColumns;
  }

  public Set<AdditionalSelectableField> getAdditionalSelectableFields() {
    return additionalSelectableFields;
  }

}
