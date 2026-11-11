package jp.cobolinsight.rules.cobol;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.cfg.ControlFlowGraph;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.CompoundStatement;
import jp.cobolinsight.core.semantic.ConditionName;
import jp.cobolinsight.core.semantic.DataItem;
import jp.cobolinsight.core.semantic.Procedure;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.semantic.Statement;
import jp.cobolinsight.rules.cfg.CfgSupport;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the rules about a middleware's call interface share: a CALL statement read into the entry
 * point it names and the data items it passes, and the question every such rule asks — whether the
 * program looks at what the call handed back before it makes the next one.
 *
 * <p>DL/I and the MQI are both reached by an ordinary COBOL CALL whose outcome comes back in one
 * of the operands, so nothing in the language ties the call to its check; the tie is the position
 * of the operand, which is what these helpers read.
 */
public final class ServiceCalls {

    /** One CALL: the entry point, upper case, and the data names after USING, in order. */
    public record Call(SimpleStatement statement, String target, List<String> operands) {

        /** The operand counted from the end: 1 is the last. Empty when there are too few. */
        public Optional<String> fromEnd(int position) {
            int index = operands.size() - position;
            return index >= 0 ? Optional.of(operands.get(index)) : Optional.empty();
        }
    }

    private static final Pattern CALL = Pattern.compile(
            "^CALL\\s+(?:'([^']+)'|\"([^\"]+)\"|([\\p{L}\\p{N}_-]+))(?:\\s+USING\\s+(.*))?$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** The words of a USING phrase that are not data names. */
    private static final Set<String> USING_NOISE = Set.of("BY", "REFERENCE", "CONTENT", "VALUE",
            "ADDRESS", "LENGTH", "OMITTED");

    /** The words that end the USING phrase. */
    private static final Set<String> USING_END = Set.of("ON", "NOT", "END-CALL", "RETURNING",
            "GIVING", "EXCEPTION", "OVERFLOW");

    private ServiceCalls() {
    }

    /**
     * The CALL a statement is, or empty for any other statement. A target written as a data name
     * is resolved through the VALUE clause of that item, which is how a program that keeps its
     * entry point names in WORKING-STORAGE writes the same call.
     */
    public static Optional<Call> of(CobolSemanticModel model, Statement statement) {
        if (!(statement instanceof SimpleStatement simple)
                || !"CALL".equalsIgnoreCase(simple.verb())) {
            return Optional.empty();
        }
        Matcher call = CALL.matcher(simple.text().strip().replaceAll("\\.\\s*$", ""));
        if (!call.matches()) {
            return Optional.empty();
        }
        String target = call.group(1) != null ? call.group(1) : call.group(2);
        if (target == null) {
            target = CfgSupport.itemNamed(model, call.group(3))
                    .flatMap(DataItem::value).map(ServiceCalls::unquoted).orElse(call.group(3));
        }
        List<String> operands = new ArrayList<>();
        if (call.group(4) != null) {
            String[] words = call.group(4).replace(",", " ").strip().split("\\s+");
            for (int i = 0; i < words.length; i++) {
                String word = words[i].toUpperCase(Locale.ROOT);
                if (USING_END.contains(word)) {
                    break;
                }
                if (word.equals("OF") || word.equals("IN")) {
                    // A qualifier: the name before it is the operand, the one after its group.
                    i++;
                    continue;
                }
                if (USING_NOISE.contains(word) || word.isEmpty()) {
                    continue;
                }
                int subscript = word.indexOf('(');
                operands.add(subscript > 0 ? word.substring(0, subscript) : word);
            }
        }
        return Optional.of(new Call(simple, target.strip().toUpperCase(Locale.ROOT), operands));
    }

    /** Every CALL of the program, in source order. */
    public static List<Call> all(CobolSemanticModel model) {
        List<Call> calls = new ArrayList<>();
        for (Procedure procedure : model.procedures()) {
            CfgSupport.walk(procedure.statements(), statement ->
                    of(model, statement).ifPresent(calls::add));
        }
        return calls;
    }

    private static String unquoted(String value) {
        String text = value.strip();
        return text.length() >= 2 && (text.startsWith("'") || text.startsWith("\""))
                ? text.substring(1, text.length() - 1) : text;
    }

    /** The name of an item and of every condition name declared on it, upper case. */
    public static Set<String> namesOf(DataItem item) {
        Set<String> names = new LinkedHashSet<>();
        names.add(item.name().toUpperCase(Locale.ROOT));
        for (ConditionName condition : item.conditionNames()) {
            names.add(condition.name().toUpperCase(Locale.ROOT));
        }
        return names;
    }

    /**
     * Whether, on some path forward from {@code start} and before {@code boundary} is met, the
     * program reads one of {@code names}: in the condition of an IF, EVALUATE or PERFORM UNTIL, or
     * by moving it to another item, which is how a program hands a status to a common checking
     * paragraph. A condition name of the item counts as the item.
     */
    public static boolean readBeforeNext(ControlFlowGraph cfg, CfgNode start,
            Predicate<CfgNode> boundary, Set<String> names) {
        List<Pattern> patterns = names.stream().map(CfgSupport::wordPattern).toList();
        return CfgSupport.forwardHasMatch(cfg, start, boundary, node -> {
            Statement statement = node.statement().orElse(null);
            String text;
            if (statement instanceof CompoundStatement) {
                text = CfgSupport.referenceText(statement);
            } else if (statement instanceof SimpleStatement simple
                    && "MOVE".equalsIgnoreCase(simple.verb())) {
                text = simple.text();
            } else {
                return false;
            }
            return patterns.stream().anyMatch(pattern -> pattern.matcher(text).find());
        });
    }

    /** Every statement and condition of the program as one upper-case text, a blank between them. */
    public static String programText(CobolSemanticModel model) {
        StringBuilder text = new StringBuilder();
        for (Procedure procedure : model.procedures()) {
            CfgSupport.walk(procedure.statements(), statement ->
                    text.append(CfgSupport.referenceText(statement)).append(' '));
        }
        return text.toString().toUpperCase(Locale.ROOT);
    }

    /** The VALUE literals of every data item of the program, apostrophes off, upper case. */
    public static Set<String> literalValues(CobolSemanticModel model) {
        Set<String> values = new LinkedHashSet<>();
        collectValues(model.dataItems(), values);
        return values;
    }

    private static void collectValues(List<DataItem> items, Set<String> values) {
        for (DataItem item : items) {
            item.value().ifPresent(value ->
                    values.add(unquoted(value).strip().toUpperCase(Locale.ROOT)));
            collectValues(item.children(), values);
        }
    }

    /** Whether the program names {@code word} anywhere in its statements, as a whole word. */
    public static boolean mentions(String programText, String word) {
        return CfgSupport.wordPattern(word).matcher(programText).find();
    }
}
