package jp.cobolinsight.app.cli;

import jp.cobolinsight.app.cli.ReportRunner.AssetEntry;
import jp.cobolinsight.app.cli.ReportRunner.CallGraphSummary;
import jp.cobolinsight.app.cli.ReportRunner.EdgeView;
import jp.cobolinsight.core.finding.Finding;

import java.util.List;
import java.util.Map;

/**
 * 統合レポートの HTML・テキスト整形を担う。scan/lint/sql-lint/call-graph の統合結果を
 * 「資産インベントリ」「検出結果一覧(scan+lint)」「呼出関係の要約」「SQL 指摘」の4節へ組む。
 */
final class ReportRenderer {

    private static final String TITLE = "COBOL Insight 統合レポート";

    private ReportRenderer() {
    }

    static String toText(List<AssetEntry> inventory, List<Finding> scanFindings,
            List<Finding> lintFindings, List<Finding> sqlAdvice, CallGraphSummary callGraph,
            int exitCode) {
        StringBuilder sb = new StringBuilder();
        sb.append(TITLE).append('\n');
        sb.append("=".repeat(40)).append("\n\n");

        sb.append("1. 資産インベントリ (").append(inventory.size()).append("件)\n");
        for (AssetEntry asset : inventory) {
            sb.append("   ").append(asset.path()).append("  ").append(asset.type())
                    .append("  ").append(asset.codepage()).append("  ")
                    .append(asset.byteSize()).append(" bytes\n");
        }
        sb.append('\n');

        sb.append("2. 検出結果一覧 (scan: ").append(scanFindings.size())
                .append("件 / lint: ").append(lintFindings.size()).append("件)\n");
        sb.append("   [scan]\n");
        appendFindingLinesText(sb, scanFindings);
        sb.append("   [lint]\n");
        appendFindingLinesText(sb, lintFindings);
        sb.append('\n');

        sb.append("3. 呼出関係の要約\n");
        sb.append("   ノード ").append(callGraph.nodeCount()).append("件 (")
                .append(formatNodesByType(callGraph.nodesByType())).append(")\n");
        sb.append("   エッジ ").append(callGraph.edgeCount()).append("件\n");
        for (EdgeView edge : callGraph.edges()) {
            sb.append("     ").append(edge.from()).append(" -> ").append(edge.to())
                    .append(" [").append(edge.kind()).append("]\n");
        }
        sb.append('\n');

        sb.append("4. SQL指摘 (").append(sqlAdvice.size()).append("件)\n");
        appendFindingLinesText(sb, sqlAdvice);
        sb.append('\n');

        sb.append("終了コード: ").append(exitCode).append('\n');
        return sb.toString();
    }

    private static void appendFindingLinesText(StringBuilder sb, List<Finding> findings) {
        if (findings.isEmpty()) {
            sb.append("     (該当なし)\n");
            return;
        }
        for (Finding finding : findings) {
            sb.append("     ").append(finding.level().name()).append("  ")
                    .append(finding.ruleId()).append("  ")
                    .append(finding.location().file()).append(':')
                    .append(finding.location().line()).append("  ")
                    .append(finding.message()).append('\n');
        }
    }

    private static String formatNodesByType(Map<String, Integer> nodesByType) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> entry : nodesByType.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(entry.getKey()).append(": ").append(entry.getValue());
        }
        return sb.toString();
    }

    static String toHtml(List<AssetEntry> inventory, List<Finding> scanFindings,
            List<Finding> lintFindings, List<Finding> sqlAdvice, CallGraphSummary callGraph) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n");
        sb.append("<html lang=\"ja\">\n<head>\n<meta charset=\"UTF-8\">\n");
        sb.append("<title>").append(escape(TITLE)).append("</title>\n");
        sb.append("<style>\n").append(css()).append("</style>\n");
        sb.append("</head>\n<body>\n");
        sb.append("<h1>").append(escape(TITLE)).append("</h1>\n");

        sb.append("<h2>1. 資産インベントリ (").append(inventory.size()).append("件)</h2>\n");
        sb.append("<table>\n<thead><tr><th>パス</th><th>種別</th><th>コードページ</th>"
                + "<th>バイト長</th></tr></thead>\n<tbody>\n");
        for (AssetEntry asset : inventory) {
            sb.append("<tr><td>").append(escape(asset.path())).append("</td><td>")
                    .append(escape(asset.type())).append("</td><td>")
                    .append(escape(asset.codepage())).append("</td><td class=\"num\">")
                    .append(asset.byteSize()).append("</td></tr>\n");
        }
        sb.append("</tbody>\n</table>\n");

        sb.append("<h2>2. 検出結果一覧 (scan: ").append(scanFindings.size())
                .append("件 / lint: ").append(lintFindings.size()).append("件)</h2>\n");
        sb.append("<h3>scan 由来</h3>\n");
        appendFindingTableHtml(sb, scanFindings);
        sb.append("<h3>lint 検出</h3>\n");
        appendFindingTableHtml(sb, lintFindings);

        sb.append("<h2>3. 呼出関係の要約</h2>\n");
        sb.append("<p>ノード ").append(callGraph.nodeCount()).append("件 (")
                .append(escape(formatNodesByType(callGraph.nodesByType())))
                .append(") / エッジ ").append(callGraph.edgeCount()).append("件</p>\n");
        sb.append("<table>\n<thead><tr><th>呼出元</th><th>呼出先</th><th>種別</th></tr></thead>\n"
                + "<tbody>\n");
        for (EdgeView edge : callGraph.edges()) {
            sb.append("<tr><td>").append(escape(edge.from())).append("</td><td>")
                    .append(escape(edge.to())).append("</td><td>")
                    .append(escape(edge.kind())).append("</td></tr>\n");
        }
        sb.append("</tbody>\n</table>\n");

        sb.append("<h2>4. SQL指摘 (").append(sqlAdvice.size()).append("件)</h2>\n");
        appendFindingTableHtml(sb, sqlAdvice);

        sb.append("</body>\n</html>\n");
        return sb.toString();
    }

    private static void appendFindingTableHtml(StringBuilder sb, List<Finding> findings) {
        if (findings.isEmpty()) {
            sb.append("<p class=\"none\">(該当なし)</p>\n");
            return;
        }
        sb.append("<table>\n<thead><tr><th>レベル</th><th>ルール</th><th>位置</th>"
                + "<th>メッセージ</th></tr></thead>\n<tbody>\n");
        for (Finding finding : findings) {
            String level = finding.level().name();
            sb.append("<tr><td class=\"lv-").append(level.toLowerCase(java.util.Locale.ROOT))
                    .append("\">").append(level).append("</td><td>")
                    .append(escape(finding.ruleId())).append("</td><td>")
                    .append(escape(finding.location().file())).append(':')
                    .append(finding.location().line()).append("</td><td>")
                    .append(escape(finding.message())).append("</td></tr>\n");
        }
        sb.append("</tbody>\n</table>\n");
    }

    private static String css() {
        return """
                body { font-family: sans-serif; margin: 2rem; color: #1a1a1a; }
                h1 { border-bottom: 2px solid #333; padding-bottom: .3rem; }
                h2 { margin-top: 2rem; }
                table { border-collapse: collapse; width: 100%; margin: .5rem 0; }
                th, td { border: 1px solid #ccc; padding: .3rem .5rem; text-align: left;
                    vertical-align: top; }
                th { background: #f0f0f0; }
                td.num { text-align: right; }
                td.lv-error { color: #b00020; font-weight: bold; }
                td.lv-warning { color: #a15c00; font-weight: bold; }
                td.lv-note { color: #555; }
                p.none { color: #777; }
                """;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
