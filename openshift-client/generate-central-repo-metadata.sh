#!/bin/bash
set -e

echo "========================================"
echo "Central Repository Metadata Generation"
echo "OpenShift Client"
echo "========================================"
echo ""
echo "This script creates metadata in the format required for"
echo "the GraalVM Reachability Metadata Repository:"
echo "https://github.com/oracle/graalvm-reachability-metadata"
echo ""

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIBRARY_METADATA_DIR="$SCRIPT_DIR/src/main/resources/META-INF/native-image/io.fabric8/openshift-client"
CENTRAL_REPO_DIR="$SCRIPT_DIR/../graalvm-reachability-metadata"
VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)

TARGET_DIR="$CENTRAL_REPO_DIR/io.fabric8/openshift-client/$VERSION"

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
  "module": "io.fabric8:openshift-client",
  "tested-versions": [
    "$VERSION"
  ],
  "metadata-version": "1.0.0",
  "description": "OpenShift Client for Java - Fabric8"
}
EOF
echo "  ✓ index.json"

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