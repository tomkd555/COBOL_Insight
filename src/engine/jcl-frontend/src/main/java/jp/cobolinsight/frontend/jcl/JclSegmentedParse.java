package jp.cobolinsight.frontend.jcl;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;

import jp.cobolinsight.frontend.jcl.JclStatements.Kind;
import jp.cobolinsight.frontend.jcl.JclStatements.Operand;
import jp.cobolinsight.frontend.jcl.JclStatements.Statement;
import jp.cobolinsight.frontend.jcl.gen.JCLLexer;
import jp.cobolinsight.frontend.jcl.gen.JCLParser;

/**
 * Reads a JCL source one statement at a time.
 *
 * <p>The grammar reads a whole file in one rule, so an unreadable statement can make an enclosing
 * loop give up and take the statements around it with it. Here each statement is handed to the
 * same grammar on its own, inside the smallest wrapper that makes the grammar accept it — a DD
 * needs a PROC and an EXEC above it, a JCLLIB needs a JOB card — and only the statement's own
 * subtree is walked into the model, so the wrapper leaves no trace. A statement the grammar
 * cannot read costs that statement and nothing else.
 *
 * <p>A JOB, EXEC or DD statement the grammar refuses is tried again with the parameter the error
 * stands on put aside: its value is replaced by a stand-in of the same length, and if that is not
 * enough the parameter is dropped. The statement then enters the model with everything else it
 * carries, and each parameter put aside is reported on its own.
 *
 * <p>The wrapper's own lines shift the line numbers the parser reports, which
 * {@link JclModelListener#lineOffset(int)} puts back.
 */
final class JclSegmentedParse {

    /** One statement the parser would not read, at the line and column it stands on. */
    record Unread(int line, int column, String message) {
    }

    /**
     * One parameter of a statement whose value the grammar would not read. {@code removed} says
     * the parameter was taken out of the statement rather than stood in for, which is what happens
     * when the keyword itself is one no rule of the grammar knows.
     */
    record Salvaged(int line, String keyword, boolean removed) {
    }

    /** What one segmented read produced. */
    record Outcome(List<Unread> unread, List<Salvaged> salvaged) {
    }

    /** The wrapper that makes the grammar accept one statement, and what to walk out of it. */
    private record Wrapper(String before, String after,
            Class<? extends ParserRuleContext> target) {

        int linesBefore() {
            return before.isEmpty() ? 0 : before.split("\n", -1).length - 1;
        }
    }

    private static final String IN_STEP = "//ZZWRAP  PROC\n//ZZSTEP   EXEC PGM=ZZ\n";
    private static final String IN_JOB = "//ZZJOB    JOB\n";

    /** The grammar reads a CNTL group whole, so each of its two cards is given the other. */
    private static final String OPEN_CNTL = "//ZZCNTL   CNTL\n";
    private static final String CLOSE_CNTL = "\n//         ENDCNTL\n";

    /** What the grammar was told when it read the statement as something else. */
    private static final String NO_KIND = "文の種別を決められません";

    /** How many parameters of one statement are put aside before the statement is given up on. */
    private static final int MAX_SALVAGED_PARAMETERS = 8;

    /** The statements worth salvaging a parameter of: the three the model is built from. */
    private static final Set<Kind> SALVAGEABLE = Set.of(Kind.JOB, Kind.EXEC, Kind.DD);

