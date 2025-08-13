# JADX MCP Server

A **Pure-Java** Model Context Protocol (MCP) server that provides Android APK reverse engineering capabilities using JADX (Java Android Decompiler). This server enables AI assistants like Claude to analyze APK files, decompile code, extract components, and perform security assessments on Android applications.

## 🚀 Pure-Java Implementation

**No external dependencies or plugins required!** This implementation is entirely self-contained:
- ✅ **Zero additional plugins** - Everything runs in pure Java
- ✅ **No native libraries** - Cross-platform compatibility guaranteed  
- ✅ **Single JAR deployment** - One file contains everything you need
- ✅ **No system modifications** - Works with any Java 11+ installation
- ✅ **Portable** - Run anywhere Java runs (Windows, macOS, Linux)

## Overview

This project implements an MCP server that wraps the powerful JADX decompiler, making Android APK analysis accessible through standardized MCP tools. It's designed for security researchers, developers, and analysts who need to reverse engineer Android applications programmatically.

### Key Features

- **APK Loading & Analysis**: Load and analyze APK files for detailed inspection
- **Code Decompilation**: Get decompiled Java source code from DEX bytecode
- **Class & Method Inspection**: Browse classes, methods, and fields
- **Component Extraction**: Extract exported components from AndroidManifest.xml
- **Method Search**: Search for specific methods across all classes
- **Manifest Analysis**: Parse and analyze AndroidManifest.xml content
- **Main Activity Detection**: Identify the application's entry point
- **MCP Integration**: Seamless integration with AI assistants through MCP protocol

## Architecture

The project follows a layered architecture:

- **MCP Layer** (`JadxToolService`): Exposes JADX functionality as MCP tools
- **API Layer** (`JadxApkAnalyzerAPI`): Clean API interface for APK analysis
- **Core Layer** (`JadxAnalyzerCore`): Core JADX integration and analysis logic
- **CLI Layer** (`JadxApkAnalyzerCLI`): Interactive command-line interface
- **Model Layer**: Data structures for components and call graphs

## Prerequisites

- **Java 11** or higher
- **Maven 3.6+** for building
- **JADX dependencies** (automatically handled by Maven)

## Installation & Setup

### 1. Clone the Repository

```bash
git clone <repository-url>
cd jadx-mcp-server
```

### 2. Build the Project

Use the provided build script:

```bash
chmod +x build.sh
./build.sh
```

Or build manually with Maven:

```bash
mvn clean package
```

The build process creates a fat JAR with all dependencies: `target/jadx-mcp-server-1.0.0-jar-with-dependencies.jar`

### 3. Configure Claude Desktop

