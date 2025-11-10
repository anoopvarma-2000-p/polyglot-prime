#!/usr/bin/env fish

# Fish shell script to compile and run the mTLS test program
# Usage: ./compile-and-run-mtls-test.fish <client-cert-file> <ca-bundle-file> [--allow-self-signed]

set SCRIPT_DIR (dirname (realpath (status --current-filename)))
set PROJECT_ROOT (realpath "$SCRIPT_DIR/../..")
set TEST_CLASS_DIR "$PROJECT_ROOT/nexus-ingestion-api/src/test/java"
set TEST_CLASS "$TEST_CLASS_DIR/org/techbd/ingest/controller/MtlsTestMain.java"

echo "=== mTLS Test Compilation and Execution Script (Fish) ==="
echo "Project Root: $PROJECT_ROOT"
echo "Test Class: $TEST_CLASS"
echo

# Check if Maven is available
if not command -v mvn > /dev/null 2>&1
    echo "Error: Maven (mvn) is not available in PATH"
    echo "Please install Maven or ensure it's in your PATH"
    exit 1
end

# Check if the test class exists
if not test -f "$TEST_CLASS"
    echo "Error: Test class not found at $TEST_CLASS"
    exit 1
end

# Compile the project to ensure all dependencies are available
echo "1. Compiling the project..."
cd "$PROJECT_ROOT"
mvn -q compile test-compile -f nexus-ingestion-api/pom.xml

if test $status -ne 0
    echo "Error: Failed to compile the project"
    exit 1
end

echo "   ✅ Project compiled successfully"
echo

# Get the classpath from Maven
echo "2. Building classpath..."
set CLASSPATH (mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout -f nexus-ingestion-api/pom.xml 2>/dev/null)
set CLASSPATH "$PROJECT_ROOT/nexus-ingestion-api/target/classes:$PROJECT_ROOT/nexus-ingestion-api/target/test-classes:$CLASSPATH"

echo "   ✅ Classpath built"
echo

# Run the test program
echo "3. Running mTLS test..."
echo "Arguments: $argv"
echo

cd "$PROJECT_ROOT"
java -cp "$CLASSPATH" org.techbd.ingest.controller.MtlsTestMain $argv