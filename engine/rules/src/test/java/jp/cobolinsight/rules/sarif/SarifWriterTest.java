package jp.cobolinsight.rules.sarif;

import jp.cobolinsight.engineapi.finding.CodeFlow;
import jp.cobolinsight.engineapi.finding.CodeFlowStep;
import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.FindingLevel;
import jp.cobolinsight.engineapi.finding.FixSuggestion;
import jp.cobolinsight.engineapi.finding.Severity;
import jp.cobolinsight.engineapi.finding.TextEdit;
import jp.cobolinsight.engineapi.source.SourcePosition;
import jp.cobolinsight.engineapi.source.SourceRange;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.engineapi.spi.AnalysisPhase;
import jp.cobolinsight.engineapi.spi.Rule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SARIF 2.1.0 出力の構造・決定論の検証。 */
class SarifWriterTest {

    private static Rule stubRule(String id, Severity severity) {
        return new Rule() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Severity defaultSeverity() {
                return severity;
            }

            @Override
            public AnalysisPhase phase() {
                return AnalysisPhase.SYNTAX;
            }

            @Override
            public List<Finding> evaluate(AnalysisContext context) {
                return List.of();
            }
        };
    }

    private static final List<Rule> RULES =
            List.of(stubRule("R026", Severity.HIGH), stubRule("R008", Severity.MEDIUM));

    private static final List<Finding> FINDINGS = List.of(
            Finding.of("R026", FindingLevel.ERROR, "認証情報の直書き",
                    new SourcePosition("cobol\\B.cbl", 5, 12, SourcePosition.UNKNOWN_BYTE_OFFSET)),
            Finding.of("R008", FindingLevel.WARNING, "THRUなしPERFORM",
                    new SourcePosition("cobol/A.cbl", 10, 12, SourcePosition.UNKNOWN_BYTE_OFFSET)));

    @Test
    @SuppressWarnings("unchecked")
    void producesWellFormedSarif210() {
        String json = SarifWriter.toJson(RULES, FINDINGS);

        Object parsed = MiniJson.parse(json);
        Map<String, Object> root = (Map<String, Object>) parsed;
        assertEquals("2.1.0", root.get("version"));
        assertTrue(((String) root.get("$schema")).contains("sarif-schema-2.1.0"));

        List<Object> runs = (List<Object>) root.get("runs");
        assertEquals(1, runs.size());
        Map<String, Object> run = (Map<String, Object>) runs.get(0);
        Map<String, Object> driver = (Map<String, Object>)
                ((Map<String, Object>) run.get("tool")).get("driver");
        assertEquals("COBOL Insight", driver.get("name"));

        List<Object> rules = (List<Object>) driver.get("rules");
        assertEquals(List.of("R008", "R026"),
                rules.stream().map(r -> ((Map<String, Object>) r).get("id")).toList());
        Map<String, Object> r008 = (Map<String, Object>) rules.get(0);
        assertEquals("warning",
                ((Map<String, Object>) r008.get("defaultConfiguration")).get("level"));

        List<Object> results = (List<Object>) run.get("results");
        assertEquals(2, results.size());
        Map<String, Object> first = (Map<String, Object>) results.get(0);
        assertEquals("R008", first.get("ruleId"));
        assertEquals(0L, first.get("ruleIndex"));
        assertEquals("warning", first.get("level"));
        Map<String, Object> location = (Map<String, Object>)
                ((List<Object>) first.get("locations")).get(0);
        Map<String, Object> physical = (Map<String, Object>) location.get("physicalLocation");
        assertEquals("cobol/A.cbl",
                ((Map<String, Object>) physical.get("artifactLocation")).get("uri"));
        Map<String, Object> region = (Map<String, Object>) physical.get("region");
        assertEquals(10L, region.get("startLine"));
        assertEquals(12L, region.get("startColumn"));

        Map<String, Object> second = (Map<String, Object>) results.get(1);
        assertEquals("R026", second.get("ruleId"));
        assertEquals("error", second.get("level"));
        Map<String, Object> secondPhysical = (Map<String, Object>)
                ((Map<String, Object>) ((List<Object>) second.get("locations")).get(0))
                        .get("physicalLocation");
        assertEquals("cobol/B.cbl",
                ((Map<String, Object>) secondPhysical.get("artifactLocation")).get("uri"),
                "URIの区切りはバックスラッシュではなくスラッシュへ正規化されること");
    }

    @Test
    void uriIsPercentEncodedPerRfc3986() {
        Finding finding = Finding.of("R008", FindingLevel.WARNING, "エンコード確認",
                new SourcePosition("サンプル\\cobol dir\\A#1 100%.cbl", 1, 1,
                        SourcePosition.UNKNOWN_BYTE_OFFSET));

        String json = SarifWriter.toJson(RULES, List.of(finding));

        assertTrue(json.contains("\"uri\":\"%E3%82%B5%E3%83%B3%E3%83%97%E3%83%AB"
                        + "/cobol%20dir/A%231%20100%25.cbl\""),
                "空白・#・%・非ASCIIをセグメント毎にパーセントエンコードし、/は保持すること: "
                        + json);
    }

    @Test
    @SuppressWarnings("unchecked")
    void fixSuggestionsAreSerializedAsArtifactChanges() {
        SourceRange range = new SourceRange(
                new SourcePosition("cobol\\A.cbl", 10, 20, SourcePosition.UNKNOWN_BYTE_OFFSET),
                new SourcePosition("cobol\\A.cbl", 10, 20, SourcePosition.UNKNOWN_BYTE_OFFSET));
        SourceRange deletion = new SourceRange(
                new SourcePosition("cobol\\A.cbl", 12, 1, SourcePosition.UNKNOWN_BYTE_OFFSET),
                new SourcePosition("cobol\\A.cbl", 12, 9, SourcePosition.UNKNOWN_BYTE_OFFSET));
        Finding finding = new Finding("R008", FindingLevel.WARNING, "THRUなしPERFORM",
                new SourcePosition("cobol/A.cbl", 10, 12, SourcePosition.UNKNOWN_BYTE_OFFSET),
                List.of(),
                List.of(new FixSuggestion("ON SIZE ERROR 句を付与する",
                        List.of(new TextEdit(range, "\n    ON SIZE ERROR"),
                                new TextEdit(deletion, "")))));

        String json = SarifWriter.toJson(RULES, List.of(finding));

        Map<String, Object> result = (Map<String, Object>) ((List<Object>)
                ((Map<String, Object>) ((List<Object>)
                        ((Map<String, Object>) MiniJson.parse(json)).get("runs")).get(0))
                        .get("results")).get(0);
        List<Object> fixes = (List<Object>) result.get("fixes");
        assertEquals(1, fixes.size(), "修正案1件が fixes へ出ること: " + json);
        Map<String, Object> fix = (Map<String, Object>) fixes.get(0);
        assertEquals("ON SIZE ERROR 句を付与する",
                ((Map<String, Object>) fix.get("description")).get("text"));
        List<Object> changes = (List<Object>) fix.get("artifactChanges");
        assertEquals(1, changes.size(), "同一ファイルの編集は1つの artifactChange へ束ねること");
        Map<String, Object> change = (Map<String, Object>) changes.get(0);
        assertEquals("cobol/A.cbl",
                ((Map<String, Object>) change.get("artifactLocation")).get("uri"),
                "artifactLocation.uri は物理位置と同じ規約で正規化すること");
        List<Object> replacements = (List<Object>) change.get("replacements");
        assertEquals(2, replacements.size());
        Map<String, Object> insertion = (Map<String, Object>) replacements.get(0);
        Map<String, Object> region = (Map<String, Object>) insertion.get("deletedRegion");
        assertEquals(10L, region.get("startLine"));
        assertEquals(20L, region.get("startColumn"));
        assertEquals(10L, region.get("endLine"));
        assertEquals(20L, region.get("endColumn"));
        assertEquals("\n    ON SIZE ERROR",
                ((Map<String, Object>) insertion.get("insertedContent")).get("text"));
        Map<String, Object> removal = (Map<String, Object>) replacements.get(1);
        assertEquals(9L, ((Map<String, Object>) removal.get("deletedRegion")).get("endColumn"));
        assertEquals("", ((Map<String, Object>) removal.get("insertedContent")).get("text"),
                "削除は空文字列の insertedContent で表すこと");
    }

    @Test
    @SuppressWarnings("unchecked")
    void codeFlowsAreSerializedAsThreadFlowLocations() {
        Finding finding = new Finding("R008", FindingLevel.WARNING, "汚染経路付きの検出",
                new SourcePosition("cobol/A.cbl", 30, 12, SourcePosition.UNKNOWN_BYTE_OFFSET),
                List.of(new CodeFlow(List.of(
                        new CodeFlowStep(new SourcePosition("cobol\\A.cbl", 10, 16,
                                SourcePosition.UNKNOWN_BYTE_OFFSET), "WS-IN が外部入力を受け取る"),
                        new CodeFlowStep(new SourcePosition("cobol/A.cbl", 30, 12,
                                SourcePosition.UNKNOWN_BYTE_OFFSET), "WS-IN を出力する")))),
                List.of());

        String json = SarifWriter.toJson(RULES, List.of(finding));

        Map<String, Object> result = (Map<String, Object>) ((List<Object>)
                ((Map<String, Object>) ((List<Object>)
                        ((Map<String, Object>) MiniJson.parse(json)).get("runs")).get(0))
                        .get("results")).get(0);
        List<Object> codeFlows = (List<Object>) result.get("codeFlows");
        assertEquals(1, codeFlows.size(), "経路1本が codeFlows へ出ること: " + json);
        List<Object> threadFlows =
                (List<Object>) ((Map<String, Object>) codeFlows.get(0)).get("threadFlows");
        assertEquals(1, threadFlows.size(), "経路は1つの threadFlow で表すこと");
        List<Object> locations =
                (List<Object>) ((Map<String, Object>) threadFlows.get(0)).get("locations");
        assertEquals(2, locations.size());

        Map<String, Object> first = (Map<String, Object>)
                ((Map<String, Object>) locations.get(0)).get("location");
        assertEquals("WS-IN が外部入力を受け取る",
                ((Map<String, Object>) first.get("message")).get("text"));
        Map<String, Object> physical = (Map<String, Object>) first.get("physicalLocation");
        assertEquals("cobol/A.cbl",
                ((Map<String, Object>) physical.get("artifactLocation")).get("uri"),
                "経路の URI も物理位置と同じ規約で正規化すること");
        Map<String, Object> region = (Map<String, Object>) physical.get("region");
        assertEquals(10L, region.get("startLine"));
        assertEquals(16L, region.get("startColumn"));

        Map<String, Object> second = (Map<String, Object>)
                ((Map<String, Object>) locations.get(1)).get("location");
        assertEquals("WS-IN を出力する",
                ((Map<String, Object>) second.get("message")).get("text"));
        assertEquals(30L, ((Map<String, Object>) ((Map<String, Object>)
                second.get("physicalLocation")).get("region")).get("startLine"));
    }

    @Test
    void findingWithoutCodeFlowsOmitsTheCodeFlowsKey() {
        String json = SarifWriter.toJson(RULES, FINDINGS);

        assertFalse(json.contains("\"codeFlows\""),
                "経路が無い finding へ空の codeFlows を出さないこと");
    }

    @Test
    void findingWithoutFixesOmitsTheFixesKey() {
        String json = SarifWriter.toJson(RULES, FINDINGS);

        assertFalse(json.contains("\"fixes\""), "修正案が無い finding へ空の fixes を出さないこと");
    }

    @Test
    void outputIsDeterministicRegardlessOfInputOrder() {
        String first = SarifWriter.toJson(RULES, FINDINGS);
        String reversed = SarifWriter.toJson(List.of(RULES.get(1), RULES.get(0)),
                List.of(FINDINGS.get(1), FINDINGS.get(0)));
        assertEquals(first, reversed, "同一入力集合なら並び順によらず同一のSARIFテキストになること");
    }

    /** テスト検証用の最小JSONパーサー。値は Map・List・String・Long・Double・Boolean・null で表す。 */
    static final class MiniJson {

        private final String text;
        private int pos;

        private MiniJson(String text) {
            this.text = text;
        }

        static Object parse(String text) {
            MiniJson parser = new MiniJson(text);
            Object value = parser.value();
            parser.ws();
            if (parser.pos != text.length()) {
                throw new IllegalArgumentException("末尾に余分な文字がある: 位置 " + parser.pos);
            }
            return value;
        }

        private Object value() {
            ws();
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            expect('{');
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            ws();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                ws();
                String key = string();
                ws();
                expect(':');
                map.put(key, value());
                ws();
                if (peek() == ',') {
                    pos++;
                } else {
                    expect('}');
                    return map;
                }
            }
        }

        private List<Object> array() {
            expect('[');
            List<Object> list = new java.util.ArrayList<>();
            ws();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(value());
                ws();
                if (peek() == ',') {
                    pos++;
                } else {
                    expect(']');
                    return list;
                }
            }
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = text.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char escaped = text.charAt(pos++);
                    switch (escaped) {
                        case '"', '\\', '/' -> sb.append(escaped);
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            sb.append((char) Integer.parseInt(text, pos, pos + 4, 16));
                            pos += 4;
                        }
                        default -> throw new IllegalArgumentException(
                                "不正なエスケープ: \\" + escaped);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private Object number() {
            int start = pos;
            while (pos < text.length() && "+-.eE0123456789".indexOf(text.charAt(pos)) >= 0) {
                pos++;
            }
            String token = text.substring(start, pos);
            if (token.contains(".") || token.contains("e") || token.contains("E")) {
                return Double.parseDouble(token);
            }
            return Long.parseLong(token);
        }

        private Object literal(String expected, Object value) {
            if (!text.startsWith(expected, pos)) {
                throw new IllegalArgumentException("不正なリテラル: 位置 " + pos);
            }
            pos += expected.length();
            return value;
        }

        private void ws() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        private char peek() {
            return text.charAt(pos);
        }

        private void expect(char c) {
            if (text.charAt(pos) != c) {
                throw new IllegalArgumentException(
                        "期待した文字 " + c + " が無い: 位置 " + pos + " の " + text.charAt(pos));
            }
            pos++;
        }
    }
}
