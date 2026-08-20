package com.example.jadxmcpserver.core;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * MEASUREMENT ONLY. Does get_type_hierarchy accept the names get_type_hierarchy
 * hands out?
 *
 * The javadoc on getTypeHierarchy claims "every name in the answer is a name
 * this server accepts back", and the consumer doc repeats it for every
 * class-taking tool. This asks the narrowest version of that question -- feed
 * each emitted name straight back into the SAME tool -- because a tool that
 * refuses its own vocabulary cannot be excused by the shape of another one.
 *
 * Run: java -cp target/classes:target/test-classes:$(deps) \
 *          com.example.jadxmcpserver.core.TypeHierarchyRoundTripProbe &lt;apk&gt; [n]
 */
public final class TypeHierarchyRoundTripProbe {

    public static void main(String[] args) {
        String apk = args.length > 0 ? args[0] : "misc/DivaApplication.apk";
        int sample = args.length > 1 ? Integer.parseInt(args[1]) : 400;
        if (!new File(apk).exists()) {
            System.out.println("SKIP: no such apk: " + apk);
            return;
        }
        JadxAnalyzerCore core = new JadxAnalyzerCore(apk);
        core.loadApk();

        List<String> classes = core.getAllClasses();
        int step = Math.max(1, classes.size() / sample);

        int emitted = 0, rejected = 0, asked = 0;
        Map<String, Integer> byKey = new TreeMap<>();
        Map<String, List<String>> examples = new LinkedHashMap<>();

        for (int i = 0; i < classes.size(); i += step) {
            Map<String, Object> h;
            try {
                h = core.getTypeHierarchy(classes.get(i));
            } catch (Throwable t) {
                continue;
            }
            asked++;
            for (Map.Entry<String, Object> e : h.entrySet()) {
                for (String name : namesIn(e.getValue())) {
                    if (name.startsWith("@")) {
                        continue; // a cap or ambiguity marker, not a class name
                    }
                    emitted++;
                    try {
                        core.getTypeHierarchy(name);
                    } catch (Throwable t) {
                        rejected++;
                        byKey.merge(e.getKey(), 1, Integer::sum);
                        examples.computeIfAbsent(e.getKey(), k -> new ArrayList<>());
                        List<String> ex = examples.get(e.getKey());
                        if (ex.size() < 6) {
                            ex.add(classes.get(i) + " -> " + name);
                        }
                    }
                }
            }
        }
        System.out.printf("%s%n  hierarchies asked %d | names emitted %d | REFUSED %d%n",
                apk, asked, emitted, rejected);
        System.out.println("  by field: " + byKey);
        for (Map.Entry<String, List<String>> e : examples.entrySet()) {
            System.out.println("  " + e.getKey() + ":");
            for (String s : e.getValue()) {
                System.out.println("      " + s);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> namesIn(Object value) {
        Set<String> out = new LinkedHashSet<>();
        if (value instanceof String) {
            out.add((String) value);
        } else if (value instanceof List) {
            for (Object o : (List<Object>) value) {
                if (o instanceof String) {
                    out.add((String) o);
                }
            }
        }
        return out;
    }
}
