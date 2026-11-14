package cc.royalty.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 RFC4180 风格 CSV 解析（支持引号、逗号转义、CRLF）。
 * 首行为表头，返回有序 Map 列表；坏行由调用方按字段校验后归类。
 */
public final class Csv {

    public record Row(int lineNo, Map<String, String> fields, String raw) {
        public String get(String key) {
            String v = fields.get(key);
            return v == null ? null : v.trim();
        }
    }

    public static List<Row> parse(String content) {
        List<List<String>> records = splitRecords(content);
        if (records.isEmpty()) {
            return List.of();
        }
        List<String> header = records.get(0).stream().map(h -> h.trim().toLowerCase()).toList();
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            List<String> rec = records.get(i);
            if (rec.isEmpty() || (rec.size() == 1 && rec.get(0).isBlank())) {
                continue;
            }
            if (!rec.isEmpty() && rec.get(0).startsWith("#")) {
                continue; // 注释行
            }
            Map<String, String> map = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) {
                map.put(header.get(c), c < rec.size() ? rec.get(c) : "");
            }
            rows.add(new Row(i + 1, map, String.join(",", rec)));
        }
        return rows;
    }

    private static List<List<String>> splitRecords(String content) {
        List<List<String>> records = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        boolean fieldStarted = false;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(ch);
                }
            } else if (ch == '"') {
                inQuotes = true;
                fieldStarted = true;
            } else if (ch == ',') {
                fields.add(cur.toString());
                cur.setLength(0);
                fieldStarted = false;
            } else if (ch == '\n') {
                fields.add(cur.toString());
                records.add(fields);
                fields = new ArrayList<>();
                cur.setLength(0);
                fieldStarted = false;
            } else if (ch == '\r') {
                // 吞掉 CR，交给 LF 收尾
            } else {
                cur.append(ch);
                fieldStarted = true;
            }
        }
        if (fieldStarted || cur.length() > 0 || !fields.isEmpty()) {
            fields.add(cur.toString());
            records.add(fields);
        }
        return records;
    }

    private Csv() {
    }
}
