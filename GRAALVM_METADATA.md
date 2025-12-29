# GraalVM Native Image Metadata - Complete Guide

This document describes the automated GraalVM metadata generation setup for both **kubernetes-client** and **openshift-client**.

## 📋 Overview

GraalVM metadata is automatically generated from the full test suites using the GraalVM tracing agent. This ensures comprehensive coverage of all reflection, JNI, resource, and serialization usage patterns.

## 🏗️ Architecture

```
kubernetes-client-project/
├── kubernetes-client/
│   ├── generate-graalvm-metadata.sh         # Generate metadata from tests
│   ├── verify-graalvm-metadata.sh           # Verify metadata is up-to-date
│   ├── generate-central-repo-metadata.sh    # Export to central repo format
│   └── src/main/resources/META-INF/native-image/io.fabric8/kubernetes-client/
│       ├── reflect-config.json              # Auto-generated
│       ├── jni-config.json                  # Auto-generated
│       ├── resource-config.json             # Auto-generated
│       ├── serialization-config.json        # Auto-generated
│       ├── native-image.properties          # Manually configured
│       └── README.md
│
├── openshift-client/
│   ├── generate-graalvm-metadata.sh         # Generate metadata from tests
│   ├── verify-graalvm-metadata.sh           # Verify metadata is up-to-date
│   ├── generate-central-repo-metadata.sh    # Export to central repo format
│   └── src/main/resources/META-INF/native-image/io.fabric8/openshift-client/
│       ├── reflect-config.json              # Auto-generated
│       ├── jni-config.json                  # Auto-generated
│       ├── resource-config.json             # Auto-generated
│       ├── serialization-config.json        # Auto-generated
│       ├── native-image.properties          # Manually configured
│       └── README.md
│
├── graalvm-reachability-metadata/           # Central repository format
│   ├── io.fabric8/
│   │   ├── kubernetes-client/7.5-SNAPSHOT/
│   │   └── openshift-client/7.5-SNAPSHOT/
│   └── README.md
│
└── .github/workflows/
    └── graalvm-metadata-check.yml           # CI check for both clients
```

## 🔄 Automated Workflows

### For Developers

#### Kubernetes Client

```bash
# Generate metadata from tests
cd kubernetes-client
./generate-graalvm-metadata.sh

# Verify metadata is up-to-date
./verify-graalvm-metadata.sh

# Export to central repository format
./generate-central-repo-metadata.sh
```

#### OpenShift Client

```bash
# Generate metadata from tests
cd openshift-client
./generate-graalvm-metadata.sh

# Verify metadata is up-to-date
./verify-graalvm-metadata.sh

# Export to central repository format
./generate-central-repo-metadata.sh
```

### For CI

The GitHub Actions workflow automatically:
- ✅ Runs on every PR that modifies source code
- ✅ Checks both `kubernetes-client` and `openshift-client` in parallel
- ✅ Verifies metadata matches what the tracing agent generates
- ✅ Posts helpful PR comments if metadata needs updating
- ✅ Uploads diff artifacts for review

## 🎯 How It Works

### 1. Metadata Generation

```bash
./generate-graalvm-metadata.sh
```

**What happens:**
1. Builds the library and test classes
2. Runs the GraalVM tracing agent: `-agentlib:native-image-agent=config-output-dir=...`
3. Executes **ALL** tests (44 tests for kubernetes-client, 8 tests for openshift-client)
4. Agent records every reflection/JNI/resource access during test execution
5. Generates JSON configuration files
6. Prompts to copy files to the library metadata directory

**Generated files:**
- `reflect-config.json` - All classes/methods/fields accessed via reflection
- `jni-config.json` - All JNI calls
- `resource-config.json` - All resources loaded at runtime
- `serialization-config.json` - All serialization patterns

### 2. Metadata Verification

```bash
./verify-graalvm-metadata.sh
```

**What happens:**
1. Regenerates metadata from tests
2. Compares generated metadata with committed files
3. Reports any differences
4. Exits with error code if metadata is out of sync

**Exit codes:**
- `0` - Metadata is up-to-date ✅
- `1` - Metadata has changed ❌

### 3. Central Repository Export

```bash
./generate-central-repo-metadata.sh
```

**What happens:**
1. Copies library-bundled metadata to central repository format
2. Creates `index.json` with version info
3. Organizes files in the structure required by oracle/graalvm-reachability-metadata

**Output:**
```
graalvm-reachability-metadata/
└── io.fabric8/
    ├── kubernetes-client/7.5-SNAPSHOT/
    │   ├── index.json
    │   ├── reflect-config.json
    │   ├── jni-config.json
    │   ├── resource-config.json
    │   └── serialization-config.json
    └── openshift-client/7.5-SNAPSHOT/
        ├── index.json
        ├── reflect-config.json
        ├── jni-config.json
        ├── resource-config.json
        └── serialization-config.json
```

