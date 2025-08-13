package com.example.jadxmcpserver.cli;

import com.example.jadxmcpserver.core.JadxAnalyzerCore;
import com.example.jadxmcpserver.model.CallGraphNode;
import com.example.jadxmcpserver.model.ExportedComponent;
import jadx.api.JavaClass;

import java.util.*;

/**
 * Command Line Interface for JADX APK Analyzer
 * Provides an interactive menu system to access core analysis functionality
 */
public class JadxApkAnalyzerCLI {
    
    private final JadxAnalyzerCore analyzer;
    private final Scanner scanner;
    
    public JadxApkAnalyzerCLI(String apkPath) {
        this.analyzer = new JadxAnalyzerCore(apkPath);
        this.scanner = new Scanner(System.in);
    }
    
    /**
     * Initialize the analyzer and start the interactive menu
     */
    public void start() {
        try {
            System.out.println("Loading APK...");
            if (!analyzer.loadApk()) {
                System.err.println("Failed to load APK");
                return;
            }
            
            Map<String, Object> apkInfo = analyzer.getApkInfo();
            System.out.println("APK loaded successfully!");
            System.out.println("Package: " + apkInfo.get("packageName"));
            System.out.println("Total classes: " + apkInfo.get("totalClasses"));
            
            showInteractiveMenu();
            
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            analyzer.close();
        }
    }
    
    /**
     * Display and handle the interactive menu
     */
    public void showInteractiveMenu() {
        while (true) {
            System.out.println("\n=== JADX APK ANALYZER MENU ===");
            System.out.println("1. Show exported components");
            System.out.println("2. Search and dump class");
            System.out.println("3. Dump source of exported component");
            System.out.println("4. Generate method call graph");
            System.out.println("5. List all classes");
            System.out.println("6. Get class source code");
            System.out.println("7. List methods of a class");
            System.out.println("8. List fields of a class");
            System.out.println("9. Get method source code");
            System.out.println("10. Search method across classes");
            System.out.println("11. Show AndroidManifest.xml");
            System.out.println("12. Show main activity");
            System.out.println("13. Show APK info");
            System.out.println("14. Exit");
            System.out.print("\nEnter choice (1-14): ");
            
            try {
                int choice = scanner.nextInt();
                scanner.nextLine(); // Consume newline
                
                switch (choice) {
                    case 1:
                        showExportedComponents();
                        break;
                        
                    case 2:
                        System.out.print("\nEnter class name to search: ");
                        String className = scanner.nextLine();
                        searchAndDumpClass(className);
                        break;
                        
                    case 3:
                        dumpExportedComponentSource();
                        break;
                        
                    case 4:
                        System.out.print("\nEnter method name to trace (e.g., loadUrl, WebView.loadUrl): ");
                        String methodName = scanner.nextLine();
                        generateCallGraphForMethod(methodName);
                        break;
                        
                    case 5:
                        listAllClasses();
                        break;
                        
                    case 6:
                        System.out.print("\nEnter full class name: ");
                        String classNameForSource = scanner.nextLine();
                        showClassSource(classNameForSource);
                        break;
                        
                    case 7:
                        System.out.print("\nEnter full class name: ");
                        String classForMethods = scanner.nextLine();
                        listMethodsOfClass(classForMethods);
                        break;
                        
                    case 8:
                        System.out.print("\nEnter full class name: ");
                        String classForFields = scanner.nextLine();
                        listFieldsOfClass(classForFields);
                        break;
                        
                    case 9:
                        System.out.print("\nEnter full class name: ");
                        String classNameForMethod = scanner.nextLine();
                        System.out.print("Enter method name: ");
                        String methodNameToGet = scanner.nextLine();
                        showMethodSource(classNameForMethod, methodNameToGet);
                        break;
                        
                    case 10:
                        System.out.print("\nEnter method name to search: ");
                        String searchMethod = scanner.nextLine();
                        searchMethodAcrossClasses(searchMethod);
                        break;
                        
                    case 11:
                        showAndroidManifest();
                        break;
                        
                    case 12:
                        showMainActivity();
                        break;
                        
                    case 13:
                        showApkInfo();
                        break;
                        
                    case 14:
                        return;
                        
                    default:
                        System.out.println("Invalid choice");
                }
            } catch (Exception e) {
                System.err.println("Invalid input");
                scanner.nextLine(); // Clear buffer
            }
        }
    }
    
