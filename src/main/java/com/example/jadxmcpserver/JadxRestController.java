package com.example.jadxmcpserver;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/jadx")
@CrossOrigin(origins = "*")
public class JadxRestController {

    @Autowired
    private JadxApkAnalyzerAPI jadxAPI;

    @PostMapping("/load-apk")
    public ResponseEntity<?> loadApk(@RequestBody Map<String, String> request) {
        try {
            String apkPath = request.get("apkPath");
            if (apkPath == null || apkPath.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "apkPath is required"));
            }
            
            Map<String, Object> result = jadxAPI.loadApk(apkPath);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/classes")
    public ResponseEntity<?> getAllClasses() {
        try {
            List<String> classes = jadxAPI.getAllClasses();
            return ResponseEntity.ok(Map.of("classes", classes));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/class/{className}/source")
    public ResponseEntity<?> getClassSource(@PathVariable String className) {
        try {
            String source = jadxAPI.getClassSource(className);
            return ResponseEntity.ok(Map.of("source", source));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/class/{className}/methods")
    public ResponseEntity<?> getClassMethods(@PathVariable String className) {
        try {
            List<String> methods = jadxAPI.getMethodsOfClass(className);
            return ResponseEntity.ok(Map.of("methods", methods));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/class/{className}/fields")
    public ResponseEntity<?> getClassFields(@PathVariable String className) {
        try {
            List<String> fields = jadxAPI.getFieldsOfClass(className);
            return ResponseEntity.ok(Map.of("fields", fields));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/class/{className}/method/{methodName}")
    public ResponseEntity<?> getMethodSource(@PathVariable String className, @PathVariable String methodName) {
        try {
            String source = jadxAPI.getMethodSource(className, methodName);
            return ResponseEntity.ok(Map.of("source", source));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/search/method/{methodName}")
    public ResponseEntity<?> searchMethod(@PathVariable String methodName) {
        try {
            Map<String, List<String>> results = jadxAPI.searchMethod(methodName);
            return ResponseEntity.ok(Map.of("results", results));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/components")
    public ResponseEntity<?> getExportedComponents() {
        try {
            List<Map<String, Object>> components = jadxAPI.getExportedComponents();
            return ResponseEntity.ok(Map.of("components", components));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/manifest")
    public ResponseEntity<?> getAndroidManifest() {
        try {
            String manifest = jadxAPI.getAndroidManifest();
            return ResponseEntity.ok(Map.of("manifest", manifest));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/main-activity")
    public ResponseEntity<?> getMainActivity() {
        try {
            String mainActivity = jadxAPI.getMainActivity();
            return ResponseEntity.ok(Map.of("mainActivity", mainActivity));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/resources")
    public ResponseEntity<?> getAllResourceFileNames() {
        try {
            List<String> resources = jadxAPI.getAllResourceFileNames();
            return ResponseEntity.ok(Map.of("resources", resources));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/resource/{fileName}")
    public ResponseEntity<?> getResourceFile(@PathVariable String fileName) {
        try {
            String content = jadxAPI.getResourceFile(fileName);
            return ResponseEntity.ok(Map.of("content", content));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/class/{className}/smali")
    public ResponseEntity<?> getSmaliOfClass(@PathVariable String className) {
        try {
            String smali = jadxAPI.getSmaliOfClass(className);
            return ResponseEntity.ok(Map.of("smali", smali));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/class/{className}/method/{methodName}/smali")
    public ResponseEntity<?> getSmaliOfMethod(@PathVariable String className, @PathVariable String methodName) {
        try {
            String smali = jadxAPI.getSmaliOfMethod(className, methodName);
            return ResponseEntity.ok(Map.of("smali", smali));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/close")
    public ResponseEntity<?> closeAnalyzer() {
        try {
            jadxAPI.close();
            return ResponseEntity.ok(Map.of("message", "Analyzer closed successfully"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}