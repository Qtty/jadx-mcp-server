#!/bin/bash

# Run JADX Server as REST API

JAR_FILE="target/jadx-mcp-server-1.0.0.jar"

if [ ! -f "$JAR_FILE" ]; then
    echo "JAR file not found: $JAR_FILE"
    echo "Please run ./build.sh first"
    exit 1
fi

echo "Starting JADX Server in REST API mode..."
echo "Server will be available at: http://localhost:8080"
echo "API endpoints will be under: http://localhost:8080/api/jadx/"
echo ""
echo "Example usage:"
echo "curl -X POST http://localhost:8080/api/jadx/load-apk -H 'Content-Type: application/json' -d '{\"apkPath\":\"/path/to/app.apk\"}'"
echo ""
echo "Press Ctrl+C to stop the server"
echo ""

java -Dspring.profiles.active=api -jar "$JAR_FILE"