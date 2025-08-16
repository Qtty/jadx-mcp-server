package com.example.jadxmcpserver.core;

import com.example.jadxmcpserver.model.CallGraphNode;
import com.example.jadxmcpserver.model.ExportedComponent;
import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaMethod;
import jadx.api.JavaField;
import jadx.api.ResourceFile;
import jadx.api.ICodeInfo;
import jadx.core.xmlgen.ResContainer;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.File;
import java.io.StringReader;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Core JADX APK Analyzer - Provides analysis functionality without UI
 * This class handles all APK analysis operations including:
 * - APK loading and decompilation
 * - Manifest parsing and component extraction
 * - Class and method analysis
 * - Call graph generation
 * - String and code search functionality
 */
public class JadxAnalyzerCore {
    
    private JadxDecompiler jadx;
    private String apkPath;
    private List<ExportedComponent> exportedComponents;
    private String manifestContent;
    private String packageName;
    
    public JadxAnalyzerCore(String apkPath) {
        this.apkPath = apkPath;
    }
    
    /**
     * Load and initialize the APK for analysis
     * @return true if loading was successful, false otherwise
     */
    public boolean loadApk() {
        File apkFile = new File(apkPath);
        
        if (!apkFile.exists()) {
            throw new RuntimeException("APK file not found: " + apkPath);
        }
        
        // Close previous instance if exists
        close();
        
        // Configure JADX
        JadxArgs jadxArgs = new JadxArgs();
        jadxArgs.setInputFile(apkFile);
        jadxArgs.setDeobfuscationOn(true);
        jadxArgs.setDeobfuscationMinLength(2);
        jadxArgs.setDeobfuscationMaxLength(64);
        
        try {
            jadx = new JadxDecompiler(jadxArgs);
            jadx.load();
            
            // Load manifest
            loadManifest();
            
            return true;
        } catch (Exception e) {
            throw new RuntimeException("Error loading APK: " + e.getMessage(), e);
        }
    }
    
