package com.example.jadxmcpserver;

import org.springframework.stereotype.Service;
import org.springframework.ai.tool.annotation.Tool;

import java.util.*;
import java.util.logging.Logger;

/**
 * JADX Tool Service - Provides MCP tools for APK analysis
 */
@Service
public class JadxToolService {
    
    private static final Logger logger = Logger.getLogger(JadxToolService.class.getName());
    private final JadxApkAnalyzerAPI analyzer = new JadxApkAnalyzerAPI();
    
    @Tool(name = "load_apk", description = "Load and analyze an APK file")
    public Map<String, Object> loadApk(String apkPath) {
        try {
            logger.info("Loading APK: " + apkPath);
            return analyzer.loadApk(apkPath);
        } catch (Exception e) {
            logger.severe("Error loading APK: " + e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }
    
    @Tool(name = "get_all_classes", description = "Get list of all classes in the loaded APK")
    public List<String> getAllClasses() {
        try {
            logger.info("Getting all classes");
            return analyzer.getAllClasses();
        } catch (Exception e) {
            logger.severe("Error getting classes: " + e.getMessage());
            return List.of("Error: " + e.getMessage());
        }
    }
    
    @Tool(name = "get_class_source", description = "Get the decompiled source code of a specific class")
    public String getClassSource(String className) {
        try {
            logger.info("Getting source for class: " + className);
            return analyzer.getClassSource(className);
        } catch (Exception e) {
            logger.severe("Error getting class source: " + e.getMessage());
            return "Error: " + e.getMessage();
        }
    }
    
    @Tool(name = "get_methods_of_class", description = "Get list of methods in a specific class")
    public List<String> getMethodsOfClass(String className) {
        try {
            logger.info("Getting methods for class: " + className);
            return analyzer.getMethodsOfClass(className);
        } catch (Exception e) {
            logger.severe("Error getting methods: " + e.getMessage());
            return List.of("Error: " + e.getMessage());
        }
    }
    
    @Tool(name = "get_fields_of_class", description = "Get list of fields in a specific class")
    public List<String> getFieldsOfClass(String className) {
        try {
            logger.info("Getting fields for class: " + className);
            return analyzer.getFieldsOfClass(className);
        } catch (Exception e) {
            logger.severe("Error getting fields: " + e.getMessage());
            return List.of("Error: " + e.getMessage());
        }
    }
    
    @Tool(name = "get_method_by_name", description = "Get the source code of a specific method")
    public String getMethodByName(String className, String methodName) {
        try {
            logger.info("Getting method source: " + className + "." + methodName);
            return analyzer.getMethodSource(className, methodName);
        } catch (Exception e) {
            logger.severe("Error getting method source: " + e.getMessage());
            return "Error: " + e.getMessage();
        }
    }
    
    @Tool(name = "search_method_by_name", description = "Search for methods across all classes")
    public Map<String, List<String>> searchMethodByName(String methodName) {
        try {
            logger.info("Searching for method: " + methodName);
            return analyzer.searchMethod(methodName);
        } catch (Exception e) {
            logger.severe("Error searching methods: " + e.getMessage());
            return Map.of("error", List.of(e.getMessage()));
        }
    }
    
    @Tool(name = "get_exported_components", description = "Get all exported components from AndroidManifest.xml")
    public List<Map<String, Object>> getExportedComponents() {
        try {
            logger.info("Getting exported components");
            return analyzer.getExportedComponents();
        } catch (Exception e) {
            logger.severe("Error getting exported components: " + e.getMessage());
            return List.of(Map.of("error", e.getMessage()));
        }
    }
    
    @Tool(name = "get_android_manifest", description = "Get the AndroidManifest.xml content")
    public String getAndroidManifest() {
        try {
            logger.info("Getting AndroidManifest.xml");
            return analyzer.getAndroidManifest();
        } catch (Exception e) {
            logger.severe("Error getting manifest: " + e.getMessage());
            return "Error: " + e.getMessage();
        }
    }
    
    @Tool(name = "get_main_activity_class", description = "Get the main launcher activity class name")
    public String getMainActivityClass() {
        try {
            logger.info("Getting main activity");
            return analyzer.getMainActivity();
        } catch (Exception e) {
            logger.severe("Error getting main activity: " + e.getMessage());
            return "Error: " + e.getMessage();
        }
    }
}
