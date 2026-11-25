package com.debianchroot.term;

import java.util.ArrayList;
import java.util.List;

/**
 * Model and dependency-free serializer for user-customizable quick shell snippets.
 */
public final class SnippetManager {

    public static final class Snippet {
        public final String id;
        public final String title;
        public final String command;
        public final boolean autoExecute;

        public Snippet(String id, String title, String command, boolean autoExecute) {
            this.id = (id != null && !id.isEmpty()) ? id : String.valueOf(System.nanoTime());
            this.title = title != null ? title : "";
            this.command = command != null ? command : "";
            this.autoExecute = autoExecute;
        }
    }

    private SnippetManager() {}

    public static List<Snippet> getDefaultSnippets() {
        List<Snippet> list = new ArrayList<>();
        list.add(new Snippet("apt_update", "Actualizar Debian (apt)", "apt update && apt upgrade -y", true));
        list.add(new Snippet("sys_usage", "Disco y memoria RAM", "df -h && free -h", true));
        list.add(new Snippet("net_ip", "Red e IPs locales", "ip -brief addr", true));
        list.add(new Snippet("top_mem", "Procesos por memoria", "ps aux --sort=-%mem | head -n 15", true));
        list.add(new Snippet("setup_stor", "Configurar /sdcard", "setup-storage", true));
        list.add(new Snippet("apt_inst", "Instalar paquete apt...", "apt install -y ", false));
        return list;
    }

    public static String serialize(List<Snippet> snippets) {
        if (snippets == null || snippets.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Snippet s : snippets) {
            if (s == null) continue;
            sb.append(escapeField(s.id)).append('\t')
              .append(escapeField(s.title)).append('\t')
              .append(s.autoExecute ? "1" : "0").append('\t')
              .append(escapeField(s.command)).append('\n');
        }
        return sb.toString();
    }

    public static List<Snippet> deserialize(String raw) {
        List<Snippet> list = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return list;
        }
        String[] lines = raw.split("\n");
        for (String line : lines) {
            if (line.isEmpty()) continue;
            String[] parts = line.split("\t", 4);
            if (parts.length < 4) continue;
            String id = unescapeField(parts[0]);
            String title = unescapeField(parts[1]);
            boolean autoExec = "1".equals(parts[2]);
            String command = unescapeField(parts[3]);
            if (title.trim().isEmpty() && command.trim().isEmpty()) continue;
            list.add(new Snippet(id, title, command, autoExec));
        }
        return list;
    }

    private static String escapeField(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\') {
                sb.append("\\\\");
            } else if (c == '\t') {
                sb.append("\\t");
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String unescapeField(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                char next = raw.charAt(++i);
                if (next == '\\') sb.append('\\');
                else if (next == 't') sb.append('\t');
                else if (next == 'n') sb.append('\n');
                else if (next == 'r') sb.append('\r');
                else {
                    sb.append('\\').append(next);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
