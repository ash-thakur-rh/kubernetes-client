# GraalVM Native Image Configuration - OpenShift Client

This directory contains GraalVM native-image configuration for the OpenShift Client library.

## 🔄 Automated Generation

**IMPORTANT**: These configuration files are **automatically generated** using the GraalVM tracing agent running against the full test suite. **Do NOT edit them manually.**

### Regenerating Metadata

If you modify library code and need to regenerate metadata:

```bash
cd openshift-client
./generate-graalvm-metadata.sh
```

The script will:
1. Build the library and test classes
2. Run all tests with the GraalVM tracing agent
3. Capture all reflection, JNI, resource, and serialization usage
4. Generate updated metadata files

### Verifying Metadata (CI Check)

To verify that committed metadata is up-to-date:

```bash
cd openshift-client
./verify-graalvm-metadata.sh
```

This check runs automatically in CI for every pull request.

## 📋 Configuration Files

- **reflect-config.json** - Reflection metadata (auto-generated from test suite)
- **jni-config.json** - JNI calls metadata (auto-generated)
- **resource-config.json** - Resource bundles and files to include (auto-generated)
- **serialization-config.json** - Serialization metadata (auto-generated)
- **native-image.properties** - Build-time compiler arguments (manually configured)

## 🚀 Using in Your Project

These configurations are automatically picked up by GraalVM when you include the `openshift-client` dependency.

### Maven Example

```xml
<dependency>
    <groupId>io.fabric8</groupId>
    <artifactId>openshift-client</artifactId>
    <version>${openshift-client.version}</version>
</dependency>
```

### Gradle Example

```gradle
implementation 'io.fabric8:openshift-client:${openshift-client.version}'
```

## 🔧 Extending for Custom Resources

The provided configurations cover all standard OpenShift resources. For Custom Resource Definitions (CRDs), add your own configuration:

**Option 1: Use GraalVM Tracing Agent (Recommended)**

```bash
java -agentlib:native-image-agent=config-output-dir=src/main/resources/META-INF/native-image \
     -jar your-application.jar
```

Run your application through typical scenarios to capture all reflection usage.

**Option 2: Manual Configuration**

Create `src/main/resources/META-INF/native-image/<groupId>/<artifactId>/reflect-config.json`:

```json
[
  {
    "name": "com.example.MyCustomResource",
    "allDeclaredFields": true,
    "queryAllDeclaredMethods": true,
    "queryAllDeclaredConstructors": true
  }
]
```

## 🧪 Testing Your Native Image

1. Build your native image:
   ```bash
   mvn -Pnative native:compile
   # or with Gradle
   ./gradlew nativeCompile
   ```

2. Run the native executable and test OpenShift operations

3. If you encounter reflection errors, use the Tracing Agent to capture missing configurations

## 🐛 Troubleshooting

**MissingReflectionRegistrationError**
- New code uses reflection not captured by tests
- Solution: Run `./generate-graalvm-metadata.sh` to regenerate

**Build Failures**
- Ensure you're using GraalVM (not standard JDK)
- Verify GraalVM version compatibility (tested with GraalVM 21+)
- Check that all required metadata files are present

## 📖 Resources

- [GraalVM Native Image Documentation](https://www.graalvm.org/latest/reference-manual/native-image/)
- [GraalVM Reachability Metadata Repository](https://github.com/oracle/graalvm-reachability-metadata)
- [Fabric8 OpenShift Client Documentation](https://github.com/fabric8io/kubernetes-client)

## 🤝 Contributing

When contributing code that uses reflection, JNI, or resources:

1. Run `./generate-graalvm-metadata.sh` after your changes
2. Include the updated metadata in your pull request
3. The CI will verify that metadata is up-to-date
