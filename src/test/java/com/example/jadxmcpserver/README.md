# JADX Analyzer Refactoring Tests

This directory contains test classes for validating the refactored JADX Analyzer components.

## Test Classes

### Core Tests
- **`CoreFunctionalityTest.java`** - Tests the `JadxAnalyzerCore` class independently
- **`ApiFunctionalityTest.java`** - Tests the `JadxApkAnalyzerAPI` wrapper class
- **`CliFunctionalityTest.java`** - Tests the `JadxApkAnalyzerCLI` interactive interface
- **`BackwardCompatibilityTest.java`** - Tests the deprecated `JadxApkAnalyzer` wrapper

### Test Suite
- **`TestSuite.java`** - Comprehensive test runner that executes all tests

## Running Tests

### Using the Test Script (Recommended)
```bash
# From project root directory
./test.sh your_app.apk [test_mode]
```

Available test modes:
- `core` - Test core functionality only
- `api` - Test API wrapper only
- `cli` - Test CLI interface (interactive)
- `compat` - Test backward compatibility
- `all` - Run all non-interactive tests (default)
- `interactive` - Run all tests including CLI
- `suite` - Use comprehensive test suite

### Using Maven
```bash
# Compile tests
mvn test-compile

# Run individual test
mvn exec:java -Dexec.mainClass="com.example.jadxmcpserver.CoreFunctionalityTest" -Dexec.args="your_app.apk"
```

### Using Java directly
```bash
# Set up classpath
CLASSPATH="target/test-classes:target/classes:$(mvn dependency:build-classpath -Dmdep.outputFile=/dev/stdout -q)"

# Run specific test
java -cp "$CLASSPATH" com.example.jadxmcpserver.CoreFunctionalityTest your_app.apk

# Run test suite
java -cp "$CLASSPATH" com.example.jadxmcpserver.TestSuite your_app.apk all
```

## What Each Test Validates

### CoreFunctionalityTest
- ✅ APK loading and initialization
- ✅ Basic APK information extraction
- ✅ Class listing and enumeration
- ✅ Exported component analysis
- ✅ Manifest parsing
- ✅ Main activity detection
- ✅ Method and field extraction
- ✅ Method source extraction
- ✅ Search functionality
- ✅ Proper resource cleanup

### ApiFunctionalityTest
- ✅ API wrapper delegation to core
- ✅ Exception handling and conversion
- ✅ All CRUD operations for classes/methods/fields
- ✅ Error handling for non-existent resources
- ✅ Serialization of results to Maps
- ✅ Proper resource cleanup

### CliFunctionalityTest
- ✅ Interactive menu system
- ✅ User input handling
- ✅ Menu navigation
- ✅ Integration with core functionality

### BackwardCompatibilityTest
- ✅ Deprecated wrapper still functions
- ✅ Deprecation warnings are shown
- ✅ Existing code continues to work

## Expected Output

Each test should:
1. Load the APK successfully
2. Display basic APK information
3. Test various functionality without errors
4. Show ✅ success indicators
5. Clean up resources properly

Failed tests will show ❌ error indicators and stack traces.

## Requirements

- Java 11+
- Maven 3.6+
- Valid APK file for testing
- JADX dependencies resolved