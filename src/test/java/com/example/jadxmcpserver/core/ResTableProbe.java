package com.example.jadxmcpserver.core;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.ResourceFile;
import jadx.core.xmlgen.ResContainer;

import java.io.File;

/** How do we get from "@string/fb_login_protocol_scheme" to its literal value? */
public final class ResTableProbe {

    public static void main(String[] args) throws Exception {
        String apk = args.length > 0 ? args[0] : "misc/DivaApplication.apk";
        JadxArgs jadxArgs = new JadxArgs();
        jadxArgs.setInputFile(new File(apk));
        jadxArgs.setDeobfuscationOn(true);

        try (JadxDecompiler jadx = new JadxDecompiler(jadxArgs)) {
            jadx.load();
            for (ResourceFile rf : jadx.getResources()) {
                if (!rf.getOriginalName().equals("resources.arsc")) {
                    continue;
                }
                ResContainer rc = rf.loadContent();
                System.out.println("container=" + rc.getName() + " type=" + rc.getDataType()
                        + " subFiles=" + rc.getSubFiles().size());
                for (ResContainer sub : rc.getSubFiles()) {
                    if (sub.getName().contains("string") || sub.getName().endsWith("values.xml")
                            || sub.getName().contains("/values/")) {
                        System.out.println("  SUB: " + sub.getName() + " type=" + sub.getDataType());
                        if (sub.getText() != null) {
                            String txt = sub.getText().getCodeStr();
                            System.out.println("    chars=" + txt.length());
                            for (String line : txt.split("\n")) {
                                if (line.contains("fb_login_protocol_scheme") || line.contains("<string ")) {
                                    System.out.println("    " + line.trim());
                                    break;
                                }
                            }
                        }
                    }
                }
                // First 15 subfile names regardless, to see the layout.
                int n = 0;
                for (ResContainer sub : rc.getSubFiles()) {
                    System.out.println("  name[" + n + "]: " + sub.getName());
                    if (++n >= 15) {
                        break;
                    }
                }
            }
        }
    }
}
