package com.example.jadxmcpserver;

import com.example.jadxmcpserver.core.JadxAnalyzerCore;

import java.util.*;

/**
 * JADX APK Analyzer API - Clean API wrapper for MCP Server
 * This class provides a simple API interface that delegates to JadxAnalyzerCore
 */
public class JadxApkAnalyzerAPI {
    
    private JadxAnalyzerCore core;
    
    /**
     * Load and analyze an APK file
     */
    public Map<String, Object> loadApk(String apkPath) throws Exception {
        try {
            // Close previous instance if exists
            close();
            
            core = new JadxAnalyzerCore(apkPath);
            
            if (!core.loadApk()) {
                throw new Exception("Failed to load APK: " + apkPath);
            }
            
            return core.getApkInfo();
        } catch (RuntimeException e) {
            throw new Exception(e.getMessage(), e);
        }
    }
    
    /**
     * Get all classes in the APK
     */
    public List<String> getAllClasses() throws Exception {
        checkLoaded();
        try {
            return core.getAllClasses();
        } catch (RuntimeException e) {
            throw new Exception(e.getMessage(), e);
        }
    }
    
    /**
     * Get source code of a class
     */
    public String getClassSource(String className) throws Exception {
        checkLoaded();
        try {
            String source = core.getClassSource(className);
            if (source == null) {
                throw new Exception("Class not found: " + className);
            }
            return source;
        } catch (RuntimeException e) {
            throw new Exception(e.getMessage(), e);
        }
    }
    
    /**
     * Get methods of a class
     */
    public List<String> getMethodsOfClass(String className) throws Exception {
        checkLoaded();
        try {
            List<String> methods = core.getMethodsOfClass(className);
            if (methods.isEmpty()) {
                // Check if class exists
                if (core.getClassSource(className) == null) {
                    throw new Exception("Class not found: " + className);
                }
            }
            return methods;
        } catch (RuntimeException e) {
            throw new Exception(e.getMessage(), e);
        }
    }
    
    /**
     * Get fields of a class
     */
    public List<String> getFieldsOfClass(String className) throws Exception {
        checkLoaded();
        try {
            List<String> fields = core.getFieldsOfClass(className);
            if (fields.isEmpty()) {
                // Check if class exists
                if (core.getClassSource(className) == null) {
                    throw new Exception("Class not found: " + className);
                }
            }
            return fields;
        } catch (RuntimeException e) {
            throw new Exception(e.getMessage(), e);
        }
    }
    
    /**
     * Get method source code
     */
    public String getMethodSource(String className, String methodName) throws Exception {
        checkLoaded();
        try {
            String methodCode = core.getMethodByName(className, methodName);
            if (methodCode == null) {
                throw new Exception("Method not found: " + methodName + " in class " + className);
            }
            return methodCode;
        } catch (RuntimeException e) {
            throw new Exception(e.getMessage(), e);
        }
    }
    
    /**
     * Search for methods across all classes
     */
    public Map<String, List<String>> searchMethod(String methodName) throws Exception {
        checkLoaded();
        try {
            return core.searchMethodByName(methodName);
        } catch (RuntimeException e) {
            throw new Exception(e.getMessage(), e);
        }
    }
    
    
    /**
     * Get exported components
     */
    public List<Map<String, Object>> getExportedComponents() throws Exception {
        checkLoaded();
        try {
            return core.getExportedComponentsAsMap();
        } catch (RuntimeException e) {
            throw new Exception(e.getMessage(), e);
        }
    }
    
    /**
     * Get AndroidManifest.xml content
     */
    public String getAndroidManifest() throws Exception {
        checkLoaded();
        try {
            String manifest = core.getAndroidManifest();
            if (manifest == null) {
                throw new Exception("AndroidManifest.xml not loaded");
            }
            return manifest;
        } catch (RuntimeException e) {
            throw new Exception(e.getMessage(), e);
        }
    }
    
    /**
     * Get main activity class
     */
    public String getMainActivity() throws Exception {
        checkLoaded();
        try {
            String mainActivity = core.getMainActivityClass();
            if (mainActivity == null) {
                throw new Exception("No main activity found");
            }
            return mainActivity;
        } catch (RuntimeException e) {
            throw new Exception(e.getMessage(), e);
        }
    }
    
    /**
     * Get all resource file names in the APK
     */
    public List<String> getAllResourceFileNames() throws Exception {
        checkLoaded();
        try {
            return core.getAllResourceFileNames();
        } catch (RuntimeException e) {
            throw new Exception(e.getMessage(), e);
        }
    }
    
    /**
     * Get content of a specific resource file
     */
    public String getResourceFile(String fileName) throws Exception {
        checkLoaded();
        try {
            String content = core.getResourceFile(fileName);
            if (content == null) {
                throw new Exception("Resource file not found: " + fileName);
            }
            return content;
        } catch (RuntimeException e) {
            throw new Exception(e.getMessage(), e);
        }
    }
    
    /**
     * Get smali code of a specific class
     */
    public String getSmaliOfClass(String className) throws Exception {
        checkLoaded();
        try {
            String smali = core.getSmaliOfClass(className);
            if (smali == null) {
                throw new Exception("Class not found or smali not available: " + className);
            }
            return smali;
        } catch (RuntimeException e) {
            throw new Exception(e.getMessage(), e);
        }
    }
    
    /**
     * Get smali code of a specific method
     */
    public String getSmaliOfMethod(String className, String methodName) throws Exception {
        checkLoaded();
        try {
            String smali = core.getSmaliOfMethod(className, methodName);
            if (smali == null) {
                throw new Exception("Method not found or smali not available: " + methodName + " in class " + className);
            }
            return smali;
        } catch (RuntimeException e) {
            throw new Exception(e.getMessage(), e);
        }
    }
    
    private void checkLoaded() throws Exception {
        if (core == null || !core.isLoaded()) {
            throw new Exception("No APK loaded. Call loadApk() first.");
        }
    }
    
    public void close() {
        if (core != null) {
            core.close();
            core = null;
        }
    }
}
