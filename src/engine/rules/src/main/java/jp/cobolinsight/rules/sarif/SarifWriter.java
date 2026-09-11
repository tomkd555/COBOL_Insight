package jp.cobolinsight.rules.sarif;

import jp.cobolinsight.core.finding.CodeFlow;
import jp.cobolinsight.core.finding.CodeFlowStep;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FixSuggestion;
import jp.cobolinsight.core.finding.TextEdit;
import jp.cobolinsight.core.json.JsonWriter;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Formats findings as SARIF 2.1.0 JSON text. The severity→level mapping follows
 * FindingLevel.sarifName. A finding with a fix suggestion gets fixes (a source-range replacement
 * under artifactChanges), and a finding with a taint-tracking-derived path gets codeFlows (a
 * location sequence under threadFlows). A finding with neither omits the corresponding key. Output
 * is deterministic: rules are normalized in ascending id order, and results in ascending
 * (file, line, column, rule id, message) order.
 */
public final class SarifWriter {

    public static final String SCHEMA_URI =
            "https://docs.oasis-open.org/sarif/sarif/v2.1.0/errata01/os/schemas/sarif-schema-2.1.0.json";
    public static final String TOOL_NAME = "COBOL Insight";
    public static final String TOOL_VERSION = "0.1.0";

    private SarifWriter() {
    }

    /** The deterministic sort order for findings. Ascending by file (slash-normalized), line, column, rule id, message. */
    public static Comparator<Finding> findingOrder() {
        return Comparator
                .comparing((Finding f) -> f.location().file().replace('\\', '/'))
                .thenComparingInt(f -> f.location().line())
                .thenComparingInt(f -> f.location().column())
                .thenComparing(Finding::ruleId)
                .thenComparing(Finding::message);
    }

    /** A run over the whole asset folder, which is every run but a scoped {@code lint}. */
    public static String toJson(List<Rule> rules, List<Finding> findings) {
        return toJson(rules, findings, List.of());
    }

    /**
     * The same, for a run that covered part of the asset folder. The scopes are written into the
     * run as a property of its own, so that the file says what it covers: a scoped result and a
     * whole-folder result are otherwise the same document, and the partial one reads as an estate
     * with fewer defects than it has.
     */
    public static String toJson(List<Rule> rules, List<Finding> findings, List<String> scope) {
        List<Rule> sortedRules = new ArrayList<>(rules);
        sortedRules.sort(Comparator.comparing(rule -> rule.meta().id()));
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
            RuleMeta meta = rule.meta();
            writer.beginObject()
                    .name("id").value(meta.id())
                    .name("name").value(meta.name())
                    .name("shortDescription").beginObject()
                    .name("text").value(meta.summary()).endObject()
                    .name("fullDescription").beginObject()
                    .name("text").value(fullDescriptionOf(meta)).endObject()
                    .name("help").beginObject()
                    .name("text").value(helpTextOf(meta)).endObject()
                    .name("properties").beginObject()
                    .name("category").value(meta.category()).endObject()
                    .name("defaultConfiguration").beginObject()
                    .name("level").value(meta.defaultSeverity().toLevel().sarifName())
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
        writer.endArray();
        if (!scope.isEmpty()) {
            writer.name("properties").beginObject().name("scope").beginArray();
            for (String path : scope) {
                writer.value(path);
            }
            writer.endArray().endObject();
        }
        writer.endObject().endArray().endObject();
        return writer.toString();
    }

    /**
     * Serializes a taint-tracking-derived path into SARIF's codeFlows (codeFlow → threadFlows →
     * locations → location). A single path represents one execution flow and is expressed as one
     * threadFlow.
     */
    /** The full text of the detection description. Combines the summary, rationale, and detection condition, in that order, into a single text. */
    private static String fullDescriptionOf(RuleMeta meta) {
        return meta.summary() + "\n\n" + meta.rationale() + "\n\n" + meta.detection();
    }

    /** The help text a SARIF reader (an IDE, a review platform) shows alongside the finding. Carries the remedy and, if present, a before/after example. */
    private static String helpTextOf(RuleMeta meta) {
        StringBuilder out = new StringBuilder("直し方: ").append(meta.remedy());
        if (meta.hasExample()) {
            out.append("\n\n該当する例:\n").append(meta.badExample())
                    .append("\n\n直した例:\n").append(meta.goodExample());
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
     * Serializes a fix suggestion into SARIF's fixes (fix → artifactChanges → replacements). When a
     * single fix suggestion contains edits spanning multiple files, they are grouped per file to
     * satisfy artifactChanges' uniqueness constraint. An edit with an empty range represents an
     * insertion, where deletedRegion's start and end coincide.
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
            if (rules.get(i).meta().id().equals(ruleId)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Formats a path string as SARIF's artifactLocation.uri (a relative URI reference). Normalizes
     * separators to forward slashes, and represents each segment's characters outside RFC 3986's
     * pchar (unreserved characters, sub-delims, ':', '@') using UTF-8 percent-encoding. '/' is kept
     * as the segment delimiter.
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