    /** What each statement kind is wrapped in. A kind with no entry contributes no model. */
    private static final Map<Kind, Wrapper> WRAPPERS = Map.ofEntries(
            Map.entry(Kind.JOB, new Wrapper("", "", JCLParser.JobCardContext.class)),
            Map.entry(Kind.EXEC,
                    new Wrapper("//ZZWRAP  PROC\n", "", JCLParser.JclStepContext.class)),
            Map.entry(Kind.PROC, new Wrapper("", "\n//ZZSTEP   EXEC PGM=ZZ\n",
                    JCLParser.ProcStatementContext.class)),
            Map.entry(Kind.SET, new Wrapper("", "", JCLParser.SetStatementContext.class)),
            Map.entry(Kind.INCLUDE, new Wrapper("", "", JCLParser.IncludeStatementContext.class)),
            Map.entry(Kind.IF, new Wrapper("", "", JCLParser.IfStatementContext.class)),
            Map.entry(Kind.ELSE, new Wrapper("", "", JCLParser.ElseStatementContext.class)),
            Map.entry(Kind.ENDIF, new Wrapper("", "", JCLParser.EndifStatementContext.class)),
            Map.entry(Kind.OUTPUT, new Wrapper("", "", JCLParser.OutputStatementContext.class)),
            Map.entry(Kind.JCLLIB, new Wrapper(IN_JOB, "", JCLParser.JcllibStatementContext.class)),
            // An XMIT statement transmits its payload to another node; the model keeps neither it
            // nor the payload, but a card the grammar refuses is worth reporting.
            Map.entry(Kind.XMIT, new Wrapper(IN_JOB, "", null)),
            // A job group is read whole, from JOBGROUP to ENDGROUP, and carries no model either.
            Map.entry(Kind.GROUP, new Wrapper("", "", null)),
            // A JECL card carries nothing the model wants, but a card the grammar refuses is worth
            // reporting: it is why the whole-file parse gave up.
            Map.entry(Kind.JECL, new Wrapper(IN_JOB, "", null)),
            // The two cards of a CNTL group hold the control statements a printer or a subsystem
            // reads, which the model keeps no row for; a card the grammar refuses is reported all
            // the same, so each of the two is wrapped in the other.
            Map.entry(Kind.CNTL, new Wrapper(IN_JOB, CLOSE_CNTL, null)),
            Map.entry(Kind.ENDCNTL, new Wrapper(IN_JOB + OPEN_CNTL, "", null)),
            Map.entry(Kind.OTHER, new Wrapper("", "", null)));

    /** A DD with a name of its own, and one written under it as part of a concatenation. */
    private static final Wrapper NAMED_DD =
            new Wrapper(IN_STEP, "", JCLParser.DdStatementContext.class);
    private static final Wrapper CONCATENATED_DD = new Wrapper(
            IN_STEP + "//ZZDD     DD   DUMMY\n", "",
            JCLParser.DdStatementConcatenationContext.class);

    /** JOBLIB and the DD statements concatenated to it: a rule of the job, not of a step. */
    private static final Wrapper JOBLIB_DD =
            new Wrapper(IN_JOB, "", JCLParser.JoblibStatementContext.class);
    private static final Wrapper JOBLIB_CONCATENATION = new Wrapper(
            IN_JOB + "//JOBLIB   DD   DUMMY\n", "", JCLParser.JoblibConcatenationContext.class);

    /** SYSCHK: the job's own checkpoint data set, which has a rule of its own as well. */
    private static final Wrapper SYSCHK_DD =
            new Wrapper(IN_JOB, "", JCLParser.SyschkStatementContext.class);

    private JclSegmentedParse() {
    }

    /**
     * Walks every statement into {@code listener} in source order, and returns the statements the
     * grammar would not read together with the parameters it had to put aside. {@code source} is
     * the whole of the text the statements were read from, which is the names a stand-in has to
     * keep clear of.
     */
    static Outcome parse(List<Statement> statements, String source, String path,
            JclModelListener listener, SchedulerMarkers markers) {
        List<Unread> unread = new ArrayList<>();
        List<Salvaged> salvaged = new ArrayList<>();
        for (Statement statement : statements) {
            if (statement.kind() == Kind.PEND) {
                listener.pend();
                continue;
            }
            Wrapper wrapper = wrapperOf(statement);
            if (wrapper == null) {
                continue;
            }
            int offset = statement.line() - wrapper.linesBefore() - 1;
            Fragment fragment = parseFragment(
                    wrapper.before() + statement.text() + wrapper.after(), path);
            if (fragment.unread() != null) {
                Salvage rescued =
                        salvage(statement, wrapper, source, path, markers, fragment.unread());
                if (rescued == null) {
                    // One statement, one diagnostic: the line it starts on is where a reader looks,
                    // and the cross-check counts the statement as reported by that line.
                    unread.add(new Unread(statement.line(), 1, fragment.unread().message()));
                    close(statement.kind(), listener);
                    continue;
                }
                fragment = rescued.fragment();
                for (Salvaged one : rescued.parameters()) {
                    salvaged.add(new Salvaged(statement.line(), one.keyword(), one.removed()));
                }
            }
            if (wrapper.target() == null) {
                continue;
            }
            ParserRuleContext target =
                    find(fragment.tree(), wrapper.target(), wrapper.linesBefore() + 1);
            if (target == null) {
                unread.add(new Unread(statement.line(), 1, NO_KIND));
                close(statement.kind(), listener);
                continue;
            }
            listener.lineOffset(offset);
            ParseTreeWalker.DEFAULT.walk(listener, target);
            listener.lineOffset(0);
        }
        return new Outcome(unread, salvaged);
    }

