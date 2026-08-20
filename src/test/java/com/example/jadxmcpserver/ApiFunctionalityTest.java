package com.example.jadxmcpserver;

import java.util.*;

/**
 * Test class for JadxApkAnalyzerAPI functionality
 */
public class ApiFunctionalityTest {
    
    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Usage: java ApiFunctionalityTest <apk_file>");
            System.exit(1);
        }
        
        System.out.println("Testing the refactored JadxApkAnalyzerAPI...");
        JadxApkAnalyzerAPI api = new JadxApkAnalyzerAPI();
        
        try {
            // Test loading
            System.out.println("Loading APK: " + args[0]);
            Map<String, Object> loadResult = api.loadApk(args[0]);
            System.out.println("Load result: " + loadResult);
            
            // Test class operations
            List<String> classes = api.getAllClasses();
            System.out.println("Total classes: " + classes.size());
            
            if (!classes.isEmpty()) {
                String firstClass = classes.get(0);
                System.out.println("Testing with first class: " + firstClass);
                
                // Test class source
                try {
                    String source = api.getClassSource(firstClass);
                    System.out.println("  Source code length: " + source.length() + " characters");
                } catch (Exception e) {
                    System.out.println("  Could not get source for " + firstClass + ": " + e.getMessage());
                }
                
                // Test methods
                try {
                    List<String> methods = api.getMethodsOfClass(firstClass);
                    System.out.println("  Methods: " + methods.size());
                    
                    // Test method source extraction
                    if (!methods.isEmpty()) {
                        String methodName = extractMethodName(methods.get(0));
                        if (methodName != null) {
                            try {
                                String methodSource = api.getMethodSource(firstClass, methodName);
                                System.out.println("  Method source extracted: " + methodSource.length() + " characters");
                            } catch (Exception e) {
                                System.out.println("  Could not get method source: " + e.getMessage());
                            }
                        }
                    }
                } catch (Exception e) {
                    System.out.println("  Could not get methods for " + firstClass + ": " + e.getMessage());
                }
                
                // Test fields
                try {
                    List<String> fields = api.getFieldsOfClass(firstClass);
                    System.out.println("  Fields: " + fields.size());
                } catch (Exception e) {
                    System.out.println("  Could not get fields for " + firstClass + ": " + e.getMessage());
                }
            }
            
            // Test search
            Map<String, List<String>> searchResults = api.searchMethod("onCreate");
            System.out.println("Classes with 'onCreate' methods: " + searchResults.size());
            
            // Test exported components
            List<Map<String, Object>> components = api.getExportedComponents();
            System.out.println("Exported components: " + components.size());
            for (Map<String, Object> comp : components) {
                System.out.println("  - " + comp.get("type") + ": " + comp.get("name"));
            }
            
            // Test manifest
            try {
                String manifest = api.getAndroidManifest();
                System.out.println("Manifest loaded: " + manifest.length() + " characters");
            } catch (Exception e) {
                System.out.println("Could not get manifest: " + e.getMessage());
            }
            
            // Test main activity
            try {
                String mainActivity = api.getMainActivity();
                System.out.println("Main activity: " + mainActivity);
            } catch (Exception e) {
                System.out.println("Could not get main activity: " + e.getMessage());
            }
            
            // Test error handling
            System.out.println("Testing error handling...");
            try {
                api.getClassSource("non.existent.Class");
            } catch (Exception e) {
                System.out.println("  ✅ Properly caught error for non-existent class: " + e.getMessage());
            }
            
            System.out.println("✅ API testing completed successfully!");
            
        } catch (Exception e) {
            System.err.println("❌ Error during testing: " + e.getMessage());
            e.printStackTrace();
        } finally {
            api.close();
        }
    }
    
    private static String extractMethodName(String fullMethodName) {
        // Extract method name from full signature like "com.example.Class.methodName()"
        int lastDot = fullMethodName.lastIndexOf('.');
        if (lastDot != -1) {
            String methodPart = fullMethodName.substring(lastDot + 1);
            int openParen = methodPart.indexOf('(');
            if (openParen != -1) {
                return methodPart.substring(0, openParen);
            }
        }
        return null;
    }
}