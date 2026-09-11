package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.cfg.ControlFlowGraph;
import jp.cobolinsight.core.finding.CodeFlow;
import jp.cobolinsight.core.finding.CodeFlowStep;
import jp.cobolinsight.core.picture.PictureType;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.CompoundStatement;
import jp.cobolinsight.core.semantic.DataItem;
import jp.cobolinsight.core.semantic.EmbeddedBlock;
import jp.cobolinsight.core.semantic.Occurs;
import jp.cobolinsight.core.semantic.Procedure;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.semantic.Statement;
import jp.cobolinsight.core.semantic.StatementBlock;
import jp.cobolinsight.core.source.SourcePosition;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Traversal and search helpers shared by control-flow analysis rules. Stateless. */
public final class CfgSupport {

    private CfgSupport() {
    }

    static String upper(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT);
    }

    /** Pre-order traversal, in definition order, of statements and the nested bodies of their compound statements. */
    static void walk(List<Statement> statements, Consumer<Statement> visitor) {
        for (Statement statement : statements) {
            visitor.accept(statement);
            if (statement instanceof CompoundStatement compound) {
                for (StatementBlock block : compound.blocks()) {
                    walk(block.statements(), visitor);
                }
            }
        }
    }

    /** Returns the procedure that contains target (by instance identity, including nested statements). Null if none does. */
    static Procedure containingProcedure(CobolSemanticModel model, Statement target) {
        for (Procedure procedure : model.procedures()) {
            if (containsStatement(procedure.statements(), target)) {
                return procedure;
            }
        }
        return null;
    }

    private static boolean containsStatement(List<Statement> statements, Statement target) {
        for (Statement statement : statements) {
            if (statement == target) {
                return true;
            }
            if (statement instanceof CompoundStatement compound) {
                for (StatementBlock block : compound.blocks()) {
                    if (containsStatement(block.statements(), target)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Performs a forward BFS over successor edges starting from start. When a boundary node is
     * reached, the search stops there (its successors are not followed, and check is not
     * evaluated on it). Returns true if any other reached node satisfies check. start itself is
     * not evaluated; traversal begins from its successors.
     */
    public static boolean forwardHasMatch(ControlFlowGraph cfg, CfgNode start,
            Predicate<CfgNode> boundary, Predicate<CfgNode> check) {
        return firstMatch(cfg, start, boundary, check).isPresent();
    }

    /**
     * The first node satisfying {@code check} that a forward walk from {@code start} reaches, in
     * breadth-first order. A boundary node stops the walk there and is never checked, exactly as
     * in {@link #forwardHasMatch}; {@code start} itself is not checked either.
     */
    public static Optional<CfgNode> firstMatch(ControlFlowGraph cfg, CfgNode start,
            Predicate<CfgNode> boundary, Predicate<CfgNode> check) {
        Set<CfgNode> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<CfgNode> queue = new ArrayDeque<>();
        for (CfgNode next : cfg.successors(start)) {
            if (visited.add(next)) {
                queue.addLast(next);
            }
        }
        while (!queue.isEmpty()) {
            CfgNode node = queue.removeFirst();
            if (boundary.test(node)) {
                continue;
            }
            if (check.test(node)) {
                return Optional.of(node);
            }
            for (CfgNode next : cfg.successors(node)) {
                if (visited.add(next)) {
                    queue.addLast(next);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The first boundary node a forward walk from {@code start} reaches, in breadth-first order.
     * Empty when every path from {@code start} leaves the graph without meeting one.
     */
    static Optional<CfgNode> firstBoundary(ControlFlowGraph cfg, CfgNode start,
            Predicate<CfgNode> boundary) {
        Set<CfgNode> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<CfgNode> queue = new ArrayDeque<>();
        for (CfgNode next : cfg.successors(start)) {
            if (visited.add(next)) {
                queue.addLast(next);
            }
        }
        while (!queue.isEmpty()) {
            CfgNode node = queue.removeFirst();
            if (boundary.test(node)) {
                return Optional.of(node);
            }
            for (CfgNode next : cfg.successors(node)) {
                if (visited.add(next)) {
                    queue.addLast(next);
                }
            }
        }
        return Optional.empty();
    }

    /** The body of an EXEC CICS command: the wrapper stripped, uppercased, layout collapsed to single spaces. */
    static String cicsBody(String blockText) {
        String text = upper(blockText).replaceAll("\\s+", " ").trim();
        int exec = text.indexOf("EXEC CICS");
        return exec < 0 ? text : text.substring(exec + "EXEC CICS".length()).trim();
    }

    /**
     * The line where {@code operand} first appears in the block. A CICS command spans several
     * physical lines and the operand a finding is about is rarely on the first one; when the
     * operand appears on none, the block's first line stands in.
     */
    static int operandLine(EmbeddedBlock block, String operand) {
        Pattern pattern = Pattern.compile("(?i)\\b" + Pattern.quote(operand) + "\\s*\\(");
        List<String> lines = block.text().lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            if (pattern.matcher(lines.get(i)).find()) {
                return block.range().start().line() + i;
            }
        }
        return block.range().start().line();
    }

    /**
     * Whether the node is an {@code EXEC SQL GET DIAGNOSTICS}. That statement reads the diagnostics
     * area the statement before it filled in, so every rule that asks whether the outcome of an
     * embedded SQL statement was read counts it as the reading — the built-in R018 and the
     * declarative {@code checked-after} form alike, which is why the two ask the same function.
     *
     * <p>The node has to be an embedded SQL statement, not merely a statement whose text holds the
     * words: a {@code DISPLAY 'GET DIAGNOSTICS'} reads nothing.
     */
    public static boolean isGetDiagnostics(CfgNode node) {
        if (!(node.statement().orElse(null) instanceof SimpleStatement simple)
                || !"EXEC SQL".equals(simple.verb())) {
            return false;
        }
        String text = upper(simple.text()).replaceAll("\\s+", " ");
        int exec = text.indexOf("EXEC SQL");
        return exec >= 0
                && text.substring(exec + "EXEC SQL".length()).stripLeading()
                        .startsWith("GET DIAGNOSTICS");
    }

    /**
     * The storage byte length of a data item: from PICTURE and USAGE for an elementary item, the
     * sum over its descendants for a group. An OCCURS descendant counts its repetitions and a
     * REDEFINES descendant counts none, because it overlaps the item it redefines. Empty when any
     * descendant's PICTURE will not parse.
     */
    static Optional<Integer> byteLength(DataItem item) {
        if (item.picture().isPresent()) {
            try {
                return Optional.of(PictureType
                        .parse(item.picture().get(), item.usage().orElse(null)).byteLength());
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }
        if (item.children().isEmpty()) {
            return Optional.empty();
        }
        int total = 0;
        for (DataItem child : item.children()) {
            if (child.redefines().isPresent()) {
                continue;
            }
            Optional<Integer> childLength = byteLength(child);
            if (childLength.isEmpty()) {
                return Optional.empty();
            }
            total += childLength.get() * child.occurs().map(Occurs::maxTimes).orElse(1);
        }
        return Optional.of(total);
    }

    /** The first item of the program with this name, searched depth-first through the group items. */
    static Optional<DataItem> itemNamed(CobolSemanticModel model, String name) {
        return itemNamed(model.dataItems(), upper(name));
    }

    private static Optional<DataItem> itemNamed(List<DataItem> items, String wanted) {
        for (DataItem item : items) {
            if (upper(item.name()).equals(wanted)) {
                return Optional.of(item);
            }
            Optional<DataItem> nested = itemNamed(item.children(), wanted);
            if (nested.isPresent()) {
                return nested;
            }
        }
        return Optional.empty();
    }

    /** A whole-word match of a COBOL data name, so WS-RESP2 is not a reference to WS-RESP. */
    public static Pattern wordPattern(String name) {
        return Pattern.compile("(?i)(?<![\\p{L}\\p{N}$#_-])" + Pattern.quote(name)
                + "(?![\\p{L}\\p{N}$#_-])");
    }

    /**
     * The text a statement puts a data name in: the statement itself for a simple one, the
     * condition and the branch labels for a compound one.
     */
    static String referenceText(Statement statement) {
        if (statement instanceof SimpleStatement simple) {
            return simple.text();
        }
        if (statement instanceof CompoundStatement compound) {
            StringBuilder text = new StringBuilder(compound.conditionText());
            for (StatementBlock block : compound.blocks()) {
                text.append(' ').append(block.label());
            }
            return text.toString();
        }
        return "";
    }

    /** A code flow of one step per position, so a finding can point at the places it is about. */
    static CodeFlow flow(CodeFlowStep... steps) {
        return new CodeFlow(List.of(steps));
    }

    public static CodeFlowStep step(String file, int line, String message) {
        return new CodeFlowStep(new SourcePosition(file, line, 1,
                SourcePosition.UNKNOWN_BYTE_OFFSET), message);
    }
}
