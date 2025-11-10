#!/bin/bash

# Script to compile and run the mTLS test program
# Usage: ./compile-and-run-mtls-test.sh <client-cert-file> <ca-bundle-file> [--allow-self-signed]

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
TEST_CLASS_DIR="$PROJECT_ROOT/nexus-ingestion-api/src/test/java"
TEST_CLASS="$TEST_CLASS_DIR/org/techbd/ingest/controller/MtlsTestMain.java"

echo "=== mTLS Test Compilation and Execution Script ==="
echo "Project Root: $PROJECT_ROOT"
echo "Test Class: $TEST_CLASS"
echo

# Check if Maven is available
if ! command -v mvn &> /dev/null; then
    echo "Error: Maven (mvn) is not available in PATH"
    echo "Please install Maven or ensure it's in your PATH"
    exit 1
fi

# Check if the test class exists
if [ ! -f "$TEST_CLASS" ]; then
    echo "Error: Test class not found at $TEST_CLASS"
    exit 1
fi

# Compile the project to ensure all dependencies are available
echo "1. Compiling the project..."
cd "$PROJECT_ROOT"
mvn -q compile test-compile -f nexus-ingestion-api/pom.xml

if [ $? -ne 0 ]; then
    echo "Error: Failed to compile the project"
    exit 1
fi

echo "   ✅ Project compiled successfully"
echo

# Get the classpath from Maven
echo "2. Building classpath..."
CLASSPATH=$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout -f nexus-ingestion-api/pom.xml 2>/dev/null)
CLASSPATH="$PROJECT_ROOT/nexus-ingestion-api/target/classes:$PROJECT_ROOT/nexus-ingestion-api/target/test-classes:$CLASSPATH"

echo "   ✅ Classpath built"
echo

# Run the test program
echo "3. Running mTLS test..."
echo "Arguments: $@"
echo

cd "$PROJECT_ROOT"
java -cp "$CLASSPATH" org.techbd.ingest.controller.MtlsTestMain "$@"