    private void showExportedComponents() {
        List<ExportedComponent> components = analyzer.getExportedComponents();
        
        if (components == null || components.isEmpty()) {
            System.out.println("No exported components found.");
            return;
        }
        
        System.out.println("\n=== EXPORTED COMPONENTS ===\n");
        System.out.println("Found " + components.size() + " exported components:\n");
        
        // Group by type
        Map<String, List<ExportedComponent>> grouped = analyzer.getExportedComponentsByType();
        
        printComponentsByType(grouped, "activity", "ACTIVITIES");
        printComponentsByType(grouped, "service", "SERVICES");
        printComponentsByType(grouped, "receiver", "BROADCAST RECEIVERS");
        printComponentsByType(grouped, "provider", "CONTENT PROVIDERS");
        
        // Print summary
        System.out.println("\n=== SUMMARY ===");
        System.out.println("Total exported components: " + components.size());
        System.out.println("Activities: " + grouped.getOrDefault("activity", new ArrayList<>()).size());
        System.out.println("Services: " + grouped.getOrDefault("service", new ArrayList<>()).size());
        System.out.println("Receivers: " + grouped.getOrDefault("receiver", new ArrayList<>()).size());
        System.out.println("Providers: " + grouped.getOrDefault("provider", new ArrayList<>()).size());
    }
    
    private void searchAndDumpClass(String className) {
        System.out.println("\nSearching for class: " + className);
        List<JavaClass> matches = analyzer.searchClasses(className);
        
        if (matches.isEmpty()) {
            System.out.println("No classes found matching: " + className);
            return;
        }
        
        // If multiple matches, let user choose
        JavaClass selectedClass = null;
        if (matches.size() == 1) {
            selectedClass = matches.get(0);
        } else {
            System.out.println("\nFound " + matches.size() + " matching classes:");
            for (int i = 0; i < matches.size(); i++) {
                System.out.println((i + 1) + ". " + matches.get(i).getFullName());
            }
            
            System.out.print("\nSelect class number (1-" + matches.size() + "): ");
            try {
                int choice = scanner.nextInt();
                if (choice >= 1 && choice <= matches.size()) {
                    selectedClass = matches.get(choice - 1);
                }
            } catch (Exception e) {
                System.err.println("Invalid selection");
                return;
            }
        }
        
        if (selectedClass != null) {
            dumpClassDetails(selectedClass.getFullName());
        }
    }
    
    private void dumpExportedComponentSource() {
        List<ExportedComponent> components = analyzer.getExportedComponents();
        
        if (components == null || components.isEmpty()) {
            System.out.println("No exported components found.");
            return;
        }
        
        System.out.println("\nExported components:");
        for (int i = 0; i < components.size(); i++) {
            System.out.println((i + 1) + ". " + components.get(i).name);
        }
        System.out.print("\nSelect component number: ");
        int compChoice = scanner.nextInt();
        scanner.nextLine(); // Consume newline
        
        if (compChoice >= 1 && compChoice <= components.size()) {
            String compName = components.get(compChoice - 1).name;
            searchAndDumpClass(compName);
        }
    }
    