    /**
     * Get basic APK information
     */
    public Map<String, Object> getApkInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("apkPath", apkPath);
        info.put("packageName", packageName);
        info.put("totalClasses", jadx != null ? jadx.getClasses().size() : 0);
        info.put("exportedComponents", exportedComponents != null ? exportedComponents.size() : 0);
        info.put("mainActivity", getMainActivityClass());
        return info;
    }
    
    /**
     * Get the AndroidManifest.xml content
     */
    public String getAndroidManifest() {
        return manifestContent;
    }
    
    /**
     * Get the package name from manifest
     */
    public String getPackageName() {
        return packageName;
    }
    
    /**
     * Get main activity class from AndroidManifest.xml
     */
    public String getMainActivityClass() {
        if (manifestContent == null) {
            return null;
        }
        
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(manifestContent)));
            
            NodeList activities = doc.getElementsByTagName("activity");
            
            for (int i = 0; i < activities.getLength(); i++) {
                Element activity = (Element) activities.item(i);
                NodeList intentFilters = activity.getElementsByTagName("intent-filter");
                
                for (int j = 0; j < intentFilters.getLength(); j++) {
                    Element intentFilter = (Element) intentFilters.item(j);
                    NodeList actions = intentFilter.getElementsByTagName("action");
                    NodeList categories = intentFilter.getElementsByTagName("category");
                    
                    boolean hasMainAction = false;
                    boolean hasLauncherCategory = false;
                    
                    for (int k = 0; k < actions.getLength(); k++) {
                        Element action = (Element) actions.item(k);
                        if ("android.intent.action.MAIN".equals(action.getAttribute("android:name"))) {
                            hasMainAction = true;
                        }
                    }
                    
                    for (int k = 0; k < categories.getLength(); k++) {
                        Element category = (Element) categories.item(k);
                        if ("android.intent.category.LAUNCHER".equals(category.getAttribute("android:name"))) {
                            hasLauncherCategory = true;
                        }
                    }
                    
                    if (hasMainAction && hasLauncherCategory) {
                        String activityName = activity.getAttribute("android:name");
                        if (activityName.startsWith(".")) {
                            activityName = packageName + activityName;
                        } else if (!activityName.contains(".")) {
                            activityName = packageName + "." + activityName;
                        }
                        return activityName;
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Error finding main activity: " + e.getMessage(), e);
        }
        
        return null;
    }
    
    /**
     * Get all classes in the APK
     */
    public List<String> getAllClasses() {
        List<String> classNames = new ArrayList<>();
        for (JavaClass javaClass : jadx.getClasses()) {
            classNames.add(javaClass.getFullName());
        }
        Collections.sort(classNames);
        return classNames;
    }
    
    /**
     * Get full source code of a given class
     */
    public String getClassSource(String className) {
        for (JavaClass javaClass : jadx.getClasses()) {
            if (javaClass.getFullName().equals(className)) {
                return javaClass.getCode();
            }
        }
        return null;
    }
    
    /**
     * Search for classes by name (supports partial matching)
     */
    public List<JavaClass> searchClasses(String className) {
        List<JavaClass> matches = new ArrayList<>();
        
        for (JavaClass javaClass : jadx.getClasses()) {
            String fullName = javaClass.getFullName();
            String simpleName = javaClass.getName();
            
            // Check for matches (exact, partial, or case-insensitive)
            if (fullName.equals(className) || 
                simpleName.equals(className) ||
                fullName.endsWith("." + className) ||
                fullName.toLowerCase().contains(className.toLowerCase()) ||
                simpleName.toLowerCase().contains(className.toLowerCase())) {
                matches.add(javaClass);
            }
        }
        
        return matches;
    }
    
    /**
     * Get detailed information about a class
     */
    public Map<String, Object> getClassDetails(String className) {
        JavaClass javaClass = findClass(className);
        if (javaClass == null) {
            return null;
        }
        
        Map<String, Object> details = new HashMap<>();
        details.put("fullName", javaClass.getFullName());
        details.put("package", javaClass.getPackage());
        details.put("methodCount", javaClass.getMethods().size());
        details.put("fieldCount", javaClass.getFields().size());
        details.put("methods", getMethodsOfClass(className));
        details.put("fields", getFieldsOfClass(className));
        details.put("sourceCode", javaClass.getCode());
        
        return details;
    }
    
    /**
     * List all methods in a specific class
     */
    public List<String> getMethodsOfClass(String className) {
        List<String> methods = new ArrayList<>();
        
        for (JavaClass javaClass : jadx.getClasses()) {
            if (javaClass.getFullName().equals(className)) {
                for (JavaMethod method : javaClass.getMethods()) {
                    methods.add(method.getFullName());
                }
                break;
            }
        }
        
        return methods;
    }
    
    /**
     * List all fields in a specific class
     */
    public List<String> getFieldsOfClass(String className) {
        List<String> fields = new ArrayList<>();
        
        for (JavaClass javaClass : jadx.getClasses()) {
            if (javaClass.getFullName().equals(className)) {
                for (JavaField field : javaClass.getFields()) {
                    fields.add(field.getType() + " " + field.getName());
                }
                break;
            }
        }
        
        return fields;
    }
    
    /**
     * Get source code of a specific method
     */
    public String getMethodByName(String className, String methodName) {
        JavaClass targetClass = findClass(className);
        if (targetClass == null) {
            return null;
        }
        
        String classCode = targetClass.getCode();
        return extractMethodCode(classCode, methodName);
    }
    
    /**
     * Search for methods by name across all classes
     */
    public Map<String, List<String>> searchMethodByName(String methodName) {
        Map<String, List<String>> results = new HashMap<>();
        
        for (JavaClass javaClass : jadx.getClasses()) {
            List<String> methods = new ArrayList<>();
            
            for (JavaMethod method : javaClass.getMethods()) {
                if (method.getName().equals(methodName) || 
                    method.getName().contains(methodName)) {
                    methods.add(method.getFullName());
                }
            }
            
            if (!methods.isEmpty()) {
                results.put(javaClass.getFullName(), methods);
            }
        }
        
        return results;
    }
    
    
    /**
     * Get all exported components from the manifest
     */
    public List<ExportedComponent> getExportedComponents() {
        return exportedComponents != null ? new ArrayList<>(exportedComponents) : new ArrayList<>();
    }
    
    /**
     * Get exported components grouped by type
     */
    public Map<String, List<ExportedComponent>> getExportedComponentsByType() {
        Map<String, List<ExportedComponent>> grouped = new HashMap<>();
        
        if (exportedComponents != null) {
            for (ExportedComponent component : exportedComponents) {
                grouped.computeIfAbsent(component.type, k -> new ArrayList<>()).add(component);
            }
        }
        
        return grouped;
    }
    
    /**
     * Get exported components as Maps for API serialization
     */
    public List<Map<String, Object>> getExportedComponentsAsMap() {
        List<Map<String, Object>> result = new ArrayList<>();
        if (exportedComponents != null) {
            for (ExportedComponent component : exportedComponents) {
                result.add(component.toMap());
            }
        }
        return result;
    }
    
    /**
     * Check if APK is loaded
     */
    public boolean isLoaded() {
        return jadx != null;
    }
    
    /**
     * Ensure APK is loaded, throw exception if not
     */
    public void checkLoaded() {
        if (!isLoaded()) {
            throw new RuntimeException("No APK loaded. Call loadApk() first.");
        }
    }
    
    /**
     * Generate call graph for a specific method
     */
    public CallGraphResult generateCallGraphForMethod(String targetMethod) {
        // Find all methods that match the target
        Set<CallGraphNode> targetNodes = findTargetMethods(targetMethod);
        
        if (targetNodes.isEmpty()) {
            return new CallGraphResult(false, "Method not found: " + targetMethod, null, null, getSimilarMethods(targetMethod));
        }
        
        // Build call graph starting from target methods
        Map<String, CallGraphNode> allNodes = new HashMap<>();
        Set<String> visited = new HashSet<>();
        
        for (CallGraphNode targetNode : targetNodes) {
            findCallersRecursively(targetNode, allNodes, visited, 0, 5); // Max depth 5
        }
        
        // Find entry points
        Set<CallGraphNode> entryPoints = findEntryPoints(targetNodes, allNodes);
        
        return new CallGraphResult(true, "Call graph generated successfully", targetNodes, entryPoints, null);
    }
    
    /**
     * Result class for call graph analysis
     */
    public static class CallGraphResult {
        public final boolean success;
        public final String message;
        public final Set<CallGraphNode> targetNodes;
        public final Set<CallGraphNode> entryPoints;
        public final Set<String> suggestions;
        
        public CallGraphResult(boolean success, String message, Set<CallGraphNode> targetNodes, 
                              Set<CallGraphNode> entryPoints, Set<String> suggestions) {
            this.success = success;
            this.message = message;
            this.targetNodes = targetNodes;
            this.entryPoints = entryPoints;
            this.suggestions = suggestions;
        }
    }
    
    // Private helper methods
    
    private void extractPackageName() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(manifestContent)));
            packageName = doc.getDocumentElement().getAttribute("package");
        } catch (Exception e) {
            throw new RuntimeException("Error extracting package name: " + e.getMessage(), e);
        }
    }
    
    private void loadManifest() {
        List<ResourceFile> resources = jadx.getResources();
        
        for (ResourceFile resource : resources) {
            String resName = resource.getOriginalName();
            if (resName.equals("AndroidManifest.xml")) {
                try {
                    ResContainer resContainer = resource.loadContent();
                    if (resContainer != null) {
                        ICodeInfo codeInfo = resContainer.getText();
                        if (codeInfo != null) {
                            manifestContent = codeInfo.toString();
                        } else if (resContainer.getDecodedData() != null) {
                            manifestContent = new String(resContainer.getDecodedData());
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Error loading manifest: " + e.getMessage(), e);
                }
                break;
            }
        }
        
        if (manifestContent != null) {
            exportedComponents = parseManifest(manifestContent);
            extractPackageName();
        }
    }
    
    private JavaClass findClass(String className) {
        for (JavaClass javaClass : jadx.getClasses()) {
            if (javaClass.getFullName().equals(className)) {
                return javaClass;
            }
        }
        return null;
    }
    
    private String extractMethodCode(String classCode, String methodName) {
        String methodPattern = "(public|private|protected|static|final|native|synchronized|abstract|transient)+" +
                              "[^{]+" + Pattern.quote(methodName) + "\\s*\\([^{]*\\{";
        
        Pattern pattern = Pattern.compile(methodPattern);
        Matcher matcher = pattern.matcher(classCode);
        
        if (matcher.find()) {
            int start = matcher.start();
            int braceStart = classCode.indexOf("{", start);
            
            if (braceStart != -1) {
                // Find matching closing brace
                int braceCount = 1;
                int pos = braceStart + 1;
                
                while (pos < classCode.length() && braceCount > 0) {
                    char ch = classCode.charAt(pos);
                    if (ch == '{') braceCount++;
                    else if (ch == '}') braceCount--;
                    pos++;
                }
                
                if (braceCount == 0) {
                    return classCode.substring(start, pos);
                }
            }
        }
        
        return null;
    }
    
    private String extractMethodSmali(String classSmali, String methodName) {
        String[] lines = classSmali.split("\n");
        StringBuilder methodSmali = new StringBuilder();
        boolean inMethod = false;
        boolean foundMethod = false;
        
        for (String line : lines) {
            // Look for method declaration
            if (line.contains(".method ") && line.contains(" " + methodName + "(")) {
                inMethod = true;
                foundMethod = true;
                methodSmali.append(line).append("\n");
            } else if (inMethod && line.contains(".end method")) {
                methodSmali.append(line).append("\n");
                break;
            } else if (inMethod) {
                methodSmali.append(line).append("\n");
            }
        }
        
        return foundMethod ? methodSmali.toString() : null;
    }
    
    private Set<CallGraphNode> findTargetMethods(String targetMethod) {
        Set<CallGraphNode> targetNodes = new HashSet<>();
        
        for (JavaClass javaClass : jadx.getClasses()) {
            for (JavaMethod method : javaClass.getMethods()) {
                String methodName = method.getName();
                String fullName = javaClass.getFullName() + "." + methodName;
                
                // Check if this method matches our target
                if (methodName.equals(targetMethod) || 
                    fullName.endsWith("." + targetMethod) ||
                    (targetMethod.contains(".") && fullName.endsWith(targetMethod))) {
                    
                    CallGraphNode node = new CallGraphNode(javaClass.getFullName(), methodName);
                    targetNodes.add(node);
                }
            }
        }
        
        return targetNodes;
    }
    
    private void findCallersRecursively(CallGraphNode targetNode, Map<String, CallGraphNode> allNodes, 
                                       Set<String> visited, int depth, int maxDepth) {
        if (depth >= maxDepth || visited.contains(targetNode.fullSignature)) {
            return;
        }
        
        visited.add(targetNode.fullSignature);
        allNodes.put(targetNode.fullSignature, targetNode);
        
        // Search all classes for methods that call this target
        for (JavaClass javaClass : jadx.getClasses()) {
            String classCode = javaClass.getCode();
            
            // Quick check if this class might contain calls to our target method
            if (!classCode.contains(targetNode.methodName)) {
                continue;
            }
            
            // Check each method in this class
            for (JavaMethod method : javaClass.getMethods()) {
                if (methodCallsTarget(javaClass, method, targetNode)) {
                    CallGraphNode callerNode = allNodes.computeIfAbsent(
                        javaClass.getFullName() + "." + method.getName(),
                        k -> new CallGraphNode(javaClass.getFullName(), method.getName())
                    );
                    
                    targetNode.callers.add(callerNode);
                    
                    // Recursively find callers of this caller
                    findCallersRecursively(callerNode, allNodes, visited, depth + 1, maxDepth);
                }
            }
        }
    }
    
    private boolean methodCallsTarget(JavaClass callerClass, JavaMethod callerMethod, CallGraphNode targetNode) {
        try {
            String methodCode = extractMethodCode(callerClass.getCode(), callerMethod.getName());
            if (methodCode == null) {
                return false;
            }
            
            // Check for method call patterns
            // 1. Direct method call: methodName(
            if (methodCode.contains(targetNode.methodName + "(")) {
                return true;
            }
            
            // 2. Qualified call: ClassName.methodName( or object.methodName(
            Pattern qualifiedCall = Pattern.compile("\\b\\w+\\." + Pattern.quote(targetNode.methodName) + "\\s*\\(");
            if (qualifiedCall.matcher(methodCode).find()) {
                return true;
            }
            
            // 3. For specific patterns like WebView.loadUrl
            if (targetNode.methodName.equals("loadUrl") && methodCode.toLowerCase().contains("webview")) {
                Pattern webViewLoadUrl = Pattern.compile("\\bwebView\\w*\\.loadUrl\\s*\\(", Pattern.CASE_INSENSITIVE);
                if (webViewLoadUrl.matcher(methodCode).find()) {
                    return true;
                }
            }
            
        } catch (Exception e) {
            // Silently ignore parsing errors
        }
        
        return false;
    }
    
    private Set<CallGraphNode> findEntryPoints(Set<CallGraphNode> targetNodes, Map<String, CallGraphNode> allNodes) {
        Set<CallGraphNode> entryPoints = new HashSet<>();
        
        // A node is an entry point if no other nodes in the graph call it
        for (CallGraphNode node : allNodes.values()) {
            boolean isEntry = true;
            
            for (CallGraphNode other : allNodes.values()) {
                if (other != node && other.callers.contains(node)) {
                    isEntry = false;
                    break;
                }
            }
            
            if (isEntry && !targetNodes.contains(node)) {
                entryPoints.add(node);
            }
        }
        
        return entryPoints;
    }
    
    private Set<String> getSimilarMethods(String targetMethod) {
        Set<String> suggestions = new HashSet<>();
        
        for (JavaClass javaClass : jadx.getClasses()) {
            for (JavaMethod method : javaClass.getMethods()) {
                if (method.getName().toLowerCase().contains(targetMethod.toLowerCase())) {
                    suggestions.add(method.getName());
                    if (suggestions.size() >= 10) {
                        return suggestions;
                    }
                }
            }
        }
        
        return suggestions;
    }
    
    private List<ExportedComponent> parseManifest(String manifestXml) {
        List<ExportedComponent> components = new ArrayList<>();
        
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(manifestXml)));
            doc.getDocumentElement().normalize();
            
            // Get package name
            String packageName = doc.getDocumentElement().getAttribute("package");
            
            // Component types to check
            String[] componentTypes = {"activity", "service", "receiver", "provider"};
            
            for (String componentType : componentTypes) {
                NodeList nodeList = doc.getElementsByTagName(componentType);
                
                for (int i = 0; i < nodeList.getLength(); i++) {
                    Node node = nodeList.item(i);
                    
                    if (node.getNodeType() == Node.ELEMENT_NODE) {
                        Element element = (Element) node;
                        ExportedComponent component = analyzeComponent(element, componentType, packageName);
                        
                        if (component != null && component.exported) {
                            components.add(component);
                        }
                    }
                }
            }
            
        } catch (Exception e) {
            throw new RuntimeException("Error parsing manifest: " + e.getMessage(), e);
        }
        
        return components;
    }
    
    private ExportedComponent analyzeComponent(Element element, String type, String packageName) {
        ExportedComponent component = new ExportedComponent();
        component.type = type;
        
        // Get component name
        String name = element.getAttribute("android:name");
        if (name.startsWith(".")) {
            name = packageName + name;
        } else if (!name.contains(".")) {
            name = packageName + "." + name;
        }
        component.name = name;
        
        // Check if explicitly exported
        String exportedAttr = element.getAttribute("android:exported");
        boolean hasIntentFilter = hasIntentFilter(element);
        
        if ("true".equals(exportedAttr)) {
            component.exported = true;
        } else if ("false".equals(exportedAttr)) {
            component.exported = false;
        } else {
            // If not explicitly set, components with intent-filters are exported by default
            component.exported = hasIntentFilter;
        }
        
        // Get permission if any
        component.permission = element.getAttribute("android:permission");
        
        // Extract intent filters
        NodeList intentFilters = element.getElementsByTagName("intent-filter");
        for (int i = 0; i < intentFilters.getLength(); i++) {
            Node filterNode = intentFilters.item(i);
            if (filterNode.getNodeType() == Node.ELEMENT_NODE) {
                String filterDesc = extractIntentFilter((Element) filterNode);
                if (!filterDesc.isEmpty()) {
                    component.intentFilters.add(filterDesc);
                }
            }
        }
        
        return component;
    }
    
    private boolean hasIntentFilter(Element element) {
        NodeList intentFilters = element.getElementsByTagName("intent-filter");
        return intentFilters.getLength() > 0;
    }
    
    private String extractIntentFilter(Element intentFilter) {
        StringBuilder sb = new StringBuilder();
        
        // Extract actions
        NodeList actions = intentFilter.getElementsByTagName("action");
        for (int i = 0; i < actions.getLength(); i++) {
            Element action = (Element) actions.item(i);
            String actionName = action.getAttribute("android:name");
            if (!actionName.isEmpty()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append("Action: ").append(actionName);
            }
        }
        
        // Extract categories
        NodeList categories = intentFilter.getElementsByTagName("category");
        for (int i = 0; i < categories.getLength(); i++) {
            Element category = (Element) categories.item(i);
            String categoryName = category.getAttribute("android:name");
            if (!categoryName.isEmpty()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append("Category: ").append(categoryName);
            }
        }
        
        // Extract data elements
        NodeList dataElements = intentFilter.getElementsByTagName("data");
        for (int i = 0; i < dataElements.getLength(); i++) {
            Element data = (Element) dataElements.item(i);
            String scheme = data.getAttribute("android:scheme");
            String host = data.getAttribute("android:host");
            String path = data.getAttribute("android:path");
            String mimeType = data.getAttribute("android:mimeType");
            
            if (!scheme.isEmpty() || !host.isEmpty() || !path.isEmpty() || !mimeType.isEmpty()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append("Data: ");
                if (!scheme.isEmpty()) sb.append("scheme=").append(scheme).append(" ");
                if (!host.isEmpty()) sb.append("host=").append(host).append(" ");
                if (!path.isEmpty()) sb.append("path=").append(path).append(" ");
                if (!mimeType.isEmpty()) sb.append("mimeType=").append(mimeType);
            }
        }
        
        return sb.toString();
    }
    
    /**
     * Get all resource file names in the APK
     */
    public List<String> getAllResourceFileNames() {
        checkLoaded();
        List<String> resourceNames = new ArrayList<>();
        
        List<ResourceFile> resources = jadx.getResources();
        for (ResourceFile resource : resources) {
            resourceNames.add(resource.getOriginalName());
        }
        
        Collections.sort(resourceNames);
        return resourceNames;
    }
    
    /**
     * Get content of a specific resource file
     */
    public String getResourceFile(String fileName) {
        checkLoaded();
        
        List<ResourceFile> resources = jadx.getResources();
        for (ResourceFile resource : resources) {
            if (resource.getOriginalName().equals(fileName)) {
                try {
                    ResContainer resContainer = resource.loadContent();
                    if (resContainer != null) {
                        ICodeInfo codeInfo = resContainer.getText();
                        if (codeInfo != null) {
                            return codeInfo.toString();
                        } else if (resContainer.getDecodedData() != null) {
                            return new String(resContainer.getDecodedData());
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Error loading resource file: " + fileName + " - " + e.getMessage(), e);
                }
            }
        }
        
        return null;
    }
    
    /**
     * Get smali code of a specific class
     */
    public String getSmaliOfClass(String className) {
        checkLoaded();
        
        for (JavaClass javaClass : jadx.getClasses()) {
            if (javaClass.getFullName().equals(className)) {
                try {
                    return javaClass.getSmali();
                } catch (Exception e) {
                    throw new RuntimeException("Error getting smali for class: " + className + " - " + e.getMessage(), e);
                }
            }
        }
        
        return null;
    }
    
    /**
     * Get smali code of a specific method
     */
    public String getSmaliOfMethod(String className, String methodName) {
        checkLoaded();
        
        for (JavaClass javaClass : jadx.getClasses()) {
            if (javaClass.getFullName().equals(className)) {
                try {
                    String classSmali = javaClass.getSmali();
                    if (classSmali != null) {
                        return extractMethodSmali(classSmali, methodName);
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Error getting smali for method: " + methodName + " in class: " + className + " - " + e.getMessage(), e);
                }
                break;
            }
        }
        
        return null;
    }

    /**
     * Close the analyzer and free resources
     */
    public void close() {
        if (jadx != null) {
            try {
                jadx.close();
            } catch (Exception e) {
                // Log error but don't throw
                System.err.println("Error closing JADX: " + e.getMessage());
            }
        }
    }
}