Add the MCP server to your Claude Desktop configuration (`claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "jadx-analyzer": {
      "command": "java",
      "args": [
        "-jar",
        "/path/to/jadx-mcp-server/target/jadx-mcp-server-1.0.0-jar-with-dependencies.jar"
      ],
      "env": {
        "JAVA_HOME": "${JAVA_HOME}",
        "PATH": "${PATH}:/usr/bin:/usr/local/bin"
      }
    }
  }
}
```

Replace `/path/to/jadx-mcp-server` with the actual path to your project directory.

## Available MCP Tools

Once running, the server provides these MCP tools:

### Core Analysis Tools

- **`load_apk`** - Load and analyze an APK file
- **`get_all_classes`** - Get list of all classes in the APK
- **`get_class_source`** - Get decompiled source code of a specific class
- **`get_methods_of_class`** - Get list of methods in a specific class
- **`get_fields_of_class`** - Get list of fields in a specific class

### Method Analysis Tools

- **`get_method_by_name`** - Get source code of a specific method
- **`search_method_by_name`** - Search for methods across all classes

### Component Analysis Tools

- **`get_exported_components`** - Get exported components from AndroidManifest.xml
- **`get_android_manifest`** - Get the AndroidManifest.xml content
- **`get_main_activity_class`** - Get the main launcher activity class

## Usage Examples

### Basic APK Analysis with Claude

1. **Load an APK**:
   ```
   Please analyze this APK file: /path/to/app.apk
   ```

2. **Examine Classes**:
   ```
   Show me all the classes in the loaded APK
   ```

3. **Get Source Code**:
   ```
   Get the source code for the MainActivity class
   ```

4. **Security Analysis**:
   ```
   Check for exported components and potential security issues
   ```

### Command Line Testing

Test the functionality with the provided test script:

```bash
chmod +x test.sh
./test.sh /path/to/your/app.apk
```

Available test modes:
- `core` - Test core functionality
- `api` - Test API functionality  
- `cli` - Test CLI interface (interactive)
- `compat` - Test backward compatibility
- `all` - Test all non-interactive components (default)
- `interactive` - Test all components including CLI
- `suite` - Run comprehensive test suite

## Project Structure

```
jadx-mcp-server/
├── src/main/java/com/example/jadxmcpserver/
│   ├── JadxMcpServerApplication.java    # Spring Boot application entry point
│   ├── JadxToolService.java             # MCP tool definitions
│   ├── JadxApkAnalyzerAPI.java         # Clean API interface
│   ├── JadxApkAnalyzer.java            # Legacy compatibility wrapper
│   ├── cli/
│   │   └── JadxApkAnalyzerCLI.java     # Interactive CLI interface
│   ├── core/
│   │   └── JadxAnalyzerCore.java       # Core JADX integration
│   └── model/
│       ├── CallGraphNode.java          # Call graph data structure
│       └── ExportedComponent.java      # Component data structure
├── src/main/resources/
│   └── application.properties          # Spring configuration
├── src/test/                          # Comprehensive test suite
├── build.sh                           # Build script
├── test.sh                            # Testing script
├── claude_desktop_config.json         # Claude Desktop config template
└── pom.xml                            # Maven configuration
```

## Configuration

### Spring Boot Configuration

The application uses Spring Boot with MCP server capabilities. Key configurations in `application.properties`:

- Runs as non-web application
- Disables console logging for STDIO transport
- Logs to `/tmp/jadx-mcp-server.log`

### JADX Configuration

JADX is configured with:
- Java 11 compatibility
- Full decompilation including resources
- Error handling for corrupted APKs

## Development

### Adding New Tools

1. Add method to `JadxToolService` with `@Tool` annotation
2. Implement functionality in `JadxApkAnalyzerAPI`
3. Add core logic to `JadxAnalyzerCore`
4. Update tests in the test suite

### Testing

Run the comprehensive test suite:

```bash
./test.sh /path/to/test.apk suite
```

Individual test components can be run separately for focused testing.

## Security Considerations

This tool is designed for **defensive security analysis only**:

- ✅ Vulnerability assessment
- ✅ Security research
- ✅ Code review and analysis
- ✅ Malware analysis (defensive)
- ❌ Creating malicious modifications
- ❌ Bypassing security controls
- ❌ Unauthorized application modification

## Troubleshooting

### Common Issues

1. **Java Version**: Ensure Java 11+ is installed and `JAVA_HOME` is set
2. **Memory Issues**: Large APKs may require additional JVM memory: `-Xmx4g`
3. **Path Issues**: Use absolute paths in Claude Desktop configuration
4. **Permission Issues**: Ensure the APK file is readable

### Debug Logging

Enable debug logging by modifying `application.properties`:

```properties
logging.level.root=DEBUG
logging.level.org.springframework.ai.mcp=TRACE
```

## License

This project uses JADX library which is licensed under Apache License 2.0. Please refer to JADX documentation for usage terms and conditions.

## Contributing

1. Fork the repository
2. Create a feature branch
3. Add tests for new functionality
4. Submit a pull request

## Support

For issues and questions:
1. Check the troubleshooting section
2. Review JADX documentation
3. Submit an issue with detailed information including Java version, APK details, and error messages