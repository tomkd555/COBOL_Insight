package jp.cobolinsight.rules.custom;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.CompoundStatement;
import jp.cobolinsight.core.semantic.Procedure;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.semantic.Statement;
import jp.cobolinsight.core.semantic.StatementBlock;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.AnalysisContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A {@code match.kind = "statement"} custom rule: statements of the named verbs that lack a clause.
 * With no {@code missingClause} every statement of those verbs is reported, which is how an author
 * bans a verb outright.
 */
final class StatementRule implements Rule {

    private final RuleMeta meta;
    private final List<String> verbs;
    private final List<String> missingClauses;
    private final Pattern inParagraph;
    private final String message;

    StatementRule(RuleMeta meta, List<String> verbs, List<String> missingClauses,
            Pattern inParagraph, String message) {
        this.meta = meta;
        this.verbs = List.copyOf(verbs);
        this.missingClauses = List.copyOf(missingClauses);
        this.inParagraph = inParagraph;
        this.message = message;
    }

    @Override
    public RuleMeta meta() {
        return meta;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext ctx) {
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : ctx.cobolPrograms()) {
            for (Procedure procedure : model.procedures()) {
                if (inParagraph != null && !inParagraph.matcher(procedure.name()).find()) {
                    continue;
                }
                collect(model, procedure.statements(), findings);
            }
        }
        return findings;
    }

    private void collect(CobolSemanticModel model, List<Statement> statements,
            List<Finding> findings) {
        for (Statement statement : statements) {
            if (statement instanceof CompoundStatement compound) {
                for (StatementBlock block : compound.blocks()) {
                    collect(model, block.statements(), findings);
                }
                continue;
            }
            if (!(statement instanceof SimpleStatement simple) || !matchesVerb(simple.verb())) {
                continue;
            }
            String text = normalize(simple.text());
            if (missingClauses.stream().anyMatch(text::contains)) {
                continue;
            }
            findings.add(Finding.of(meta.id(), meta.defaultSeverity().toLevel(), message,
                    new SourcePosition(model.sourceFile(), simple.range().start().line(), 1,
                            SourcePosition.UNKNOWN_BYTE_OFFSET)));
        }
    }

    private boolean matchesVerb(String verb) {
        String upper = normalize(verb);
        for (String candidate : verbs) {
            if (candidate.equals(upper)) {
                return true;
            }
        }
        return false;
    }

    /** Upper case with runs of whitespace collapsed, so a clause spanning two lines still matches. */
    static String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
    }
}
