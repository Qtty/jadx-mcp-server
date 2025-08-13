#!/bin/bash

# Build script for JADX MCP Server

echo "Building JADX MCP Server with official MCP SDK..."

# Clean and build
mvn clean package

if [ $? -eq 0 ]; then
    echo "Build complete!"
    echo ""
    echo "The server JAR is located at: target/jadx-mcp-server-1.0.0-jar-with-dependencies.jar"
    echo ""
    echo "To use with Claude Desktop, update your configuration with:"
    echo ""
    echo '{
  "mcpServers": {
    "jadx-analyzer": {
      "command": "java",
      "args": [
        "-jar",
        "'$(pwd)'/target/jadx-mcp-server-1.0.0-jar-with-dependencies.jar"
      ]
    }
  }
}'
else
    echo "Build failed!"
    exit 1
fi