    /**
     * Closes what the statement would have closed had it been readable, so the DD statements
     * written under an unreadable EXEC land nowhere rather than under the step before it; each of
     * them is then reported on its own.
     */
    private static void close(Kind kind, JclModelListener listener) {
        switch (kind) {
            case JOB -> {
                listener.closeOpenStep();
                listener.closeOpenProc();
                listener.closeJob();
            }
            case PROC -> {
                listener.closeOpenStep();
                listener.closeOpenProc();
            }
            case EXEC -> listener.unreadableStep();
            // A DD nobody could read carries nothing for a nameless DD below it to be concatenated
            // to, so the listener is told to forget the DD name it was carrying.
            case DD -> listener.ddNotRead();
            // An IF nobody could read still opens a nest the ELSE and the ENDIF below it close, so
            // the listener is told to keep its place rather than let them close the IF around it.
            case IF -> listener.unreadableIf();
            default -> {
            }
        }
    }

    private static Wrapper wrapperOf(Statement statement) {
        if (statement.kind() == Kind.DD) {
            if (statement.isJoblib()) {
                return statement.name().isEmpty() ? JOBLIB_CONCATENATION : JOBLIB_DD;
            }
            if (statement.isSyschk()) {
                return SYSCHK_DD;
            }
            if (statement.isSyschkConcatenation()) {
                // The model keeps one checkpoint data set, so a second concatenated to it has
                // nowhere to go; reading it would only cost a diagnostic for a statement the
                // cross-check does not ask for either.
                return null;
            }
            return statement.name().isEmpty() ? CONCATENATED_DD : NAMED_DD;
        }
        if (statement.isDelimiter()) {
            return null;
        }
        if (!statement.isJclStatement() && statement.kind() != Kind.JECL) {
            return null;
        }
        return WRAPPERS.get(statement.kind());
    }

    /** One wrapped statement after parsing: its tree, or the first reason it would not read. */
    private record Fragment(ParserRuleContext tree, Unread unread) {
    }

    /** A statement that parsed once a parameter was put aside, and which parameters those were. */
    private record Salvage(Fragment fragment, List<Salvaged> parameters) {
    }

    /** One {@code KEYWORD=value} item of an operand, by its offsets in the statement's text. */
    private record Item(String keyword, int start, int end, int valueStart, int valueEnd) {
    }

    /**
     * Reads the statement again with the parameter the error stands on put aside: first with its
     * value replaced by a stand-in of the same length, so every other column keeps its place, and
     * failing that with the parameter dropped. Returns null when the statement is unreadable for
     * a reason no parameter accounts for.
     *
     * <p>The stand-in is checked against the whole source rather than against this statement: it
     * is put back into every string of the finished model, so a name another statement of the file
     * uses would be rewritten into the value salvaged here.
     */
    private static Salvage salvage(Statement statement, Wrapper wrapper, String source, String path,
            SchedulerMarkers markers, Unread first) {
        if (!SALVAGEABLE.contains(statement.kind())) {
            return null;
        }
        String text = statement.text();
        Unread error = first;
        List<Salvaged> parameters = new ArrayList<>();
        Set<Integer> tried = new LinkedHashSet<>();
        Item item = null;
        for (int attempt = 0; attempt < MAX_SALVAGED_PARAMETERS; attempt++) {
            item = itemAt(text, wrapper, error);
            if (item == null || !tried.add(item.start())) {
                break;
            }
            String stand =
                    markers.salvage(text.substring(item.valueStart(), item.valueEnd()), source);
            if (stand == null) {
                // No name of that length is free, so there is nothing to put the value back from.
                break;
            }
            text = text.substring(0, item.valueStart()) + stand + text.substring(item.valueEnd());
            parameters.add(new Salvaged(statement.line(), item.keyword(), false));
            Fragment retry = parseFragment(wrapper.before() + text + wrapper.after(), path);
            if (retry.unread() == null) {
                return new Salvage(retry, parameters);
            }
            error = retry.unread();
        }
        // The value was not what the grammar refused, so the parameter itself is one it does not
        // know. Dropping it costs that parameter and keeps the statement.
        if (item == null) {
            return null;
        }
        Fragment retry = parseFragment(wrapper.before() + drop(text, item) + wrapper.after(), path);
        if (retry.unread() != null) {
            return null;
        }
        String removed = item.keyword();
        parameters.removeIf(one -> one.keyword().equals(removed));
        parameters.add(new Salvaged(statement.line(), removed, true));
        return new Salvage(retry, parameters);
    }

