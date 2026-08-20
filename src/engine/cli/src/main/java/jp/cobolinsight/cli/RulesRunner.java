package jp.cobolinsight.cli;

import jp.cobolinsight.engineapi.json.JsonWriter;
import jp.cobolinsight.engineapi.pipeline.AnalysisServices;
import jp.cobolinsight.engineapi.spi.Rule;
import jp.cobolinsight.engineapi.spi.RuleDoc;
import jp.cobolinsight.rules.user.UserRuleLoader;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * `rules` の中核処理。組み込みルールと利用者定義ルールを束ね、説明つきの一覧を返す。
 * GUI はここが出す JSON をルールカタログの供給源とし、画面側でルール名や説明を持たない。
 */
public final class RulesRunner {

    /** 利用者定義ルールの ID 接頭辞。組み込み(R・S)と出所を見分ける唯一の手掛かりである。 */
    private static final String USER_RULE_PREFIX = "U";

    /** disabledRuleIds は設定ファイル由来の無効化指定。一覧の enabled はこれで決まる。 */
    public record Options(Path userRulesFile, String ruleId, Set<String> disabledRuleIds) {

        public Options(Path userRulesFile, String ruleId) {
            this(userRulesFile, ruleId, Set.of());
        }
    }

    /** detail は1件へ絞り込んだかどうか。端末向けの整形をここで切り替える。 */
    public record Result(List<Rule> rules, List<String> userRuleErrors, boolean detail,
            Set<String> disabledRuleIds, List<String> ruleConfigWarnings) {

        public Result {
            rules = List.copyOf(rules);
            userRuleErrors = List.copyOf(userRuleErrors);
            disabledRuleIds = Set.copyOf(disabledRuleIds);
            ruleConfigWarnings = List.copyOf(ruleConfigWarnings);
        }

        private boolean isEnabled(Rule rule) {
            return !disabledRuleIds.contains(rule.id());
        }

        /** GUI が読む形式。ルールは id 昇順で、説明の全項目を持つ。 */
        public String toJson() {
            JsonWriter writer = new JsonWriter();
            writer.beginObject()
                    .name("ruleCount").value(rules.size())
                    .name("rules").beginArray();
            for (Rule rule : rules) {
                RuleDoc doc = rule.doc();
                writer.beginObject()
                        .name("id").value(rule.id())
                        .name("name").value(doc.name())
                        .name("category").value(doc.category())
                        .name("severity").value(rule.defaultSeverity().name())
                        .name("phase").value(rule.phase().name())
                        .name("hasFix").value(rule.fixProducer().isPresent())
                        .name("source").value(sourceOf(rule))
                        .name("enabled").value(isEnabled(rule))
                        .name("summary").value(doc.summary())
                        .name("rationale").value(doc.rationale())
                        .name("detection").value(doc.detection())
                        .name("remedy").value(doc.remedy())
                        .name("badExample").value(doc.badExample())
                        .name("goodExample").value(doc.goodExample())
                        .endObject();
            }
            writer.endArray().name("userRuleErrors").beginArray();
            for (String error : userRuleErrors) {
                writer.value(error);
            }
            writer.endArray().name("ruleConfigWarnings").beginArray();
            for (String warning : ruleConfigWarnings) {
                writer.value(warning);
            }
            writer.endArray().endObject();
            return writer.toString();
        }

        /** 端末向けの一覧。1件へ絞り込んだ場合は説明の全文を示す。 */
        public String toText() {
            if (detail && rules.size() == 1) {
                return detailTextOf(rules.get(0));
            }
            StringBuilder out = new StringBuilder();
            out.append(String.format(Locale.ROOT, "%-6s %-4s %-14s %s%n",
                    "ID", "重大度", "カテゴリ", "名称"));
            for (Rule rule : rules) {
                out.append(String.format(Locale.ROOT, "%-6s %-4s %-14s %s%n",
                        rule.id(), rule.defaultSeverity().label(), rule.doc().category(),
                        rule.doc().name()));
            }
            long userCount = rules.stream().filter(r -> r.id().startsWith(USER_RULE_PREFIX))
                    .count();
            out.append(String.format(Locale.ROOT, "%n合計 %d 件(組み込み %d・利用者定義 %d)",
                    rules.size(), rules.size() - userCount, userCount));
            long disabledCount = rules.stream().filter(rule -> !isEnabled(rule)).count();
            if (disabledCount > 0) {
                out.append(String.format(Locale.ROOT, " うち無効 %d 件", disabledCount));
            }
            out.append(System.lineSeparator());
            for (String error : userRuleErrors) {
                out.append("警告: 利用者定義ルールの定義に誤りがある: ").append(error).append('\n');
            }
            for (String warning : ruleConfigWarnings) {
                out.append("警告: ルール設定: ").append(warning).append('\n');
            }
            return out.toString();
        }

        private static String detailTextOf(Rule rule) {
            RuleDoc doc = rule.doc();
            StringBuilder out = new StringBuilder();
            out.append(rule.id()).append(' ').append(doc.name()).append('\n')
                    .append("カテゴリ: ").append(doc.category())
                    .append(" / 重大度: ").append(rule.defaultSeverity().label())
                    .append(" / 解析段階: ").append(rule.phase().label())
                    .append(" / 修正案: ").append(rule.fixProducer().isPresent() ? "あり" : "なし")
                    .append("\n\n");
            appendSection(out, "何を検出するか", doc.summary());
            appendSection(out, "なぜ問題か", doc.rationale());
            appendSection(out, "検出条件", doc.detection());
            appendSection(out, "どう直すか", doc.remedy());
            if (doc.hasExample()) {
                appendSection(out, "該当する例", doc.badExample());
                appendSection(out, "直した例", doc.goodExample());
            }
            return out.toString();
        }

        private static void appendSection(StringBuilder out, String title, String body) {
            out.append(title).append('\n');
            for (String line : body.split("\n", -1)) {
                out.append("  ").append(line).append('\n');
            }
            out.append('\n');
        }

        private static String sourceOf(Rule rule) {
            return rule.id().startsWith(USER_RULE_PREFIX) ? "user" : "builtin";
        }
    }

    private RulesRunner() {
    }

    /** ruleId を指定した場合、その1件だけを返す(見つからなければ空)。 */
    public static Result run(Options options) {
        UserRuleLoader.LoadResult userRules = UserRuleLoader.load(options.userRulesFile());
        List<Rule> all = AnalysisServices.load(userRules.rules()).rules();
        String wanted = options.ruleId();
        boolean detail = wanted != null && !wanted.isBlank();
        List<Rule> selected = detail
                ? all.stream().filter(r -> r.id().equalsIgnoreCase(wanted.strip())).toList()
                : all;
        return new Result(selected, userRules.errors(), detail, options.disabledRuleIds(),
                unknownIdWarnings(all, options.disabledRuleIds()));
    }

    /**
     * カタログに無いルールIDの無効化指定を警告にする。設定ファイルを消さずにルールを入れ替えた
     * ときの取り残しを利用者へ示すためであり、指定そのものは捨てない。カタログ全体を持つのは
     * この経路だけのため、照合もここだけで行う。
     */
    private static List<String> unknownIdWarnings(List<Rule> all, Set<String> disabledRuleIds) {
        Set<String> known = all.stream().map(Rule::id).collect(Collectors.toSet());
        return disabledRuleIds.stream()
                .filter(id -> !known.contains(id))
                .map(id -> "無効化の指定にあるルールIDがカタログに無い: " + id)
                .toList();
    }
}
