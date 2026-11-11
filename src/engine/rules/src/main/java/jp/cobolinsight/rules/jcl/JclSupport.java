package jp.cobolinsight.rules.jcl;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.jcl.JclDataset;
import jp.cobolinsight.core.jcl.JclDdStatement;
import jp.cobolinsight.core.jcl.JclDisposition;
import jp.cobolinsight.core.jcl.JclExecKind;
import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.jcl.JclStep;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.source.SourcePosition;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * What the JCL rules share: how a DD's data set and disposition are read, and how a finding about
 * a statement a PROC brought in is kept to one report.
 */
public final class JclSupport {

    private JclSupport() {
    }

    /** The steps that run a program, which are the ones a rule about what a step does looks at. */
    public static List<JclStep> programSteps(JclJobModel job) {
        return job.steps().stream().filter(step -> step.execKind() == JclExecKind.PGM).toList();
    }

    /**
     * The programs a step runs, upper case: its EXEC target, and the programs its cards or its
     * PARM name — the application under a TSO launcher or an IMS region controller.
     */
    public static List<String> programsOf(JclStep step) {
        List<String> programs = new java.util.ArrayList<>();
        if (step.execKind() == JclExecKind.PGM) {
            programs.add(upper(step.target()));
        }
        step.utility().ifPresent(facts ->
                facts.programRuns().forEach(run -> programs.add(upper(run.program()))));
        return programs;
    }

    /** The in-stream records of a DD and of everything concatenated under it, in order. */
    public static List<String> cards(JclStep step, String ddName) {
        List<String> cards = new java.util.ArrayList<>();
        for (JclDdStatement dd : step.ddStatements()) {
            if (dd.ddName().equalsIgnoreCase(ddName)) {
                cards.addAll(dd.inStreamData());
            }
        }
        return cards;
    }

    public static String upper(String text) {
        return text == null ? "" : text.toUpperCase(Locale.ROOT);
    }

    /**
     * The data set a DD names, upper case, when it is one a rule can follow from step to step: not
     * a DUMMY, not a referback nothing resolved, and not a name with a symbol or a scheduler
     * variable left in it, which is a different data set on every run.
     */
    public static Optional<JclDataset> dataset(JclDdStatement dd) {
        if (dd.dummy()) {
            return Optional.empty();
        }
        return dd.dataset().filter(dataset -> !dataset.name().startsWith("*.")
                && !hasVariable(dataset.name()));
    }

    /** Whether a name still carries a JCL symbol or a scheduler variable. */
    public static boolean hasVariable(String name) {
        String body = name.startsWith("&&") ? name.substring(2) : name;
        return body.contains("&") || body.contains("%%") || body.contains("$");
    }

    /** The DISP status, {@code NEW} where the DD writes none; empty for a DD with no data set. */
    public static String status(JclDdStatement dd) {
        return dd.disposition().map(JclDisposition::status).map(JclSupport::upper)
                .orElse(JclDisposition.DEFAULT_STATUS);
    }

    /** The normal-termination disposition as written, or the empty string. */
    public static String normal(JclDdStatement dd) {
        return dd.disposition().flatMap(JclDisposition::normal).map(JclSupport::upper).orElse("");
    }

    /** The abnormal-termination disposition as written, or the empty string. */
    public static String abnormal(JclDdStatement dd) {
        return dd.disposition().flatMap(JclDisposition::abnormal).map(JclSupport::upper)
                .orElse("");
    }

    /** Whether the DD has the keyword, whatever its value. */
    public static boolean has(JclDdStatement dd, String keyword) {
        return dd.parameters().keySet().stream().anyMatch(key -> key.equalsIgnoreCase(keyword));
    }

    /** The value of a keyword of the DD, or the empty string. */
    public static String parameter(JclDdStatement dd, String keyword) {
        return dd.parameters().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(keyword))
                .map(entry -> entry.getValue()).findFirst().orElse("");
    }

    /** The value of a keyword of the EXEC statement, a qualified one ({@code TIME.STEP1}) included. */
    public static Optional<String> parameter(JclStep step, String keyword) {
        return step.parameters().entrySet().stream()
                .filter(entry -> {
                    String key = upper(entry.getKey());
                    return key.equals(keyword) || key.startsWith(keyword + ".");
                })
                .map(entry -> entry.getValue()).findFirst();
    }

    public static Optional<JclDdStatement> dd(JclStep step, String ddName) {
        return step.ddStatements().stream()
                .filter(dd -> dd.ddName().equalsIgnoreCase(ddName)).findFirst();
    }

    public static boolean hasDd(JclStep step, String ddName) {
        return dd(step, ddName).isPresent();
    }

    /**
     * Collects findings, each place once. A statement expanded from a PROC keeps its line in the
     * PROC member, so two jobs calling the PROC would otherwise report it twice.
     */
    public static final class Findings {

        private final RuleMeta meta;
        private final List<Finding> findings;
        private final Set<String> reportedAt = new HashSet<>();

        public Findings(RuleMeta meta, List<Finding> findings) {
            this.meta = meta;
            this.findings = findings;
        }

        public void add(SourcePosition position, String message) {
            add(position, "", message);
        }

        /** {@code key} tells apart two findings about one line, such as two data sets of one DD. */
        public void add(SourcePosition position, String key, String message) {
            if (reportedAt.add(position.file() + ":" + position.line() + ":" + key)) {
                findings.add(Finding.of(meta.id(), meta.defaultSeverity().toLevel(), message,
                        position));
            }
        }
    }
}
