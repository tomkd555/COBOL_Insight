package jp.cobolinsight.rules;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.core.spi.FixProducer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The rules a run may use: the built-ins plus whatever {@code rules.json} adds, with the file's
 * enable and severity overrides already applied. Every command asks this object for its rules, so
 * the filtering happens once and cannot drift between subcommands.
 */
public final class RuleSet {

    /** Where a rule came from. */
    public enum Source {
        BUILTIN,
        CUSTOM
    }

    /** One row of the catalogue: the rule as configured, next to what it declared by default. */
    public record RuleEntry(Rule rule, boolean enabled, Source source, Severity severity) {

        public RuleMeta meta() {
            return rule.meta();
        }

        public String id() {
            return rule.meta().id();
        }

        public boolean hasFix() {
            return rule.fix().isPresent();
        }
    }

    private final List<RuleEntry> catalogue;
    private final List<String> errors;

    private RuleSet(List<RuleEntry> catalogue, List<String> errors) {
        this.catalogue = List.copyOf(catalogue);
        this.errors = List.copyOf(errors);
    }

    /** Loads the built-ins plus the file at {@code path}. A missing file means the defaults. */
    public static RuleSet load(Path path) {
        return load(RulesFile.load(path));
    }

    public static RuleSet load(RulesFile file) {
        List<RuleEntry> catalogue = new ArrayList<>();
        List<String> errors = new ArrayList<>(file.errors());
        Set<String> ids = new HashSet<>();
        for (Rule rule : BuiltinRules.all()) {
            add(catalogue, ids, rule, Source.BUILTIN, file);
        }
        for (Rule rule : file.custom()) {
            if (ids.contains(rule.meta().id())) {
                errors.add("利用者定義ルールの ID が組み込みルールと重なっている: "
                        + rule.meta().id());
                continue;
            }
            add(catalogue, ids, rule, Source.CUSTOM, file);
        }
        for (String id : file.overrides().keySet()) {
            if (ids.contains(id)) {
                continue;
            }
            errors.add(RemovedRules.contains(id)
                    ? "rule " + id + " was removed in V2"
                    : "設定にあるルールIDがカタログに無い: " + id);
        }
        catalogue.sort(Comparator.comparing(RuleEntry::id));
        return new RuleSet(catalogue, errors);
    }

    private static void add(List<RuleEntry> catalogue, Set<String> ids, Rule rule, Source source,
            RulesFile file) {
        RuleMeta meta = rule.meta();
        RulesFile.RuleOverride override = file.overrides().get(meta.id());
        boolean enabled = meta.defaultEnabled();
        Severity severity = meta.defaultSeverity();
        if (override != null) {
            if (override.enabled() != null) {
                enabled = override.enabled();
            }
            if (override.severity() != null) {
                severity = override.severity();
            }
        }
        ids.add(meta.id());
        catalogue.add(new RuleEntry(
                severity == meta.defaultSeverity() ? rule : new Releveled(rule, severity),
                enabled, source, severity));
    }

    /** The only way a command obtains rules. FIX additionally requires a fix producer. */
    public List<Rule> forCommand(Command command) {
        return catalogue.stream()
                .filter(RuleEntry::enabled)
                .filter(entry -> entry.meta().runsUnder(command))
                .filter(entry -> command != Command.FIX || entry.hasFix())
                .map(RuleEntry::rule)
                .toList();
    }

    /** Every rule, enabled or not, in id order. */
    public List<RuleEntry> catalogue() {
        return catalogue;
    }

    /** What the pipeline has to prepare before {@code command} can evaluate its rules. */
    public Set<Needs> needs(Command command) {
        Set<Needs> needs = EnumSet.noneOf(Needs.class);
        for (Rule rule : forCommand(command)) {
            needs.addAll(rule.meta().needs());
        }
        return needs;
    }

    /** Problems found in {@code rules.json}. None of them stops the run. */
    public List<String> errors() {
        return errors;
    }

    /**
     * A rule whose severity the configuration changed. Rules build their findings from their own
     * default severity, so the override has to be applied to the findings as well as to the
     * catalogue; otherwise the setting would show in the GUI and do nothing.
     */
    private record Releveled(Rule delegate, Severity severity) implements Rule {

        @Override
        public RuleMeta meta() {
            RuleMeta original = delegate.meta();
            return new RuleMeta(original.id(), original.name(), original.category(),
                    original.summary(), original.rationale(), original.detection(),
                    original.remedy(), original.badExample(), original.goodExample(), severity,
                    original.defaultEnabled(), original.commands(), original.targets(),
                    original.needs());
        }

        @Override
        public List<Finding> evaluate(AnalysisContext ctx) {
            return delegate.evaluate(ctx).stream()
                    .map(finding -> new Finding(finding.ruleId(), severity.toLevel(),
                            finding.message(), finding.location(), finding.codeFlows(),
                            finding.fixes()))
                    .toList();
        }

        @Override
        public Optional<FixProducer> fix() {
            return delegate.fix();
        }
    }
}