## 🚦 CI Workflow Details

**File:** `.github/workflows/graalvm-metadata-check.yml`

**Triggers:**
- Pull requests modifying:
  - `kubernetes-client/src/main/**`
  - `kubernetes-client/pom.xml`
  - `kubernetes-client/src/main/resources/META-INF/native-image/**`
  - `openshift-client/src/main/**`
  - `openshift-client/pom.xml`
  - `openshift-client/src/main/resources/META-INF/native-image/**`
- Pushes to `main` branch
- Manual dispatch

**Matrix Strategy:**
```yaml
matrix:
  module:
    - kubernetes-client
    - openshift-client
```

Both clients are checked in parallel for efficiency.

**On Failure:**
- Uploads diff artifacts showing what changed
- Posts PR comment with fix instructions
- Includes module name in all messages

## 📊 Metadata Coverage

### Kubernetes Client
- **44 test files** covering all core Kubernetes resources
- **~933+ lines** in reflect-config.json
- Includes: Pod, Service, ConfigMap, Namespace, Deployment, StatefulSet, etc.

### OpenShift Client
- **8 test files** covering OpenShift-specific resources
- Comprehensive coverage of OpenShift extensions
- Includes: Route, Build, BuildConfig, DeploymentConfig, etc.

## 🔧 Manual Configuration

Only `native-image.properties` is manually configured. This file contains build-time arguments:

**kubernetes-client:**
```properties
Args = --initialize-at-run-time=io.fabric8.kubernetes.client.internal.SSLUtils \
       --initialize-at-build-time=org.slf4j \
       --enable-http \
       --enable-https \
       --enable-all-security-services \
       -H:+AddAllCharsets
```

**openshift-client:**
```properties
Args = --initialize-at-run-time=io.fabric8.openshift.client.internal
```

These files are **not** overwritten by the generation scripts.

## 🐛 Troubleshooting

### CI Check Fails

**Symptom:** GitHub Actions workflow fails with "Metadata has changed"

**Cause:** Code changes introduced new reflection/JNI/resource usage

**Fix:**
```bash
cd kubernetes-client  # or openshift-client
./generate-graalvm-metadata.sh
# Review changes
git add src/main/resources/META-INF/native-image/
git commit -m "Update GraalVM metadata"
git push
```

### Missing Reflection Error in Native Image

**Symptom:** `MissingReflectionRegistrationError` at runtime

**Cause:** New code path not covered by tests

**Fix:**
1. Add tests that exercise the new code path
2. Regenerate metadata: `./generate-graalvm-metadata.sh`
3. Verify: `./verify-graalvm-metadata.sh`

### Build Failures

**Symptom:** Native image compilation fails

**Common causes:**
- Not using GraalVM (using standard JDK instead)
- Outdated metadata files
- Missing required build arguments in `native-image.properties`

**Fix:**
1. Ensure GraalVM is installed: `java -version` should show GraalVM
2. Regenerate metadata: `./generate-graalvm-metadata.sh`
3. Check `native-image.properties` for required args

## 🤝 Contributing

When contributing code that uses reflection, JNI, or resources:

### Required Steps

1. **Write tests** that exercise your new code paths
2. **Generate metadata** from your tests:
   ```bash
   cd kubernetes-client  # or openshift-client
   ./generate-graalvm-metadata.sh
   ```
3. **Verify metadata**:
   ```bash
   ./verify-graalvm-metadata.sh
   ```
4. **Include in PR**: Commit the updated metadata files
5. **CI will verify**: The workflow ensures nothing is missed

### Guidelines

- ✅ **DO** regenerate metadata after any reflection-related changes
- ✅ **DO** commit all generated JSON files
- ✅ **DO** add tests for new code paths
- ❌ **DON'T** manually edit `reflect-config.json`, `jni-config.json`, etc.
- ❌ **DON'T** modify `native-image.properties` without discussion
- ✅ **DO** check CI results and fix any metadata drift

## 📚 Resources

- [GraalVM Native Image](https://www.graalvm.org/native-image/)
- [GraalVM Reachability Metadata Repository](https://github.com/oracle/graalvm-reachability-metadata)
- [Kubernetes Client Documentation](https://github.com/fabric8io/kubernetes-client)
- [GraalVM Tracing Agent](https://www.graalvm.org/latest/reference-manual/native-image/metadata/AutomaticMetadataCollection/)

## 📝 Version History

- **7.5-SNAPSHOT**: Initial automated metadata generation setup
  - Both kubernetes-client and openshift-client
  - CI verification workflow
  - Central repository export scripts
