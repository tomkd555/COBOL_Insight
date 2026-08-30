package jp.cobolinsight.app.cli;

import java.util.ArrayList;
import java.util.List;

/**
 * Display formatting for unified diff lines. Handles ANSI coloring for terminals and formatting
 * into self-contained HTML with no external asset dependency. Computing the diff itself is the
 * responsibility of {@link jp.cobolinsight.app.fix.UnifiedDiffFormatter}; this class only takes
 * plain unified diff lines and colors/HTML-ifies them.
 */
final class DiffRendering {

    private static final String RESET = "[0m";
    private static final String BOLD = "[1m";
    private static final String RED = "[31m";
    private static final String GREEN = "[32m";
    private static final String CYAN = "[36m";

    /** The diff for a single file. The label is the original file's relative path. */
    record FileDiff(String label, List<String> lines) {
    }

    private DiffRendering() {
    }

    /** Colors unified diff lines with ANSI. Added=green, removed=red, hunk header=cyan, file header=bold. */
    static List<String> ansi(List<String> diffLines) {
        List<String> colored = new ArrayList<>(diffLines.size());
        for (String line : diffLines) {
            colored.add(color(line) + line + RESET);
        }
        return colored;
    }

    private static String color(String line) {
        if (line.startsWith("+++") || line.startsWith("---")) {
            return BOLD;
        }
        if (line.startsWith("@@")) {
            return CYAN;
        }
        if (line.startsWith("+")) {
            return GREEN;
        }
        if (line.startsWith("-")) {
            return RED;
        }
        return "";
    }

    /**
     * Formats the diffs of all files into a single self-contained HTML page (inline CSS, no
     * external references). Colors added lines, removed lines, hunk headers, and file headers, and
     * HTML-escapes the body text.
     */
    static String html(List<FileDiff> diffs) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"ja\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<title>COBOL Insight 修正案 diff</title>\n<style>\n")
                .append("body{font-family:'Consolas','Courier New',monospace;margin:1.5rem;"
                        + "background:#fff;color:#24292e;}\n")
                .append("h1{font-size:1.2rem;}\n")
                .append("h2{font-size:1rem;margin-top:1.5rem;border-bottom:1px solid #e1e4e8;"
                        + "padding-bottom:.3rem;}\n")
                .append("pre{background:#f6f8fa;border:1px solid #e1e4e8;border-radius:4px;"
                        + "padding:.6rem;overflow-x:auto;white-space:pre;}\n")
                .append(".add{color:#22863a;background:#e6ffed;display:block;}\n")
                .append(".del{color:#b31d28;background:#ffeef0;display:block;}\n")
                .append(".hunk{color:#6f42c1;display:block;}\n")
                .append(".meta{color:#6a737d;font-weight:bold;display:block;}\n")
                .append(".ctx{display:block;}\n")
                .append("</style>\n</head>\n<body>\n<h1>COBOL Insight 修正案 diff</h1>\n");
        if (diffs.isEmpty()) {
            sb.append("<p>修正案はない。</p>\n");
        }
        for (FileDiff diff : diffs) {
            sb.append("<h2>").append(escape(diff.label())).append("</h2>\n<pre>");
            for (String line : diff.lines()) {
                sb.append("<span class=\"").append(cssClass(line)).append("\">")
                        .append(escape(line)).append("</span>\n");
            }
            sb.append("</pre>\n");
        }
        sb.append("</body>\n</html>\n");
        return sb.toString();
    }

    private static String cssClass(String line) {
        if (line.startsWith("+++") || line.startsWith("---")) {
            return "meta";
        }
        if (line.startsWith("@@")) {
            return "hunk";
        }
        if (line.startsWith("+")) {
            return "add";
        }
        if (line.startsWith("-")) {
            return "del";
        }
        return "ctx";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
