package com.eventflow.service;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Minimal RFC 4180 writer with spreadsheet formula-injection protection. */
public final class CsvExport {
    private CsvExport() {}

    public static String cell(Object value) {
        if (value == null) return "";
        String text = String.valueOf(value);
        // Cells that start with = + - @ tab or CR are treated as formulas by Excel/Sheets: neutralise them.
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) text = "'" + text;
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            text = "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    public static String row(List<?> cells) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) out.append(',');
            out.append(cell(cells.get(i)));
        }
        return out.append("\r\n").toString();
    }

    /** UTF-8 with BOM so Excel opens non-ASCII names correctly. */
    public static byte[] bytes(String csv) {
        byte[] body = csv.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[body.length + 3];
        out[0] = (byte) 0xEF; out[1] = (byte) 0xBB; out[2] = (byte) 0xBF;
        System.arraycopy(body, 0, out, 3, body.length);
        return out;
    }
}
