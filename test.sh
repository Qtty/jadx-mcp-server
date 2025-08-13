#!/bin/bash

# Test script for the refactored JADX Analyzer

echo "🧪 JADX Analyzer Refactoring Test Script"
echo "========================================"

# Check if APK file is provided
if [ $# -eq 0 ]; then
    echo "Usage: $0 <apk_file> [test_mode]"
    echo ""
    echo "Available test modes:"
    echo "  core        - Test core functionality"
    echo "  api         - Test API functionality"  
    echo "  cli         - Test CLI interface (interactive)"
    echo "  compat      - Test backward compatibility"
    echo "  all         - Test all non-interactive components (default)"
    echo "  interactive - Test all components including CLI"
    echo "  suite       - Run comprehensive test suite"
    exit 1
fi

APK_FILE="$1"
TEST_MODE="${2:-all}"

# Check if APK file exists
if [ ! -f "$APK_FILE" ]; then
    echo "❌ Error: APK file '$APK_FILE' not found!"
    exit 1
fi

echo "Testing with APK: $APK_FILE"
echo "Test mode: $TEST_MODE"
echo ""

# Build the project including tests
echo "🔧 Building Project (including tests)..."
mvn compile test-compile
if [ $? -ne 0 ]; then
    echo "❌ Build failed!"
    exit 1
fi

# Set up classpath
MAIN_CLASSPATH="target/classes"
TEST_CLASSPATH="target/test-classes"
DEPENDENCY_CLASSPATH=$(mvn dependency:build-classpath -Dmdep.outputFile=/dev/stdout -q 2>/dev/null)
FULL_CLASSPATH="$TEST_CLASSPATH:$MAIN_CLASSPATH:$DEPENDENCY_CLASSPATH"

echo "✅ Build successful!"
echo ""

# Run tests based on mode
case "$TEST_MODE" in
    "core")
        echo "🔧 Testing Core Functionality..."
        java -cp "$FULL_CLASSPATH" com.example.jadxmcpserver.CoreFunctionalityTest "$APK_FILE"
        ;;
    "api")
        echo "🔌 Testing API Functionality..."
        java -cp "$FULL_CLASSPATH" com.example.jadxmcpserver.ApiFunctionalityTest "$APK_FILE"
        ;;
    "cli")
        echo "💻 Testing CLI Interface..."
        java -cp "$FULL_CLASSPATH" com.example.jadxmcpserver.CliFunctionalityTest "$APK_FILE"
        ;;
    "compat")
        echo "🔄 Testing Backward Compatibility..."
        java -cp "$FULL_CLASSPATH" com.example.jadxmcpserver.BackwardCompatibilityTest "$APK_FILE"
        ;;
    "suite")
        echo "🧪 Running Comprehensive Test Suite..."
        java -cp "$FULL_CLASSPATH" com.example.jadxmcpserver.TestSuite "$APK_FILE" all
        ;;
    "interactive")
        echo "🧪 Running Interactive Test Suite..."
        java -cp "$FULL_CLASSPATH" com.example.jadxmcpserver.TestSuite "$APK_FILE" interactive
        ;;
    "all"|*)
        echo "🧪 Running All Non-Interactive Tests..."
        java -cp "$FULL_CLASSPATH" com.example.jadxmcpserver.TestSuite "$APK_FILE" all
        ;;
esac

EXIT_CODE=$?

echo ""
if [ $EXIT_CODE -eq 0 ]; then
    echo "✅ Testing completed successfully!"
else
    echo "❌ Testing failed!"
fi

echo "========================================"
exit $EXIT_CODE