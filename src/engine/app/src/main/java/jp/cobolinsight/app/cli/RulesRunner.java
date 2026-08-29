package jp.cobolinsight.app.cli;

import jp.cobolinsight.core.json.JsonWriter;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.rules.RuleSet;

import java.util.List;
import java.util.Locale;

/**
 * `rules`。組み込みルールと利用者定義ルールを束ね、説明つきの一覧を返す。GUI はここが出す JSON を
 * ルールカタログの供給源とし、画面側でルール名や説明を持たない。
 */
public final class RulesRunner {

    public record Options(RuleSet ruleSet, String ruleId) {
    }

    /** detail は1件へ絞り込んだかどうか。端末向けの整形をここで切り替える。 */
    public record Result(List<RuleSet.RuleEntry> rules, List<String> errors, boolean detail) {

        public Result {
            rules = List.copyOf(rules);
            errors = List.copyOf(errors);
        }

        /** GUI が読む形式。ルールは id 昇順で、説明の全項目を持つ。 */
        public String toJson() {
            JsonWriter writer = new JsonWriter();
            writer.beginObject()
                    .name("ruleCount").value(rules.size())
                    .name("rules").beginArray();
            for (RuleSet.RuleEntry entry : rules) {
                RuleMeta meta = entry.meta();
                writer.beginObject()
                        .name("id").value(meta.id())
                        .name("name").value(meta.name())
                        .name("category").value(meta.category())
                        .name("severity").value(entry.severity().name())
                        .name("defaultSeverity").value(meta.defaultSeverity().name())
                        .name("hasFix").value(entry.hasFix())
                        .name("source").value(sourceOf(entry))
                        .name("enabled").value(entry.enabled())
                        .name("defaultEnabled").value(meta.defaultEnabled())
                        .name("summary").value(meta.summary())
                        .name("rationale").value(meta.rationale())
                        .name("detection").value(meta.detection())
                        .name("remedy").value(meta.remedy())
                        .name("badExample").value(meta.badExample())
                        .name("goodExample").value(meta.goodExample());
                writeNames(writer, "commands", meta.commands().stream().map(Command::name)
                        .sorted().toList());
                writeNames(writer, "targets", meta.targets().stream().map(AssetKind::name)
                        .sorted().toList());
                writeNames(writer, "needs", meta.needs().stream().map(Needs::name)
                        .sorted().toList());
                writer.endObject();
            }
            writer.endArray().name("ruleErrors").beginArray();
            for (String error : errors) {
                writer.value(error);
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
            for (RuleSet.RuleEntry entry : rules) {
                out.append(String.format(Locale.ROOT, "%-6s %-4s %-14s %s%n",
                        entry.id(), entry.severity().label(), entry.meta().category(),
                        entry.meta().name()));
            }
            long customCount = rules.stream()
                    .filter(entry -> entry.source() == RuleSet.Source.CUSTOM).count();
            out.append(String.format(Locale.ROOT, "%n合計 %d 件(組み込み %d・利用者定義 %d)",
                    rules.size(), rules.size() - customCount, customCount));
            long disabledCount = rules.stream().filter(entry -> !entry.enabled()).count();
            if (disabledCount > 0) {
                out.append(String.format(Locale.ROOT, " うち無効 %d 件", disabledCount));
            }
            out.append(System.lineSeparator());
            for (String error : errors) {
                out.append("警告: ルール設定: ").append(error).append('\n');
            }
            return out.toString();
        }

        private static String detailTextOf(RuleSet.RuleEntry entry) {
            RuleMeta meta = entry.meta();
            StringBuilder out = new StringBuilder();
            out.append(meta.id()).append(' ').append(meta.name()).append('\n')
                    .append("カテゴリ: ").append(meta.category())
                    .append(" / 重大度: ").append(entry.severity().label())
                    .append(" / 対象コマンド: ").append(meta.commands().stream()
                            .map(Command::name).sorted().reduce((a, b) -> a + "・" + b).orElse(""))
                    .append(" / 修正案: ").append(entry.hasFix() ? "あり" : "なし")
                    .append("\n\n");
            appendSection(out, "何を検出するか", meta.summary());
            appendSection(out, "なぜ問題か", meta.rationale());
            appendSection(out, "検出条件", meta.detection());
            appendSection(out, "どう直すか", meta.remedy());
            if (meta.hasExample()) {
                appendSection(out, "該当する例", meta.badExample());
                appendSection(out, "直した例", meta.goodExample());
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

        private static void writeNames(JsonWriter writer, String name, List<String> values) {
            writer.name(name).beginArray();
            for (String value : values) {
                writer.value(value);
            }
            writer.endArray();
        }

        private static String sourceOf(RuleSet.RuleEntry entry) {
            return entry.source() == RuleSet.Source.CUSTOM ? "user" : "builtin";
        }
    }

    private RulesRunner() {
    }

    /** ruleId を指定した場合、その1件だけを返す(見つからなければ空)。 */
    public static Result run(Options options) {
        List<RuleSet.RuleEntry> all = options.ruleSet().catalogue();
        String wanted = options.ruleId();
        boolean detail = wanted != null && !wanted.isBlank();
        List<RuleSet.RuleEntry> selected = detail
                ? all.stream().filter(entry -> entry.id().equalsIgnoreCase(wanted.strip())).toList()
                : all;
        return new Result(selected, options.ruleSet().errors(), detail);
    }
}
