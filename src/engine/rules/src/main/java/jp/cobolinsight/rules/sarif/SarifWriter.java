package jp.cobolinsight.rules.sarif;

import jp.cobolinsight.engineapi.finding.CodeFlow;
import jp.cobolinsight.engineapi.finding.CodeFlowStep;
import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.FixSuggestion;
import jp.cobolinsight.engineapi.finding.TextEdit;
import jp.cobolinsight.engineapi.json.JsonWriter;
import jp.cobolinsight.engineapi.spi.Rule;
import jp.cobolinsight.engineapi.spi.RuleDoc;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * findings を SARIF 2.1.0 の JSON テキストへ整形する。severity→level の対応は
 * FindingLevel.sarifName に従う。修正案を持つ finding には fixes(artifactChanges の
 * ソース範囲置換)を付し、汚染追跡由来の経路を持つ finding には codeFlows(threadFlows の
 * 位置列)を付す。いずれも持たない finding へは当該キーを出さない。出力は決定論とする:
 * ルールは id 昇順、結果は(ファイル・行・桁・ルールID・メッセージ)の昇順に正規化する。
 */
public final class SarifWriter {

    public static final String SCHEMA_URI =
            "https://docs.oasis-open.org/sarif/sarif/v2.1.0/errata01/os/schemas/sarif-schema-2.1.0.json";
    public static final String TOOL_NAME = "COBOL Insight";
    public static final String TOOL_VERSION = "0.1.0";

    private SarifWriter() {
    }

    /** findings の決定論整列順。ファイル(スラッシュ正規化)・行・桁・ルールID・メッセージの昇順。 */
    public static Comparator<Finding> findingOrder() {
        return Comparator
                .comparing((Finding f) -> f.location().file().replace('\\', '/'))
                .thenComparingInt(f -> f.location().line())
                .thenComparingInt(f -> f.location().column())
                .thenComparing(Finding::ruleId)
                .thenComparing(Finding::message);
    }

    public static String toJson(List<Rule> rules, List<Finding> findings) {
        List<Rule> sortedRules = new ArrayList<>(rules);
        sortedRules.sort(Comparator.comparing(Rule::id));
        List<Finding> sortedFindings = new ArrayList<>(findings);
        sortedFindings.sort(findingOrder());

        JsonWriter writer = new JsonWriter();
        writer.beginObject()
                .name("$schema").value(SCHEMA_URI)
                .name("version").value("2.1.0")
                .name("runs").beginArray().beginObject()
                .name("tool").beginObject().name("driver").beginObject()
                .name("name").value(TOOL_NAME)
                .name("version").value(TOOL_VERSION)
                .name("rules").beginArray();
        for (Rule rule : sortedRules) {
            RuleDoc doc = rule.doc();
            writer.beginObject()
                    .name("id").value(rule.id())
                    .name("name").value(doc.name())
                    .name("shortDescription").beginObject()
                    .name("text").value(doc.summary()).endObject()
                    .name("fullDescription").beginObject()
                    .name("text").value(fullDescriptionOf(doc)).endObject()
                    .name("help").beginObject()
                    .name("text").value(helpTextOf(doc)).endObject()
                    .name("properties").beginObject()
                    .name("category").value(doc.category()).endObject()
                    .name("defaultConfiguration").beginObject()
                    .name("level").value(rule.defaultSeverity().toLevel().sarifName())
                    .endObject()
                    .endObject();
        }
        writer.endArray().endObject().endObject()
                .name("results").beginArray();
        for (Finding finding : sortedFindings) {
            int ruleIndex = indexOf(sortedRules, finding.ruleId());
            writer.beginObject()
                    .name("ruleId").value(finding.ruleId());
            if (ruleIndex >= 0) {
                writer.name("ruleIndex").value(ruleIndex);
            }
            writer.name("level").value(finding.level().sarifName())
                    .name("message").beginObject()
                    .name("text").value(finding.message()).endObject()
                    .name("locations").beginArray().beginObject()
                    .name("physicalLocation").beginObject()
                    .name("artifactLocation").beginObject()
                    .name("uri").value(uriOf(finding.location().file())).endObject()
                    .name("region").beginObject()
                    .name("startLine").value(finding.location().line())
                    .name("startColumn").value(finding.location().column())
                    .endObject()
                    .endObject()
                    .endObject().endArray();
            writeCodeFlows(writer, finding.codeFlows());
            writeFixes(writer, finding.fixes());
            writer.endObject();
        }
        writer.endArray().endObject().endArray().endObject();
        return writer.toString();
    }

