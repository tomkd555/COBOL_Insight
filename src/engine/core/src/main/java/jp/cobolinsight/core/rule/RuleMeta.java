package jp.cobolinsight.core.rule;

import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.source.AssetKind;

import java.util.Set;

/**
 * Everything a rule declares about itself: what it detects, why it matters, how to fix it, and what
 * the pipeline has to prepare before it can run. The engine is the single source of this text; the
 * CLI {@code rules} subcommand, the SARIF rule table and the GUI catalogue all read it from here.
 *
 * <p>Examples ({@code badExample}, {@code goodExample}) may be empty because some rules have no
 * short contrasting pair. Every other text field is required and rejected while blank, so a rule
 * without a description cannot be added.
 */
public record RuleMeta(
        String id,
        String name,
        String category,
        String summary,
        String rationale,
        String detection,
        String remedy,
        String badExample,
        String goodExample,
        Severity defaultSeverity,
        boolean defaultEnabled,
        Set<Command> commands,
        Set<AssetKind> targets,
        Set<Needs> needs) {

    public RuleMeta {
        id = requireText(id, "id");
        name = requireText(name, "name");
        category = requireText(category, "category");
        summary = requireText(summary, "summary");
        rationale = requireText(rationale, "rationale");
        detection = requireText(detection, "detection");
        remedy = requireText(remedy, "remedy");
        badExample = badExample == null ? "" : badExample.strip();
        goodExample = goodExample == null ? "" : goodExample.strip();
        if (defaultSeverity == null) {
            throw new IllegalArgumentException("RuleMeta.defaultSeverity must be set");
        }
        commands = Set.copyOf(commands);
        targets = Set.copyOf(targets);
        needs = Set.copyOf(needs);
    }

    /** Whether both halves of a contrasting example are present; one half alone is no contrast. */
    public boolean hasExample() {
        return !badExample.isEmpty() && !goodExample.isEmpty();
    }

    public boolean runsUnder(Command command) {
        return commands.contains(command);
    }

    public static Builder named(String id, String name, String category) {
        return new Builder(id, name, category);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("RuleMeta." + field + " must not be blank");
        }
        return value.strip();
    }

    /** Avoids a fourteen-argument positional call. Validation happens in {@link #build()}. */
    public static final class Builder {

        private final String id;
        private final String name;
        private final String category;
        private String summary;
        private String rationale;
        private String detection;
        private String remedy;
        private String badExample = "";
        private String goodExample = "";
        private Severity defaultSeverity = Severity.MEDIUM;
        private boolean defaultEnabled = true;
        private Set<Command> commands = Set.of();
        private Set<AssetKind> targets = Set.of();
        private Set<Needs> needs = Set.of();

        private Builder(String id, String name, String category) {
            this.id = id;
            this.name = name;
            this.category = category;
        }

        /** What the rule detects, in one sentence. */
        public Builder summary(String value) {
            this.summary = value;
            return this;
        }

        /** Why it matters: what goes wrong when it is left alone. */
        public Builder rationale(String value) {
            this.rationale = value;
            return this;
        }

        /** The detection condition, including what it deliberately leaves out. */
        public Builder detection(String value) {
            this.detection = value;
            return this;
        }

        /** How to fix it. */
        public Builder remedy(String value) {
            this.remedy = value;
            return this;
        }

        /** The offending fragment and its corrected form. */
        public Builder example(String bad, String good) {
            this.badExample = bad;
            this.goodExample = good;
            return this;
        }

        public Builder severity(Severity value) {
            this.defaultSeverity = value;
            return this;
        }

        public Builder defaultEnabled(boolean value) {
            this.defaultEnabled = value;
            return this;
        }

        public Builder commands(Command... values) {
            this.commands = Set.of(values);
            return this;
        }

        public Builder commands(Set<Command> values) {
            this.commands = values;
            return this;
        }

        public Builder targets(AssetKind... values) {
            this.targets = Set.of(values);
            return this;
        }

        public Builder targets(Set<AssetKind> values) {
            this.targets = values;
            return this;
        }

        public Builder needs(Needs... values) {
            this.needs = Set.of(values);
            return this;
        }

        public Builder needs(Set<Needs> values) {
            this.needs = values;
            return this;
        }

        public RuleMeta build() {
            return new RuleMeta(id, name, category, summary, rationale, detection, remedy,
                    badExample, goodExample, defaultSeverity, defaultEnabled, commands, targets,
                    needs);
        }
    }
}
