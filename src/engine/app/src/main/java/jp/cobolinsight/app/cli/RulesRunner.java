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
 * `rules`. Bundles the builtin rules and user-defined rules and returns a list with descriptions.
 * The GUI treats the JSON emitted here as the source of the rule catalogue and holds no rule names
 * or descriptions on the screen side.
 */
public final class RulesRunner {

    public record Options(RuleSet ruleSet, String ruleId) {
    }

    /** Whether detail was narrowed down to a single entry. Switches the terminal-oriented formatting here. */
    public record Result(List<RuleSet.RuleEntry> rules, List<String> errors, boolean detail) {

        public Result {
            rules = List.copyOf(rules);
            errors = List.copyOf(errors);
        }

        /** The format the GUI reads. Rules are in ascending id order and carry every description field. */
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

        /** The terminal-oriented listing. Shows the full description text when narrowed to a single entry. */
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

    /** When ruleId is specified, returns only that one entry (empty if not found). */
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
