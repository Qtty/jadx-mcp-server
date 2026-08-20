package com.example.jadxmcpserver;

/**
 * Test class for backward compatibility with the original JadxApkAnalyzer
 */
public class BackwardCompatibilityTest {
    
    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Usage: java BackwardCompatibilityTest <apk_file>");
            System.exit(1);
        }
        
        System.out.println("Testing backward compatibility with original JadxApkAnalyzer...");
        System.out.println("This should show deprecation warnings but still work.");
        System.out.println();
        
        JadxApkAnalyzer analyzer = new JadxApkAnalyzer(args[0]);
        analyzer.start();
    }
}