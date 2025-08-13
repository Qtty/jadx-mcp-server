package com.example.jadxmcpserver.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ExportedComponent {
    public String type;
    public String name;
    public List<String> intentFilters = new ArrayList<>();
    public String permission;
    public boolean exported;
    
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("type", type);
        map.put("name", name);
        map.put("intentFilters", intentFilters);
        map.put("permission", permission);
        map.put("exported", exported);
        return map;
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Type: ").append(type).append("\n");
        sb.append("Name: ").append(name).append("\n");
        sb.append("Exported: ").append(exported).append("\n");
        if (permission != null && !permission.isEmpty()) {
            sb.append("Permission: ").append(permission).append("\n");
        }
        if (!intentFilters.isEmpty()) {
            sb.append("Intent Filters:\n");
            for (String filter : intentFilters) {
                sb.append("  - ").append(filter).append("\n");
            }
        }
        return sb.toString();
    }
}