    /**
     * 汚染追跡由来の経路を SARIF の codeFlows(codeFlow → threadFlows → locations → location)へ
     * 直列化する。1本の経路は単一の実行の流れであり、1つの threadFlow で表す。
     */
    /** 検出内容の全文。要約・理由・検出条件を、この順で1つのテキストへまとめる。 */
    private static String fullDescriptionOf(RuleDoc doc) {
        return doc.summary() + "\n\n" + doc.rationale() + "\n\n" + doc.detection();
    }

    /** SARIF を読む側(IDE・レビュー基盤)が指摘の隣へ出す助け。対処と、あれば対比の例を載せる。 */
    private static String helpTextOf(RuleDoc doc) {
        StringBuilder out = new StringBuilder("対処: ").append(doc.remedy());
        if (doc.hasExample()) {
            out.append("\n\n該当する例:\n").append(doc.badExample())
                    .append("\n\n直した例:\n").append(doc.goodExample());
        }
        return out.toString();
    }

    private static void writeCodeFlows(JsonWriter writer, List<CodeFlow> codeFlows) {
        if (codeFlows.isEmpty()) {
            return;
        }
        writer.name("codeFlows").beginArray();
        for (CodeFlow codeFlow : codeFlows) {
            writer.beginObject()
                    .name("threadFlows").beginArray().beginObject()
                    .name("locations").beginArray();
            for (CodeFlowStep step : codeFlow.steps()) {
                writer.beginObject()
                        .name("location").beginObject()
                        .name("physicalLocation").beginObject()
                        .name("artifactLocation").beginObject()
                        .name("uri").value(uriOf(step.position().file())).endObject()
                        .name("region").beginObject()
                        .name("startLine").value(step.position().line())
                        .name("startColumn").value(step.position().column())
                        .endObject()
                        .endObject()
                        .name("message").beginObject()
                        .name("text").value(step.message()).endObject()
                        .endObject()
                        .endObject();
            }
            writer.endArray().endObject().endArray().endObject();
        }
        writer.endArray();
    }

    /**
     * 修正案を SARIF の fixes(fix → artifactChanges → replacements)へ直列化する。1つの修正案が
     * 複数ファイルへまたがる編集を含む場合は、artifactChanges の一意性制約に従いファイル単位へ束ねる。
     * 空範囲の編集は挿入を表し、deletedRegion の開始と終了が一致する。
     */
    private static void writeFixes(JsonWriter writer, List<FixSuggestion> fixes) {
        if (fixes.isEmpty()) {
            return;
        }
        writer.name("fixes").beginArray();
        for (FixSuggestion fix : fixes) {
            Map<String, List<TextEdit>> editsByFile = new LinkedHashMap<>();
            for (TextEdit edit : fix.edits()) {
                editsByFile.computeIfAbsent(edit.range().start().file(), k -> new ArrayList<>())
                        .add(edit);
            }
            writer.beginObject()
                    .name("description").beginObject()
                    .name("text").value(fix.description()).endObject()
                    .name("artifactChanges").beginArray();
            for (Map.Entry<String, List<TextEdit>> entry : editsByFile.entrySet()) {
                writer.beginObject()
                        .name("artifactLocation").beginObject()
                        .name("uri").value(uriOf(entry.getKey())).endObject()
                        .name("replacements").beginArray();
                for (TextEdit edit : entry.getValue()) {
                    writer.beginObject()
                            .name("deletedRegion").beginObject()
                            .name("startLine").value(edit.range().start().line())
                            .name("startColumn").value(edit.range().start().column())
                            .name("endLine").value(edit.range().end().line())
                            .name("endColumn").value(edit.range().end().column())
                            .endObject()
                            .name("insertedContent").beginObject()
                            .name("text").value(edit.replacement()).endObject()
                            .endObject();
                }
                writer.endArray().endObject();
            }
            writer.endArray().endObject();
        }
        writer.endArray();
    }

    private static int indexOf(List<Rule> rules, String ruleId) {
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).id().equals(ruleId)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * パス文字列をSARIFのartifactLocation.uri(相対URI参照)へ整形する。区切りをスラッシュへ
     * 正規化し、各セグメントをRFC 3986のpchar(非予約文字・sub-delims・':'・'@')以外について
     * UTF-8のパーセントエンコードで表す。'/'はセグメント区切りとして保持する。
     */
    private static String uriOf(String file) {
        String normalized = file.replace('\\', '/');
        StringBuilder out = new StringBuilder(normalized.length());
        String[] segments = normalized.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                out.append('/');
            }
            appendEncodedSegment(out, segments[i]);
        }
        return out.toString();
    }

    private static void appendEncodedSegment(StringBuilder out, String segment) {
        for (byte b : segment.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if (isPchar(c)) {
                out.append(c);
            } else {
                out.append('%').append(String.format("%02X", b & 0xff));
            }
        }
    }

    private static boolean isPchar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || "-._~!$&'()*+,;=:@".indexOf(c) >= 0;
    }
}
