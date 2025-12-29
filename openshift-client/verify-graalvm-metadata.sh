#!/bin/bash
set -e

echo "========================================"
echo "GraalVM Metadata Verification"
echo "OpenShift Client"
echo "========================================"
echo ""

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
METADATA_DIR="$SCRIPT_DIR/src/main/resources/META-INF/native-image/io.fabric8/openshift-client"
AGENT_OUTPUT_DIR="$SCRIPT_DIR/target/native-image-agent-verify"
DIFF_DIR="$SCRIPT_DIR/target/metadata-diff"

# Check if metadata exists
if [ ! -d "$METADATA_DIR" ]; then
  echo "❌ ERROR: No metadata found at $METADATA_DIR"
  echo "   Run ./generate-graalvm-metadata.sh first"
  exit 1
fi

echo "📦 Building openshift-client..."
mvn clean compile test-compile -DskipTests -q

echo ""
echo "🔍 Running tests with GraalVM tracing agent..."
echo ""

# Clean agent output directory
rm -rf "$AGENT_OUTPUT_DIR"
mkdir -p "$AGENT_OUTPUT_DIR"

# Run tests with GraalVM tracing agent
mvn surefire:test \
  -Dtest='*' \
  -DargLine="-agentlib:native-image-agent=config-output-dir=$AGENT_OUTPUT_DIR" \
  -Dorg.slf4j.simpleLogger.defaultLogLevel=warn

echo ""
echo "✓ Tests completed"
echo ""

# Create diff directory
rm -rf "$DIFF_DIR"
mkdir -p "$DIFF_DIR"

# Compare each metadata file (excluding native-image.properties which is manual)
echo "📊 Comparing generated metadata with committed metadata..."
echo ""

EXIT_CODE=0
CHANGED_FILES=()

for file in "$AGENT_OUTPUT_DIR"/*.json; do
  if [ -f "$file" ]; then
    filename=$(basename "$file")
    committed_file="$METADATA_DIR/$filename"

    if [ ! -f "$committed_file" ]; then
      echo "⚠️  NEW FILE: $filename (not in committed metadata)"
      CHANGED_FILES+=("$filename")
      EXIT_CODE=1
      continue
    fi

    # Compare files (ignoring whitespace differences)
    if ! diff -w "$file" "$committed_file" > "$DIFF_DIR/$filename.diff" 2>&1; then
      echo "❌ CHANGED: $filename"
      CHANGED_FILES+=("$filename")
      EXIT_CODE=1

      # Show diff summary
      echo "   Diff saved to: $DIFF_DIR/$filename.diff"

      # Show first few lines of diff
      echo "   First 10 lines of diff:"
      head -10 "$DIFF_DIR/$filename.diff" | sed 's/^/     /'
      echo ""
    else
      echo "✅ OK: $filename"
      rm "$DIFF_DIR/$filename.diff"
    fi
  fi
done

# Check for removed files
for file in "$METADATA_DIR"/*.json; do
  if [ -f "$file" ]; then
    filename=$(basename "$file")
    generated_file="$AGENT_OUTPUT_DIR/$filename"

    if [ ! -f "$generated_file" ]; then
      echo "⚠️  REMOVED: $filename (in committed metadata but not generated)"
      CHANGED_FILES+=("$filename")
      EXIT_CODE=1
    fi
  fi
done

echo ""
echo "========================================"

if [ $EXIT_CODE -eq 0 ]; then
  echo "✅ SUCCESS: Metadata is up-to-date!"
  echo ""
  echo "The committed GraalVM metadata matches what the tracing agent generates."
  rm -rf "$DIFF_DIR"
else
  echo "❌ FAILURE: Metadata has changed!"
  echo ""
  echo "Changed files: ${CHANGED_FILES[*]}"
  echo ""
  echo "This means:"
  echo "  1. New reflection/JNI/resource usage was added to the code"
  echo "  2. Existing usage patterns have changed"
  echo "  3. The committed metadata is out of sync"
  echo ""
  echo "To fix:"
  echo "  1. Review the changes in: $DIFF_DIR"
  echo "  2. Run: ./generate-graalvm-metadata.sh"
  echo "  3. Review and commit the updated metadata files"
  echo ""
fi

echo "========================================"

exit $EXIT_CODE
