#!/bin/bash

# Build script for JADX MCP Server

echo "Building JADX MCP Server with official MCP SDK..."

# Clean and build
mvn clean package

if [ $? -eq 0 ]; then
    echo "Build complete!"
    echo ""
    echo "The server JAR is located at: target/jadx-mcp-server-1.0.0.jar"
    echo ""
    echo "To use with Claude Desktop, update your configuration with:"
    echo ""
    echo '"jadx-analyzer": {
      "command": "java",
      "args": [
        "-Dspring.ai.mcp.server.stdio=true",
        "-jar",
        "'$(pwd)'/target/jadx-mcp-server-1.0.0.jar"
      ]
    }'
else
    echo "Build failed!"
    exit 1
fi