    /** The statement without one parameter, and without the comma that joined it to the rest. */
    private static String drop(String text, Item item) {
        int from = item.start();
        int to = item.end();
        if (to < text.length() && text.charAt(to) == ',') {
            to++;
        } else if (from > 0 && text.charAt(from - 1) == ',') {
            from--;
        }
        return text.substring(0, from) + text.substring(to);
    }

    /** The parameter the error stands on, or the last one written before it. */
    private static Item itemAt(String text, Wrapper wrapper, Unread error) {
        List<Item> items = itemsOf(text);
        if (items.isEmpty()) {
            return null;
        }
        int offset = offsetOf(text, error.line() - wrapper.linesBefore(), error.column());
        Item best = items.get(0);
        for (Item item : items) {
            if (item.start() <= offset && offset < item.end()) {
                return item;
            }
            if (item.start() <= offset) {
                best = item;
            }
        }
        return best;
    }

    /**
     * The top-level {@code KEYWORD=value} items of a statement's operand. A comma inside
     * parentheses or apostrophes belongs to the value it stands in, and a parameter written
     * across a continuation is left out: nothing can be put in its place without moving a column.
     */
    private static List<Item> itemsOf(String text) {
        Operand operand = JclStatements.operandOf(text);
        String written = operand.text();
        int[] offsets = operand.offsets();
        List<Item> items = new ArrayList<>();
        int depth = 0;
        boolean literal = false;
        int start = 0;
        int equals = -1;
        for (int at = 0; at <= written.length(); at++) {
            // The end of the operand closes the last item whatever the depth is, because an
            // unclosed parenthesis is one of the things a statement is salvaged for.
            boolean last = at == written.length();
            char c = last ? ',' : written.charAt(at);
            if (!last && c == '\'') {
                literal = !literal;
            } else if (literal) {
                continue;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            } else if (c == '=' && depth == 0 && equals < 0) {
                equals = at;
            } else if (c == ',' && (depth == 0 || last)) {
                Item item = item(written, offsets, start, equals, at);
                // An unclosed parenthesis that already holds a keyword of its own has swallowed
                // the parameters written after it, and where its value ends is then a guess; such
                // an item is left alone rather than salvaged along with what it swallowed.
                if (item != null && (depth == 0 || written.indexOf('=', equals + 1) < 0)) {
                    items.add(item);
                }
                start = at + 1;
                equals = -1;
            }
        }
        return items;
    }

    /** One item, or null when it carries no value or is written across a continuation. */
    private static Item item(String written, int[] offsets, int start, int equals, int end) {
        if (equals <= start || equals + 1 >= end) {
            return null;
        }
        if (offsets[end - 1] - offsets[start] != end - 1 - start) {
            return null;
        }
        return new Item(written.substring(start, equals), offsets[start], offsets[end - 1] + 1,
                offsets[equals + 1], offsets[end - 1] + 1);
    }

    /** The offset in a statement's text of one line and column of it, both counted from one. */
    private static int offsetOf(String text, int line, int column) {
        int at = 0;
        for (int i = 1; i < line; i++) {
            int newline = text.indexOf('\n', at);
            if (newline < 0) {
                return text.length();
            }
            at = newline + 1;
        }
        return Math.min(text.length(), at + Math.max(0, column - 1));
    }

    /** Parses one wrapped statement; the error it reports is a line of the wrapped text. */
    private static Fragment parseFragment(String text, String path) {
        JCLLexer lexer = new JCLLexer(CharStreams.fromString(text, path));
        lexer.removeErrorListeners();
        JCLParser parser = new JCLParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        List<Unread> first = new ArrayList<>();
        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                    int charPositionInLine, String message, RecognitionException e) {
                if (first.isEmpty()) {
                    first.add(new Unread(line, charPositionInLine + 1, message));
                }
            }
        });
        JCLParser.StartRuleContext tree = parser.startRule();
        return new Fragment(tree, first.isEmpty() ? null : first.get(0));
    }

    /** The first context of that kind starting on that line of the wrapped text. */
    private static ParserRuleContext find(ParseTree tree,
            Class<? extends ParserRuleContext> type, int line) {
        if (type.isInstance(tree)) {
            ParserRuleContext ctx = type.cast(tree);
            if (ctx.getStart() != null && ctx.getStart().getLine() == line) {
                return ctx;
            }
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            ParserRuleContext found = find(tree.getChild(i), type, line);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
