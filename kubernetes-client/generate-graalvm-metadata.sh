#!/bin/bash
set -e

echo "========================================"
echo "GraalVM Metadata Generation"
echo "========================================"
echo ""

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
METADATA_DIR="$SCRIPT_DIR/src/main/resources/META-INF/native-image/io.fabric8/kubernetes-client"
AGENT_OUTPUT_DIR="$SCRIPT_DIR/target/native-image-agent"

echo "📦 Building kubernetes-client..."
mvn clean compile test-compile -DskipTests -q

echo ""
echo "🔍 Running tests with GraalVM tracing agent..."
echo "   This will capture all reflection, JNI, and resource usage"
echo ""

# Clean agent output directory
rm -rf "$AGENT_OUTPUT_DIR"
mkdir -p "$AGENT_OUTPUT_DIR"

# Run tests with GraalVM tracing agent
# The agent will record all reflection, JNI, resource access, and serialization
mvn surefire:test \
  -Dtest='*' \
  -DargLine="-agentlib:native-image-agent=config-output-dir=$AGENT_OUTPUT_DIR" \
  -Dorg.slf4j.simpleLogger.defaultLogLevel=warn

echo ""
echo "✓ Tests completed, metadata captured"
echo ""

# Display what was generated
echo "📋 Generated metadata files:"
ls -lh "$AGENT_OUTPUT_DIR"
echo ""

# Show file sizes and line counts
for file in "$AGENT_OUTPUT_DIR"/*.json; do
  if [ -f "$file" ]; then
    filename=$(basename "$file")
    lines=$(wc -l < "$file" | tr -d ' ')
    size=$(ls -lh "$file" | awk '{print $5}')
    echo "  $filename: $lines lines, $size"
  fi
done

echo ""
echo "📁 Target location: $METADATA_DIR"
echo ""

# Ask for confirmation before copying
read -p "Copy generated metadata to target location? [y/N] " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
  # Backup existing metadata
  if [ -d "$METADATA_DIR" ]; then
    BACKUP_DIR="$SCRIPT_DIR/target/metadata-backup-$(date +%Y%m%d-%H%M%S)"
    echo "📦 Backing up existing metadata to: $BACKUP_DIR"
    mkdir -p "$BACKUP_DIR"
    cp -r "$METADATA_DIR"/* "$BACKUP_DIR/" 2>/dev/null || true
  fi

  # Copy generated metadata
  echo "📋 Copying generated metadata..."
  mkdir -p "$METADATA_DIR"
  cp "$AGENT_OUTPUT_DIR"/*.json "$METADATA_DIR/"

  echo ""
  echo "✅ Metadata generation complete!"
  echo ""
  echo "Next steps:"
  echo "  1. Review the generated files in: $METADATA_DIR"
  echo "  2. Test native image compilation: mvn -Pnative package"
  echo "  3. Run CI check: ./verify-graalvm-metadata.sh"
  echo ""
else
  echo ""
  echo "ℹ️  Metadata NOT copied. Files remain in: $AGENT_OUTPUT_DIR"
  echo ""
fi