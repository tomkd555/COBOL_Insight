package jp.cobolinsight.rules.custom;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.cfg.ControlFlowGraph;
import jp.cobolinsight.core.cfg.ControlFlowGraphs;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.CompoundStatement;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.rules.cfg.CfgSupport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * A {@code match.kind = "checked-after"} custom rule: after a statement of interest, the forward
 * control flow has to reference a data item before it leaves the declared scope. This is the shape
 * of the built-in "status not checked" rules (R017, R018, R029) expressed declaratively, and it
 * walks the same control flow graph they do.
 */
final class CheckedAfterRule implements Rule {

    /** How far forward the check is looked for. */
    enum Scope {
        /** Stop at the next statement with the same verb; that statement overwrites the status. */
        UNTIL_NEXT_MATCHING_STATEMENT,
        /** Stop when control leaves the paragraph the statement sits in. */
        UNTIL_PARAGRAPH_END,
        /** Never stop; follow the flow to the end of the program. */
        UNTIL_PROGRAM_END
    }

    private final RuleMeta meta;
    private final String afterVerb;
    private final Pattern afterText;
    private final List<String> dataItems;
    private final Scope scope;
    private final boolean onEveryPath;
    private final String message;

    CheckedAfterRule(RuleMeta meta, String afterVerb, Pattern afterText, List<String> dataItems,
            Scope scope, boolean onEveryPath, String message) {
        this.meta = meta;
        this.afterVerb = StatementRule.normalize(afterVerb);
        this.afterText = afterText;
        this.dataItems = dataItems.stream().map(item -> item.toUpperCase(Locale.ROOT)).toList();
        this.scope = scope;
        this.onEveryPath = onEveryPath;
        this.message = message;
    }

    @Override
    public RuleMeta meta() {
        return meta;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext ctx) {
        ControlFlowGraphs cfgs = ctx.artifact(ControlFlowGraphs.class).orElse(null);
        if (cfgs == null) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : ctx.cobolPrograms()) {
            cfgs.of(model).ifPresent(cfg -> evaluate(model, cfg, findings));
        }
        return findings;
    }

    private void evaluate(CobolSemanticModel model, ControlFlowGraph cfg, List<Finding> findings) {
        for (CfgNode node : cfg.nodes()) {
            SimpleStatement statement = statementOf(node);
            if (statement == null || !isSubject(statement)) {
                continue;
            }
            Predicate<CfgNode> boundary = boundaryOf(node);
            boolean checked = onEveryPath
                    ? checkedOnEveryPath(cfg, node, boundary)
                    : CfgSupport.forwardHasMatch(cfg, node, boundary, this::isCheck);
            if (!checked) {
                findings.add(Finding.of(meta.id(), meta.defaultSeverity().toLevel(), message,
                        new SourcePosition(model.sourceFile(), statement.range().end().line(), 1,
                                SourcePosition.UNKNOWN_BYTE_OFFSET)));
            }
        }
    }

    private Predicate<CfgNode> boundaryOf(CfgNode start) {
        return switch (scope) {
            case UNTIL_NEXT_MATCHING_STATEMENT -> node -> {
                SimpleStatement statement = statementOf(node);
                return node != start && statement != null && hasSubjectVerb(statement);
            };
            case UNTIL_PARAGRAPH_END -> node -> !node.procedureName().equals(start.procedureName());
            case UNTIL_PROGRAM_END -> node -> false;
        };
    }

    /**
     * Whether every forward path out of {@code start} passes a check before it leaves the scope.
     * The fixpoint starts from "checked" and only ever moves nodes to "unchecked", so a cycle that
     * never checks counts as checked: an execution that never leaves the loop never reaches the
     * boundary either. Switch the seed to "unchecked" if loops should be reported instead.
     */
    private boolean checkedOnEveryPath(ControlFlowGraph cfg, CfgNode start,
            Predicate<CfgNode> boundary) {
        Map<CfgNode, Boolean> checked = new HashMap<>();
        for (CfgNode node : cfg.nodes()) {
            checked.put(node,
                    !boundary.test(node) && (isCheck(node) || !cfg.successors(node).isEmpty()));
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (CfgNode node : cfg.nodes()) {
                if (!checked.get(node) || isCheck(node)) {
                    continue;
                }
                for (CfgNode next : cfg.successors(node)) {
                    if (!checked.get(next)) {
                        checked.put(node, false);
                        changed = true;
                        break;
                    }
                }
            }
        }
        for (CfgNode next : cfg.successors(start)) {
            if (!checked.get(next)) {
                return false;
            }
        }
        return true;
    }

    private boolean isSubject(SimpleStatement statement) {
        return hasSubjectVerb(statement)
                && (afterText == null
                        || afterText.matcher(StatementRule.normalize(statement.text())).find());
    }

    private boolean hasSubjectVerb(SimpleStatement statement) {
        return afterVerb.equals(StatementRule.normalize(statement.verb()));
    }

    private static SimpleStatement statementOf(CfgNode node) {
        return node.statement().orElse(null) instanceof SimpleStatement simple ? simple : null;
    }

    /** A node checks when it branches on a condition that names one of the data items. */
    private boolean isCheck(CfgNode node) {
        if (!(node.statement().orElse(null) instanceof CompoundStatement compound)) {
            return false;
        }
        String condition = compound.conditionText().toUpperCase(Locale.ROOT);
        return dataItems.stream().anyMatch(condition::contains);
    }
}
