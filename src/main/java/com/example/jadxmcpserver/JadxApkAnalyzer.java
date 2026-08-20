package com.example.jadxmcpserver;

import com.example.jadxmcpserver.cli.JadxApkAnalyzerCLI;

/**
 * JADX APK Analyzer - Backwards compatibility wrapper
 * This class maintains the original interface while delegating to the new CLI implementation
 * 
 * @deprecated Use JadxApkAnalyzerCLI directly for CLI functionality or JadxAnalyzerCore for programmatic access
 */
@Deprecated
public class JadxApkAnalyzer {
    
    private final JadxApkAnalyzerCLI cli;
    
    public JadxApkAnalyzer(String apkPath) {
        this.cli = new JadxApkAnalyzerCLI(apkPath);
    }
    
    /**
     * Start the interactive CLI
     */
    public void start() {
        cli.start();
    }
    
    /**
     * Show the interactive menu (delegates to CLI)
     */
    public void showInteractiveMenu() {
        cli.showInteractiveMenu();
    }
    
    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Usage: java JadxApkAnalyzer <apk_file>");
            System.out.println("Example: java JadxApkAnalyzer app.apk");
            System.out.println("\nNote: This class is deprecated. Use JadxApkAnalyzerCLI directly.");
            System.exit(1);
        }
        
        System.out.println("Warning: JadxApkAnalyzer is deprecated. Use JadxApkAnalyzerCLI instead.");
        JadxApkAnalyzer analyzer = new JadxApkAnalyzer(args[0]);
        analyzer.start();
    }
}
