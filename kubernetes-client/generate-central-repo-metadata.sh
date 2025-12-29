#!/bin/bash
set -e

echo "========================================"
echo "Central Repository Metadata Generation"
echo "========================================"
echo ""
echo "This script creates metadata in the format required for"
echo "the GraalVM Reachability Metadata Repository:"
echo "https://github.com/oracle/graalvm-reachability-metadata"
echo ""

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIBRARY_METADATA_DIR="$SCRIPT_DIR/src/main/resources/META-INF/native-image/io.fabric8/kubernetes-client"
CENTRAL_REPO_DIR="$SCRIPT_DIR/../graalvm-reachability-metadata"
VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)

TARGET_DIR="$CENTRAL_REPO_DIR/io.fabric8/kubernetes-client/$VERSION"

# Check if library metadata exists
if [ ! -d "$LIBRARY_METADATA_DIR" ]; then
  echo "❌ ERROR: Library metadata not found at: $LIBRARY_METADATA_DIR"
  echo "   Run ./generate-graalvm-metadata.sh first"
  exit 1
fi

echo "📦 Version: $VERSION"
echo "📁 Target: $TARGET_DIR"
echo ""

# Create target directory
mkdir -p "$TARGET_DIR"

# Copy metadata files
echo "📋 Copying metadata files..."
for file in reflect-config.json jni-config.json resource-config.json serialization-config.json; do
  if [ -f "$LIBRARY_METADATA_DIR/$file" ]; then
    cp "$LIBRARY_METADATA_DIR/$file" "$TARGET_DIR/$file"
    echo "  ✓ $file"
  else
    echo "  ⚠️  $file (not found, skipping)"
  fi
done

# Create index.json
echo ""
echo "📝 Creating index.json..."
cat > "$TARGET_DIR/index.json" <<EOF
{
  "module": "io.fabric8:kubernetes-client",
  "tested-versions": [
    "$VERSION"
  ],
  "metadata-version": "1.0.0",
  "description": "Kubernetes Client for Java - Fabric8"
}
EOF
echo "  ✓ index.json"

# Create README for central repository
echo ""
echo "📝 Creating README.md for central repository..."
cat > "$CENTRAL_REPO_DIR/README.md" <<'EOF'
# GraalVM Reachability Metadata - Fabric8 Kubernetes Client

This directory contains GraalVM native-image metadata for the Fabric8 Kubernetes Client in the format required for the [GraalVM Reachability Metadata Repository](https://github.com/oracle/graalvm-reachability-metadata).

## Directory Structure

```
graalvm-reachability-metadata/
└── io.fabric8/
    └── kubernetes-client/
        └── <version>/
            ├── index.json
            ├── reflect-config.json
            ├── jni-config.json
            ├── resource-config.json
            └── serialization-config.json
```

## Regenerating Metadata

This metadata is automatically generated from the kubernetes-client test suite:

```bash
cd kubernetes-client
./generate-graalvm-metadata.sh
./generate-central-repo-metadata.sh
```

## Contributing to Central Repository

To contribute this metadata to the official GraalVM Reachability Metadata Repository:

1. Fork: https://github.com/oracle/graalvm-reachability-metadata
2. Copy the `io.fabric8` directory to the fork
3. Test the metadata following their contribution guidelines
4. Submit a pull request

## More Information

- [GraalVM Reachability Metadata Repository](https://github.com/oracle/graalvm-reachability-metadata)
- [Kubernetes Client Native Image Support](../kubernetes-client/src/main/resources/META-INF/native-image/io.fabric8/kubernetes-client/README.md)
- [Fabric8 Kubernetes Client](https://github.com/fabric8io/kubernetes-client)
EOF
echo "  ✓ README.md"

echo ""
echo "✅ Central repository metadata generated!"
echo ""
echo "📁 Location: $CENTRAL_REPO_DIR"
echo ""
echo "Next steps:"
echo "  1. Review the generated files in: $TARGET_DIR"
echo "  2. Test the metadata with a sample application"
echo "  3. Contribute to: https://github.com/oracle/graalvm-reachability-metadata"
echo ""
