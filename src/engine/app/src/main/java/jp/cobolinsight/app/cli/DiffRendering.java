package jp.cobolinsight.app.cli;

import java.util.ArrayList;
import java.util.List;

/**
 * unified diff 行の表示整形。ANSI 端末向けの着色と、外部資産に依存しない自己完結 HTML への整形を
 * 担う。差分算出そのものは {@link jp.cobolinsight.app.fix.UnifiedDiffFormatter} の責務で、本クラスは
 * 素の unified diff 行を受け取って着色・HTML 化するだけである。
 */
final class DiffRendering {

    private static final String RESET = "[0m";
    private static final String BOLD = "[1m";
    private static final String RED = "[31m";
    private static final String GREEN = "[32m";
    private static final String CYAN = "[36m";

    /** 1ファイル分の差分。ラベルは原本の相対パス。 */
    record FileDiff(String label, List<String> lines) {
    }

    private DiffRendering() {
    }

    /** unified diff 行を ANSI 着色する。追加=緑・削除=赤・ハンク見出し=シアン・ファイル見出し=太字。 */
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
     * 全ファイルの差分を1枚の自己完結 HTML(インライン CSS・外部参照なし)へ整形する。追加・削除・
     * ハンク見出し・ファイル見出しを配色し、本文は HTML エスケープする。
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
