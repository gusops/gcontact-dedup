package com.contacts.cleaner;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal RFC4180-ish CSV reader/writer. Supports quoted fields with embedded
 * commas, newlines, and doubled-quote escaping. No external dependencies.
 */
public class CsvUtil {

    public static List<String[]> read(Path path) throws IOException {
        String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        if (content.startsWith("\uFEFF")) {
            content = content.substring(1);
        }
        List<String[]> rows = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;
        int n = content.length();
        while (i < n) {
            char c = content.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                        continue;
                    } else {
                        inQuotes = false;
                        i++;
                        continue;
                    }
                } else {
                    field.append(c);
                    i++;
                    continue;
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                    i++;
                    continue;
                } else if (c == ',') {
                    current.add(field.toString());
                    field.setLength(0);
                    i++;
                    continue;
                } else if (c == '\r') {
                    i++;
                    continue;
                } else if (c == '\n') {
                    current.add(field.toString());
                    field.setLength(0);
                    rows.add(current.toArray(new String[0]));
                    current = new ArrayList<>();
                    i++;
                    continue;
                } else {
                    field.append(c);
                    i++;
                    continue;
                }
            }
        }
        if (field.length() > 0 || !current.isEmpty()) {
            current.add(field.toString());
            rows.add(current.toArray(new String[0]));
        }
        return rows;
    }

    public static String escape(String v) {
        if (v == null) {
            return "";
        }
        boolean needsQuote = v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r");
        String out = v.replace("\"", "\"\"");
        return needsQuote ? "\"" + out + "\"" : out;
    }

    public static void writeRow(BufferedWriter w, List<String> fields) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(fields.get(i)));
        }
        sb.append("\r\n");
        w.write(sb.toString());
    }
}
