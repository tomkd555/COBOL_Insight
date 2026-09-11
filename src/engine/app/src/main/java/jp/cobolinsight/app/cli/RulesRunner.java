package jp.cobolinsight.app.cli;

import jp.cobolinsight.core.json.JsonWriter;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.rules.RuleSet;

import java.util.List;

/**
 * `rules`. Bundles the builtin rules and user-defined rules and returns the catalogue as JSON.
 * The GUI treats the JSON emitted here as the source of the rule catalogue and holds no rule names
 * or descriptions on the screen side.
 */
public final class RulesRunner {

    public record Options(RuleSet ruleSet) {
    }

    public record Result(List<RuleSet.RuleEntry> rules, List<String> errors) {

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

    public static Result run(Options options) {
        return new Result(options.ruleSet().catalogue(), options.ruleSet().errors());
    }
}
