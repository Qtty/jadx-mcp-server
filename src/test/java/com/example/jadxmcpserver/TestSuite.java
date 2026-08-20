package com.example.jadxmcpserver;

import java.io.File;

/**
 * Comprehensive test suite for all refactored components
 */
public class TestSuite {
    
    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Usage: java TestSuite <apk_file> [test_type]");
            System.out.println();
            System.out.println("Available test types:");
            System.out.println("  core        - Test core functionality");
            System.out.println("  api         - Test API functionality");
            System.out.println("  cli         - Test CLI interface (interactive)");
            System.out.println("  compat      - Test backward compatibility");
            System.out.println("  all         - Run all non-interactive tests");
            System.out.println("  interactive - Run all tests including CLI");
            System.exit(1);
        }
        
        String apkFile = args[0];
        String testType = args.length > 1 ? args[1] : "all";
        
        // Validate APK file
        if (!new File(apkFile).exists()) {
            System.err.println("❌ Error: APK file '" + apkFile + "' not found!");
            System.exit(1);
        }
        
        System.out.println("🧪 JADX Analyzer Refactoring Test Suite");
        System.out.println("=" .repeat(50));
        System.out.println("APK File: " + apkFile);
        System.out.println("Test Type: " + testType);
        System.out.println();
        
        boolean success = true;
        
        switch (testType.toLowerCase()) {
            case "core":
                success = runCoreTest(apkFile);
                break;
            case "api":
                success = runApiTest(apkFile);
                break;
            case "cli":
                success = runCliTest(apkFile);
                break;
            case "compat":
                success = runCompatibilityTest(apkFile);
                break;
            case "all":
                success = runAllTests(apkFile, false);
                break;
            case "interactive":
                success = runAllTests(apkFile, true);
                break;
            default:
                System.err.println("❌ Unknown test type: " + testType);
                System.exit(1);
        }
        
        System.out.println();
        System.out.println("=" .repeat(50));
        if (success) {
            System.out.println("✅ All tests completed successfully!");
        } else {
            System.out.println("❌ Some tests failed!");
            System.exit(1);
        }
    }
    
    private static boolean runCoreTest(String apkFile) {
        System.out.println("🔧 Testing Core Functionality...");
        System.out.println("-" .repeat(30));
        try {
            CoreFunctionalityTest.main(new String[]{apkFile});
            return true;
        } catch (Exception e) {
            System.err.println("❌ Core test failed: " + e.getMessage());
            return false;
        }
    }
    
    private static boolean runApiTest(String apkFile) {
        System.out.println("🔌 Testing API Functionality...");
        System.out.println("-" .repeat(30));
        try {
            ApiFunctionalityTest.main(new String[]{apkFile});
            return true;
        } catch (Exception e) {
            System.err.println("❌ API test failed: " + e.getMessage());
            return false;
        }
    }
    
    private static boolean runCliTest(String apkFile) {
        System.out.println("💻 Testing CLI Functionality...");
        System.out.println("-" .repeat(30));
        System.out.println("Note: CLI test is interactive. Use menu option 14 to exit.");
        try {
            CliFunctionalityTest.main(new String[]{apkFile});
            return true;
        } catch (Exception e) {
            System.err.println("❌ CLI test failed: " + e.getMessage());
            return false;
        }
    }
    
    private static boolean runCompatibilityTest(String apkFile) {
        System.out.println("🔄 Testing Backward Compatibility...");
        System.out.println("-" .repeat(30));
        try {
            BackwardCompatibilityTest.main(new String[]{apkFile});
            return true;
        } catch (Exception e) {
            System.err.println("❌ Compatibility test failed: " + e.getMessage());
            return false;
        }
    }
    
    private static boolean runAllTests(String apkFile, boolean includeInteractive) {
        boolean success = true;
        
        success &= runCoreTest(apkFile);
        System.out.println();
        
        success &= runApiTest(apkFile);
        System.out.println();
        
        success &= runCompatibilityTest(apkFile);
        System.out.println();
        
        if (includeInteractive) {
            System.out.println("Press Enter to continue with CLI test, or Ctrl+C to skip...");
            try {
                System.in.read();
            } catch (Exception ignored) {}
            
            success &= runCliTest(apkFile);
        } else {
            System.out.println("💻 CLI test skipped (use 'interactive' mode to include)");
        }
        
        return success;
    }
}