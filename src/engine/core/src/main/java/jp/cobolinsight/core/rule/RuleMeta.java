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
 * short contrasting pair, and so may {@code symptom}, because not every defect has a code an
 * operator would recognise. Every other text field is required and rejected while blank, so a rule
 * without a description cannot be added.
 *
 * <p>{@code category} says what goes wrong in production ({@link RuleCategory}) and
 * {@code technology} which language or middleware the rule is about; the two together are how the
 * catalogue is filtered.
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
        Set<Needs> needs,
        Technology technology,
        String symptom) {

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
        if (technology == null) {
            technology = technologyOf(targets);
        }
        symptom = symptom == null ? "" : symptom.strip();
    }

    /** A rule that states neither its technology nor a symptom: what was declared before both existed. */
    public RuleMeta(String id, String name, String category, String summary, String rationale,
            String detection, String remedy, String badExample, String goodExample,
            Severity defaultSeverity, boolean defaultEnabled, Set<Command> commands,
            Set<AssetKind> targets, Set<Needs> needs) {
        this(id, name, category, summary, rationale, detection, remedy, badExample, goodExample,
                defaultSeverity, defaultEnabled, commands, targets, needs, null, "");
    }

    /**
     * The technology a rule that names none is about, read off the asset kinds it targets. A rule
     * that targets COBOL beside another kind is taken for a COBOL rule, because every rule about a
     * middleware states its technology itself.
     */
    private static Technology technologyOf(Set<AssetKind> targets) {
        if (targets.contains(AssetKind.COBOL) || targets.contains(AssetKind.COPYBOOK)) {
            return Technology.COBOL;
        }
        if (targets.contains(AssetKind.JCL)) {
            return Technology.JCL;
        }
        if (targets.contains(AssetKind.EASYTRIEVE)) {
            return Technology.EASYTRIEVE;
        }
        if (targets.contains(AssetKind.SQL)) {
            return Technology.DB2;
        }
        if (targets.contains(AssetKind.BMS)) {
            return Technology.CICS;
        }
        if (targets.contains(AssetKind.IMS_DBD) || targets.contains(AssetKind.IMS_PSB)) {
            return Technology.IMS;
        }
        return Technology.COBOL;
    }

    /** The same rule at another severity, which is what a severity override of the rules file makes. */
    public RuleMeta withSeverity(Severity severity) {
        return new RuleMeta(id, name, category, summary, rationale, detection, remedy, badExample,
                goodExample, severity, defaultEnabled, commands, targets, needs, technology,
                symptom);
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
        private Technology technology;
        private String symptom = "";

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

        /** The language or middleware the rule is about. Left out, the targets decide it. */
        public Builder technology(Technology value) {
            this.technology = value;
            return this;
        }

        /**
         * How the defect shows itself in production, in the words of the message or code an
         * operator sees: an abend code, an SQLCODE, a JCL message. Empty when it has no such name.
         */
        public Builder symptom(String value) {
            this.symptom = value;
            return this;
        }

        public RuleMeta build() {
            return new RuleMeta(id, name, category, summary, rationale, detection, remedy,
                    badExample, goodExample, defaultSeverity, defaultEnabled, commands, targets,
                    needs, technology, symptom);
        }
    }
}
