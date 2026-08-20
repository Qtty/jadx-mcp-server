package com.example.jadxmcpserver;

import com.example.jadxmcpserver.core.JadxAnalyzerCore;
import com.example.jadxmcpserver.model.ExportedComponent;
import java.util.*;

/**
 * Test class for JadxAnalyzerCore functionality
 */
public class CoreFunctionalityTest {
    
    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Usage: java CoreFunctionalityTest <apk_file>");
            System.exit(1);
        }
        
        System.out.println("Testing the refactored JadxAnalyzerCore...");
        JadxAnalyzerCore core = new JadxAnalyzerCore(args[0]);
        
        try {
            // Test loading
            System.out.println("Loading APK: " + args[0]);
            if (!core.loadApk()) {
                System.err.println("Failed to load APK");
                return;
            }
            
            // Test basic info
            Map<String, Object> info = core.getApkInfo();
            System.out.println("APK Info: " + info);
            
            // Test class listing
            List<String> classes = core.getAllClasses();
            System.out.println("Total classes: " + classes.size());
            if (!classes.isEmpty()) {
                System.out.println("First few classes:");
                for (int i = 0; i < Math.min(5, classes.size()); i++) {
                    System.out.println("  - " + classes.get(i));
                }
            }
            
            // Test exported components
            List<ExportedComponent> components = core.getExportedComponents();
            System.out.println("Exported components: " + components.size());
            for (ExportedComponent comp : components) {
                System.out.println("  - " + comp.type + ": " + comp.name);
            }
            
            // Test manifest
            String manifest = core.getAndroidManifest();
            if (manifest != null) {
                System.out.println("Manifest loaded: " + manifest.length() + " characters");
            }
            
            // Test main activity
            String mainActivity = core.getMainActivityClass();
            if (mainActivity != null) {
                System.out.println("Main activity: " + mainActivity);
            }
            
            // Test method search functionality
            if (!classes.isEmpty()) {
                String testClass = classes.get(0);
                System.out.println("Testing method operations with class: " + testClass);
                
                List<String> methods = core.getMethodsOfClass(testClass);
                System.out.println("  Methods: " + methods.size());
                
                List<String> fields = core.getFieldsOfClass(testClass);
                System.out.println("  Fields: " + fields.size());
                
                if (!methods.isEmpty()) {
                    String methodName = extractMethodName(methods.get(0));
                    if (methodName != null) {
                        String methodSource = core.getMethodByName(testClass, methodName);
                        if (methodSource != null) {
                            System.out.println("  Method source extracted: " + methodSource.length() + " characters");
                        }
                    }
                }
            }
            
            // Test search functionality
            Map<String, List<String>> searchResults = core.searchMethodByName("onCreate");
            System.out.println("Search results for 'onCreate': " + searchResults.size() + " classes");
            
            System.out.println("✅ Core testing completed successfully!");
            
        } catch (Exception e) {
            System.err.println("❌ Error during testing: " + e.getMessage());
            e.printStackTrace();
        } finally {
            core.close();
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