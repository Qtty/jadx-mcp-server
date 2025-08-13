package com.example.jadxmcpserver.model;

import java.util.HashSet;
import java.util.Set;

public class CallGraphNode {
    public String className;
    public String methodName;
    public String fullSignature;
    public Set<CallGraphNode> callers = new HashSet<>();
    
    public CallGraphNode(String className, String methodName) {
        this.className = className;
        this.methodName = methodName;
        this.fullSignature = className + "." + methodName;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CallGraphNode that = (CallGraphNode) o;
        return fullSignature.equals(that.fullSignature);
    }
    
    @Override
    public int hashCode() {
        return fullSignature.hashCode();
    }
}