    private void generateCallGraphForMethod(String targetMethod) {
        System.out.println("\n=== CALL GRAPH for: " + targetMethod + " ===\n");
        System.out.println("Searching for method calls... This may take a moment.\n");
        
        JadxAnalyzerCore.CallGraphResult result = analyzer.generateCallGraphForMethod(targetMethod);
        
        if (!result.success) {
            System.out.println(result.message);
            if (result.suggestions != null && !result.suggestions.isEmpty()) {
                System.out.println("\nDid you mean one of these methods?");
                for (String suggestion : result.suggestions) {
                    System.out.println("  - " + suggestion);
                }
            }
            return;
        }
        
        System.out.println("Found " + result.targetNodes.size() + " matching method(s):");
        for (CallGraphNode node : result.targetNodes) {
            System.out.println("  - " + node.fullSignature);
        }
        System.out.println();
        
        // Display the call graph
        System.out.println("\nCall Hierarchy (bottom-up):");
        System.out.println("============================\n");
        
        for (CallGraphNode targetNode : result.targetNodes) {
            printCallTree(targetNode, 0, new HashSet<>());
        }
        
        // Display entry points
        System.out.println("\n=== ENTRY POINTS ===");
        if (result.entryPoints.isEmpty()) {
            System.out.println("No clear entry points found.");
        } else {
            List<ExportedComponent> exportedComponents = analyzer.getExportedComponents();
            for (CallGraphNode entry : result.entryPoints) {
                System.out.println("  - " + entry.fullSignature);
                
                // Check if it's an exported component
                for (ExportedComponent comp : exportedComponents) {
                    if (entry.fullSignature.startsWith(comp.name)) {
                        System.out.println("    ^ EXPORTED " + comp.type.toUpperCase());
                    }
                }
            }
        }
        
        System.out.println("\n=== SUMMARY ===");
        System.out.println("Entry points found: " + result.entryPoints.size());
    }
    
    private void listAllClasses() {
        List<String> allClasses = analyzer.getAllClasses();
        System.out.println("\n=== ALL CLASSES (" + allClasses.size() + ") ===");
        int count = 0;
        for (String clsName : allClasses) {
            System.out.println(clsName);
            count++;
            if (count >= 50) {
                System.out.println("\n... and " + (allClasses.size() - 50) + " more classes");
                System.out.print("Show all? (y/n): ");
                String response = scanner.nextLine();
                if (response.toLowerCase().startsWith("y")) {
                    for (int i = 50; i < allClasses.size(); i++) {
                        System.out.println(allClasses.get(i));
                    }
                }
                break;
            }
        }
    }
    
    private void showClassSource(String className) {
        String source = analyzer.getClassSource(className);
        if (source != null) {
            System.out.println("\n=== SOURCE CODE FOR: " + className + " ===");
            System.out.println(source);
        } else {
            System.out.println("Class not found: " + className);
        }
    }
    
    private void listMethodsOfClass(String className) {
        List<String> methods = analyzer.getMethodsOfClass(className);
        if (!methods.isEmpty()) {
            System.out.println("\n=== METHODS IN: " + className + " ===");
            for (String method : methods) {
                System.out.println("  - " + method);
            }
            System.out.println("\nTotal methods: " + methods.size());
        } else {
            System.out.println("No methods found or class doesn't exist: " + className);
        }
    }
    
    private void listFieldsOfClass(String className) {
        List<String> fields = analyzer.getFieldsOfClass(className);
        if (!fields.isEmpty()) {
            System.out.println("\n=== FIELDS IN: " + className + " ===");
            for (String field : fields) {
                System.out.println("  - " + field);
            }
            System.out.println("\nTotal fields: " + fields.size());
        } else {
            System.out.println("No fields found or class doesn't exist: " + className);
        }
    }
    
    private void showMethodSource(String className, String methodName) {
        String methodCode = analyzer.getMethodByName(className, methodName);
        if (methodCode != null) {
            System.out.println("\n=== METHOD SOURCE: " + className + "." + methodName + " ===");
            System.out.println(methodCode);
        } else {
            System.out.println("Method not found: " + methodName + " in class " + className);
        }
    }
    
    private void searchMethodAcrossClasses(String methodName) {
        Map<String, List<String>> searchResults = analyzer.searchMethodByName(methodName);
        System.out.println("\n=== METHOD SEARCH RESULTS ===");
        if (searchResults.isEmpty()) {
            System.out.println("No methods found matching: " + methodName);
        } else {
            System.out.println("Found methods in " + searchResults.size() + " classes:\n");
            for (Map.Entry<String, List<String>> entry : searchResults.entrySet()) {
                System.out.println("Class: " + entry.getKey());
                for (String method : entry.getValue()) {
                    System.out.println("  - " + method);
                }
            }
        }
    }
    
    private void showAndroidManifest() {
        String manifest = analyzer.getAndroidManifest();
        if (manifest != null) {
            System.out.println("\n=== AndroidManifest.xml ===");
            System.out.println(manifest);
            System.out.println("\n=== End of AndroidManifest.xml ===");
        } else {
            System.out.println("AndroidManifest.xml not loaded");
        }
    }
    
