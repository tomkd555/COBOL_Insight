package jp.cobolinsight.rules.sarif;

import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.json.JsonWriter;
import jp.cobolinsight.engineapi.spi.Rule;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * findings を SARIF 2.1.0 の JSON テキストへ整形する。severity→level の対応は
 * FindingLevel.sarifName に従う。出力は決定論とする: ルールは id 昇順、結果は
 * (ファイル・行・桁・ルールID・メッセージ)の昇順に正規化する。
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
            writer.beginObject()
                    .name("id").value(rule.id())
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
                    .endObject().endArray()
                    .endObject();
        }
        writer.endArray().endObject().endArray().endObject();
        return writer.toString();
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
