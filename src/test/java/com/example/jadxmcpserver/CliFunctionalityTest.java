package com.example.jadxmcpserver;

import com.example.jadxmcpserver.cli.JadxApkAnalyzerCLI;

/**
 * Test class for JadxApkAnalyzerCLI functionality
 */
public class CliFunctionalityTest {
    
    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Usage: java CliFunctionalityTest <apk_file>");
            System.exit(1);
        }
        
        System.out.println("Testing the new CLI with refactored core...");
        System.out.println("Note: This will start the interactive CLI interface.");
        System.out.println("You can test various menu options and then exit with option 14.");
        System.out.println();
        
        JadxApkAnalyzerCLI cli = new JadxApkAnalyzerCLI(args[0]);
        cli.start();
    }
}