    private void showMainActivity() {
        String mainActivity = analyzer.getMainActivityClass();
        if (mainActivity != null) {
            System.out.println("\n=== MAIN ACTIVITY ===");
            System.out.println("Main Activity Class: " + mainActivity);
            System.out.print("\nShow source code of main activity? (y/n): ");
            String response = scanner.nextLine();
            if (response.toLowerCase().startsWith("y")) {
                searchAndDumpClass(mainActivity);
            }
        } else {
            System.out.println("\nNo main activity found in AndroidManifest.xml");
            System.out.println("This might happen if:");
            System.out.println("  - The app has no launcher activity");
            System.out.println("  - The manifest uses a different structure");
            System.out.println("  - The app is a service or library");
        }
    }
    
    private void showApkInfo() {
        Map<String, Object> info = analyzer.getApkInfo();
        System.out.println("\nAPK: " + info.get("apkPath"));
        System.out.println("Package: " + (info.get("packageName") != null ? info.get("packageName") : "Not extracted"));
        System.out.println("Total classes: " + info.get("totalClasses"));
        System.out.println("Exported components: " + info.get("exportedComponents"));
        String mainAct = (String) info.get("mainActivity");
        if (mainAct != null) {
            System.out.println("Main Activity: " + mainAct);
        }
    }
    
    // Helper methods
    
    private void dumpClassDetails(String className) {
        Map<String, Object> details = analyzer.getClassDetails(className);
        if (details == null) {
            System.out.println("Class not found: " + className);
            return;
        }
        
        System.out.println("\n" + "=".repeat(80));
        System.out.println("CLASS: " + details.get("fullName"));
        System.out.println("=".repeat(80));
        
        // Basic info
        System.out.println("\nPackage: " + (details.get("package") != null ? details.get("package") : "(default)"));
        
        // Methods summary
        @SuppressWarnings("unchecked")
        List<String> methods = (List<String>) details.get("methods");
        System.out.println("\nMethods (" + details.get("methodCount") + "):");
        for (String method : methods) {
            System.out.println("  - " + method);
        }
        
        // Fields summary
        @SuppressWarnings("unchecked")
        List<String> fields = (List<String>) details.get("fields");
        System.out.println("\nFields (" + details.get("fieldCount") + "):");
        for (String field : fields) {
            System.out.println("  - " + field);
        }
        
        // Full source code
        System.out.println("\n" + "=".repeat(80));
        System.out.println("SOURCE CODE:");
        System.out.println("=".repeat(80));
        
        String code = (String) details.get("sourceCode");
        System.out.println(code);
        
        System.out.println("=".repeat(80));
    }
    
    private void printComponentsByType(Map<String, List<ExportedComponent>> grouped, String type, String header) {
        List<ExportedComponent> filtered = grouped.getOrDefault(type, new ArrayList<>());
        
        if (!filtered.isEmpty()) {
            System.out.println("\n--- " + header + " (" + filtered.size() + ") ---");
            for (ExportedComponent comp : filtered) {
                System.out.println("\n" + comp);
            }
        }
    }
    
    private void printCallTree(CallGraphNode node, int depth, Set<String> visited) {
        if (visited.contains(node.fullSignature)) {
            printIndented(depth, node.fullSignature + " [RECURSIVE]");
            return;
        }
        
        visited.add(node.fullSignature);
        printIndented(depth, node.fullSignature);
        
        if (depth < 10 && !node.callers.isEmpty()) {
            for (CallGraphNode caller : node.callers) {
                printCallTree(caller, depth + 1, visited);
            }
        }
    }
    
    private void printIndented(int depth, String text) {
        StringBuilder indent = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            if (i == depth - 1) {
                indent.append("└── ");
            } else {
                indent.append("    ");
            }
        }
        System.out.println(indent + text);
    }
    
    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Usage: java JadxApkAnalyzerCLI <apk_file>");
            System.out.println("Example: java JadxApkAnalyzerCLI app.apk");
            System.exit(1);
        }
        
        JadxApkAnalyzerCLI cli = new JadxApkAnalyzerCLI(args[0]);
        cli.start();
    }
}