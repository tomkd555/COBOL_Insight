package jp.cobolinsight.frontend.jcl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import jp.cobolinsight.frontend.jcl.JclSegmentedParse.Salvaged;
import jp.cobolinsight.frontend.jcl.gen.JCLParser;
import jp.cobolinsight.frontend.jcl.gen.JCLParserBaseListener;

/**
 * Collects the raw shape of one JCL source file: the job cards, SET symbols, in-stream PROCs and
 * the ordered items (steps and INCLUDE statements) of each job.
 *
 * <p>Nothing is resolved here. Symbolic parameters are kept verbatim and every line number is the
 * line of the statement in this very file, because a PROC body is expanded per call site and the
 * same statement may end up under several steps.
 *
 * <p>The same instance serves both parse paths. Reading a whole file walks one tree into it;
 * reading statement by statement walks one small tree per statement into it, in source order,
 * with {@link #lineOffset(int)} put back to the line the statement stands on. Everything the
 * listener knows therefore comes from statements arriving in order: a step is held open until the
 * next statement that ends it, and the DD statements between the two belong to it.
 *
 * <p>A file may hold more than one job; each JOB card starts one, and what precedes a JOB card
 * belongs to the job that follows it. Symbols and PROC definitions are shared by every job of the
 * file: a SET or an in-stream PROC written for one job is the best guess available for the next.
 *
 * <p>A SET symbol goes into one map for the whole file, whatever position it was written in and
 * whether or not it stands inside a PROC body. A symbol set twice therefore keeps the last value
 * read rather than the value in force at each step, which is the simplification this listener
 * makes; expanding a PROC per call site already gives each call its own arguments and defaults.
 *
 * <p>Every child context is read through a guard. After a syntax error ANTLR hands the listener a
 * context whose children are missing or fabricated, and a statement that cannot be read whole is
 * skipped with its line recorded rather than turned into half a step.
 */
final class JclModelListener extends JCLParserBaseListener {

    /** The keys the model gives the two positional parameters of the JOB statement. */
    static final String ACCOUNT = "ACCOUNT";
    static final String PROGRAMMER = "PROGRAMMER";

    /** The DD names the job owns rather than a step, which have rules of their own. */
    static final String JOBLIB = "JOBLIB";
    static final String SYSCHK = "SYSCHK";

    /** What an IF statement ends with, and what the ELSE branch of it ends with instead. */
    private static final String THEN = "THEN";
    private static final String ELSE = "ELSE";

    /** The guard an IF the parser could not read states: a test that stands there unread. */
    private static final String UNREADABLE_IF = "IF(?)" + THEN;

    /**
     * One DD statement, or one entry of a DD concatenation. {@code parameters} is every keyword of
     * the statement as written, symbols unresolved; {@code concatIndex} is 0 for the named DD and
     * 1 upwards for each nameless statement under it.
     */
    record RawDd(String ddName, Map<String, String> parameters, List<String> inStreamData,
            int concatIndex, int line) {
    }

    /** An item of a job or PROC body, in source order. */
    sealed interface RawItem {
        int line();
    }

    /**
     * An EXEC statement with the DD statements that follow it and the members INCLUDEd into it.
     * An INCLUDE written after an EXEC contributes its DD statements to that step, which is what
     * {@code includes} carries; an INCLUDE at job or PROC level is an item of its own.
     */
    record RawStep(String name, boolean execPgm, String target, String condition,
            Map<String, String> procArguments, Map<String, String> parameters,
            List<RawDd> ddStatements, List<String> includes, int line) implements RawItem {
    }

    /** An INCLUDE statement, expanded in place from the member library. */
    record RawInclude(String member, int line) implements RawItem {
    }

    /** A PROC definition, in-stream or catalogued, with the file it was read from. */
    record RawProc(String name, String sourceFile, Map<String, String> defaults,
            List<RawItem> items) {
    }

    /** One JOB card with the items that follow it. */
    record RawJob(String name, String condition, Map<String, String> parameters,
            List<RawDd> joblib, RawDd syschk,
            Map<String, Map<String, String>> outputStatements, List<String> jcllib,
            List<RawItem> items, int line) {
    }

    private final String sourceFile;
    /** The in-stream data of each DD, by the line its DD statement starts on. */
    private final Map<Integer, List<String>> inStreamData;
    private final Map<String, String> symbols = new LinkedHashMap<>();
    private final Map<String, RawProc> procs = new LinkedHashMap<>();
    private final List<RawItem> items = new ArrayList<>();
    private final List<RawJob> jobs = new ArrayList<>();
    /** The JCLLIB ORDER libraries of the job being read, which the next job does not inherit. */
    private final Set<String> jcllib = new LinkedHashSet<>();
    /** DD statements of a member that has no step of its own to hold them. */
    private final List<RawDd> memberDds = new ArrayList<>();
    /** Lines of a DD statement whose own step was never read, which no step can hold. */
    private final List<Integer> steplessDdLines = new ArrayList<>();
    /** Lines whose statement could not be read whole, in the order they were met. */
    private final List<Integer> skippedLines = new ArrayList<>();
    /** Parameters the grammar answered with an errorChars node, which are kept as written. */
    private final List<Salvaged> salvagedParameters = new ArrayList<>();
    /** Lines of a PROC a JOB card closed, which a PEND statement should have closed instead. */
    private final List<Integer> unclosedProcLines = new ArrayList<>();
    /** Lines the model has a row for, which is what an unread statement is measured against. */
    private final Set<Integer> modelledLines = new LinkedHashSet<>();

    private int lineOffset;
    private String jobName;
    private String jobCondition;
    private int jobLine;
    private Map<String, String> jobParameters = new LinkedHashMap<>();
    private final List<RawDd> joblib = new ArrayList<>();
    private RawDd syschk;
    private final Map<String, Map<String, String>> outputStatements = new LinkedHashMap<>();
    private String openProcName;
    private int openProcLine;
    private Map<String, String> openProcDefaults;
    private List<RawItem> openProcItems;
    private int unnamedSteps;
    private String lastDdName;
    private int lastConcatIndex;
    /** The DD name a JOBLIB concatenation carries on from. */
    private String joblibName;
    /** Whether the EXEC statement of the step being read was refused, so the step has no row. */
    private boolean stepNotRead;
    /** The step being read, held open until the statement that ends it. */
    private RawStep openStep;
    private List<RawDd> openStepDds;
    private List<String> openStepIncludes;
    /** The IF statements enclosing the current position, innermost first. */
    private final Deque<String> ifGuards = new ArrayDeque<>();

    JclModelListener(String sourceFile) {
        this(sourceFile, Map.of());
    }

    JclModelListener(String sourceFile, Map<Integer, List<String>> inStreamData) {
        this.sourceFile = sourceFile;
        this.inStreamData = inStreamData;
    }

    /** What to add to every line the parser reports, so a statement read alone keeps its place. */
    void lineOffset(int offset) {
        this.lineOffset = offset;
    }

    String sourceFile() {
        return sourceFile;
    }

    List<RawJob> jobs() {
        return jobs;
    }

    Map<String, String> symbols() {
        return symbols;
    }

    Map<String, RawProc> procs() {
        return procs;
    }

    /** The items of a source with no JOB card: the body of a PROC or an INCLUDE member. */
    List<RawItem> items() {
        return items;
    }

    /** The DD statements of an INCLUDE member, which the calling step takes over. */
    List<RawDd> memberDds() {
        return memberDds;
    }

    /** The DD statements written under an EXEC statement nobody could read. */
    List<Integer> steplessDdLines() {
        return steplessDdLines;
    }

    List<Integer> skippedLines() {
        return skippedLines;
    }

    /** The PROC statements a JOB card closed because no PEND statement did. */
    List<Integer> unclosedProcLines() {
        return unclosedProcLines;
    }

    Set<Integer> modelledLines() {
        return modelledLines;
    }

    /**
     * The PROC of a catalogued procedure file: the one named after the member, or the only one
     * defined in the file when the PROC statement carries no name.
     */
    RawProc procNamed(String name) {
        RawProc named = procs.get(name);
        if (named != null) {
            return named;
        }
        return procs.size() == 1 ? procs.values().iterator().next() : null;
    }

    /** Closes what the end of the file leaves open: a step, a PROC without PEND, and the last job. */
    void finish() {
        closeOpenStep();
        closeOpenProc();
        closeJob();
    }

    /** Closes an open PROC, for a PEND statement read on its own. */
    void pend() {
        closeOpenStep();
        closeOpenProc();
    }

    @Override
    public void exitJobCard(JCLParser.JobCardContext ctx) {
        if (!written(ctx.JOB()) || ctx.jobName() == null || ctx.jobName().getText().isBlank()) {
            // Recovery answers a statement it cannot place by inserting the token the rule wanted.
            // Taking that fabricated JOB card for a job would turn every unreadable statement of
            // the file into a job of its own.
            skip(ctx);
            return;
        }
        closeOpenStep();
        // An in-stream PROC the JOB card runs into was never PENDed. Left open, it would swallow
        // the whole of the job that starts here, so it is closed and its line reported.
        if (openProcName != null) {
            unclosedProcLines.add(openProcLine);
        }
        closeOpenProc();
        closeJob();
        modelled(ctx);
        jobName = ctx.jobName().getText();
        jobLine = line(ctx);
        jobParameters = new LinkedHashMap<>();
        // The accounting information and the programmer name are positional, so they carry no
        // keyword of their own; the model gives them one rather than losing them.
        String accounting = ctx.jobAccountingInformation().stream()
                .map(ParserRuleContext::getText).collect(Collectors.joining(","));
        if (!accounting.isBlank()) {
            jobParameters.put(ACCOUNT, accounting);
        }
        if (ctx.jobProgrammerName() != null && !ctx.jobProgrammerName().getText().isBlank()) {
            jobParameters.put(PROGRAMMER, ctx.jobProgrammerName().getText());
        }
        JclParameters.putAll(jobParameters, ctx.jobKeywordParameter(), salvagedAt(line(ctx)));
        for (JCLParser.JobKeywordParameterContext parm : ctx.jobKeywordParameter()) {
            if (parm.jobParmCOND() != null) {
                jobCondition = parm.jobParmCOND().getText();
            }
        }
    }

    /** JOBLIB: a DD of the job rather than of a step, with its own concatenation. */
    @Override
    public void exitJoblibStatement(JCLParser.JoblibStatementContext ctx) {
        joblibName = written(ctx.JOBLIB()) ? ctx.JOBLIB().getText() : JOBLIB;
        addJoblib(ctx, joblibName, 0, joblibParameters(ctx.joblibParameter(), line(ctx)));
    }

    @Override
    public void exitJoblibConcatenation(JCLParser.JoblibConcatenationContext ctx) {
        if (joblibName == null) {
            skip(ctx);
            return;
        }
        addJoblib(ctx, joblibName, joblib.size(),
                joblibParameters(ctx.joblibParameter(), line(ctx)));
    }

    /** SYSCHK: the job-level checkpoint data set a RESTART= rerun reads. */
    @Override
    public void exitSyschkStatement(JCLParser.SyschkStatementContext ctx) {
        if (recovered(ctx)) {
            skip(ctx);
            return;
        }
        modelled(ctx);
        Map<String, String> parameters = new LinkedHashMap<>();
        JclParameters.putAll(parameters, ctx.syschkParameter(), salvagedAt(line(ctx)));
        syschk = new RawDd(written(ctx.SYSCHK()) ? ctx.SYSCHK().getText() : SYSCHK, parameters,
                List.of(), 0, line(ctx));
    }

    /** OUTPUT statement: its parameters by the name in its own name field. */
    @Override
    public void exitOutputStatement(JCLParser.OutputStatementContext ctx) {
        if (!written(ctx.OUTPUT())) {
            skip(ctx);
            return;
        }
        modelled(ctx);
        Map<String, String> parameters = new LinkedHashMap<>();
        for (JCLParser.OutputStatementParameterContext parm : ctx.outputStatementParameter()) {
            JclParameters.put(parameters, parm);
        }
        String name = ctx.NAME_FIELD() == null ? "" : ctx.NAME_FIELD().getText();
        outputStatements.put(name.isBlank() ? "OUTPUT$" + (outputStatements.size() + 1) : name,
                parameters);
    }

    /** JCLLIB ORDER=(lib,lib): recorded as written. Member lookup is the caller's business. */
    @Override
    public void exitJcllibStatement(JCLParser.JcllibStatementContext ctx) {
        modelled(ctx);
        if (ctx.singleOrMultipleValue() == null) {
            return;
        }
        String value = ctx.singleOrMultipleValue().getText();
        for (String library : value.replaceAll("^\\(|\\)$", "").split(",")) {
            if (!library.isBlank()) {
                jcllib.add(library);
            }
        }
    }

    @Override
    public void exitSetStatement(JCLParser.SetStatementContext ctx) {
        modelled(ctx);
        for (JCLParser.SetOperationContext operation : ctx.setOperation()) {
            if (operation.SET_PARM_NAME() == null) {
                skip(operation);
                continue;
            }
            String value = operation.keywordOrSymbolic() == null
                    ? "" : operation.keywordOrSymbolic().getText();
            symbols.put(operation.SET_PARM_NAME().getText(),
                    MapaJclParser.resolveSymbols(value, symbols));
        }
    }

    @Override
    public void exitProcStatement(JCLParser.ProcStatementContext ctx) {
        closeOpenStep();
        closeOpenProc();
        modelled(ctx);
        stepNotRead = false;
        openProcName = ctx.procName() == null ? "" : ctx.procName().getText();
        openProcLine = line(ctx);
        openProcDefaults = new LinkedHashMap<>();
        openProcItems = new ArrayList<>();
        for (JCLParser.DefinedSymbolicParametersContext group : ctx.definedSymbolicParameters()) {
            for (JCLParser.DefineSymbolicParameterContext parm : group.defineSymbolicParameter()) {
                if (parm.PROC_PARM_NAME() == null) {
                    skip(parm);
                    continue;
                }
                openProcDefaults.put(parm.PROC_PARM_NAME().getText(),
                        parm.keywordOrSymbolic() == null ? "" : parm.keywordOrSymbolic().getText());
            }
        }
    }

    @Override
    public void exitPendStatement(JCLParser.PendStatementContext ctx) {
        pend();
    }

    /**
     * A step inside IF ... THEN ... ELSE ... ENDIF runs on the condition the IF states, which is
     * as much a guard on the preceding steps' outcome as a COND parameter is. The whole nest
     * guards the step, so the condition is every enclosing IF joined by one blank, outermost
     * first, and each of them ends in THEN or in ELSE according to the branch the step stands in.
     */
    @Override
    public void enterIfStatement(JCLParser.IfStatementContext ctx) {
        if (!written(ctx.IF()) || recovered(ctx)) {
            // Its place on the stack is kept all the same, so the ELSE and the ENDIF that close it
            // still match this IF and not the one around it.
            unreadableIf();
            return;
        }
        ifGuards.push(ifText(ctx));
    }

    /**
     * The test an IF statement states, read from the IF keyword onwards. What stands before it is
     * the name field, which labels the statement and takes no part in the test.
     *
     * <p>The text comes from the characters the parser read rather than from the tokens it kept,
     * because a blank the author wrote is no token of the tree and JCL needs one to tell
     * {@code AND STEP020.RUN} from a step name of its own. A test written across a continuation is
     * run back together, the {@code //} the next line opens with standing for the blank it holds.
     */
    private static String ifText(JCLParser.IfStatementContext ctx) {
        Token start = ctx.IF().getSymbol();
        Token stop = ctx.getStop();
        if (start.getInputStream() == null || stop == null
                || stop.getStopIndex() < start.getStartIndex()) {
            return UNREADABLE_IF;
        }
        return start.getInputStream()
                .getText(Interval.of(start.getStartIndex(), stop.getStopIndex()))
                .replaceAll("\\s*\\R\\s*//\\s*", " ");
    }

    /** An IF statement the parser could not read, whose test the model states as unread. */
    void unreadableIf() {
        ifGuards.push(UNREADABLE_IF);
    }

    /** The ELSE branch of the innermost IF: the same test, holding when it does not. */
    @Override
    public void exitElseStatement(JCLParser.ElseStatementContext ctx) {
        if (ifGuards.isEmpty()) {
            return;
        }
        String guard = ifGuards.pop();
        ifGuards.push(guard.endsWith(THEN)
                ? guard.substring(0, guard.length() - THEN.length()) + ELSE : guard);
    }

    @Override
    public void exitEndifStatement(JCLParser.EndifStatementContext ctx) {
        if (!ifGuards.isEmpty()) {
            ifGuards.pop();
        }
    }

    /**
     * An INCLUDE after an EXEC contributes DD statements to that step; one at job or PROC level
     * contributes items. The grammar makes the first a child of the step, and reading statement by
     * statement puts it inside the step that is still open, so both paths agree.
     */
    @Override
    public void exitIncludeStatement(JCLParser.IncludeStatementContext ctx) {
        if (!written(ctx.INCLUDE()) || ctx.keywordOrSymbolic() == null
                || ctx.keywordOrSymbolic().getText().isBlank()) {
            skip(ctx);
            return;
        }
        modelled(ctx);
        if (openStep != null) {
            openStepIncludes.add(ctx.keywordOrSymbolic().getText());
            return;
        }
        add(new RawInclude(ctx.keywordOrSymbolic().getText(), line(ctx)));
    }

    /** The EXEC statement opens a step; the DD statements that follow it are its own. */
    @Override
    public void enterJclStep(JCLParser.JclStepContext ctx) {
        JCLParser.ExecStatementContext exec = ctx.execStatement();
        RawStep step = exec == null ? null : execStep(exec);
        closeOpenStep();
        if (step == null) {
            stepNotRead = true;
            skip(ctx);
            return;
        }
        modelled(exec);
        stepNotRead = false;
        openStep = step;
        openStepDds = new ArrayList<>();
        openStepIncludes = new ArrayList<>();
        lastDdName = null;
    }

    /** An EXEC statement the parser could not read, whose step therefore holds no DD statement. */
    void unreadableStep() {
        closeOpenStep();
        stepNotRead = true;
    }

    @Override
    public void exitDdStatement(JCLParser.DdStatementContext ctx) {
        if (!written(ctx.DD()) || ctx.ddName() == null || ctx.ddName().getText().isBlank()) {
            ddNotRead();
            skip(ctx);
            return;
        }
        lastDdName = ctx.ddName().getText();
        lastConcatIndex = 0;
        addDd(ctx, lastDdName, 0, ctx.ddParameter());
    }

    /**
     * A DD statement that produced no row, so nothing is concatenated to it. The nameless DD
     * statements written under it name no DD of this model and are reported rather than filed
     * under the DD before it, which is the last one that did produce a row.
     */
    void ddNotRead() {
        lastDdName = null;
        lastConcatIndex = 0;
    }

    /** A DD written under another DD of the same name: one entry of a concatenation. */
    @Override
    public void exitDdStatementConcatenation(JCLParser.DdStatementConcatenationContext ctx) {
        if (!written(ctx.DD()) || lastDdName == null) {
            skip(ctx);
            return;
        }
        addDd(ctx, lastDdName, ++lastConcatIndex, ctx.ddParameter());
    }

    private void addDd(ParserRuleContext ctx, String ddName, int concatIndex,
            List<JCLParser.DdParameterContext> parameters) {
        if (recovered(ctx)) {
            // A context recovery had a hand in carries the tokens the parser invented
            // (<missing RPAREN>) as well as what was written; a dataset name made of both is
            // worse than none, so the statement is reported rather than half read.
            ddNotRead();
            skip(ctx);
            return;
        }
        modelled(ctx);
        if (openStep == null && stepNotRead) {
            // The EXEC statement this DD belongs to was refused, so there is no step to hold it
            // and it is none of the DD statements an INCLUDE member lends its caller. The row is
            // reported rather than filed where nothing would ever read it.
            steplessDdLines.add(line(ctx));
            return;
        }
        Map<String, String> written = new LinkedHashMap<>();
        JclParameters.putAll(written, parameters, salvagedAt(line(ctx)));
        RawDd dd = new RawDd(ddName, written, inStreamAt(line(ctx)), concatIndex, line(ctx));
        (openStep == null ? memberDds : openStepDds).add(dd);
    }

    private void addJoblib(ParserRuleContext ctx, String ddName, int concatIndex,
            Map<String, String> parameters) {
        if (recovered(ctx)) {
            skip(ctx);
            return;
        }
        modelled(ctx);
        joblib.add(new RawDd(ddName, parameters, List.of(), concatIndex, line(ctx)));
    }

    private Map<String, String> joblibParameters(
            List<JCLParser.JoblibParameterContext> parameters, int line) {
        Map<String, String> written = new LinkedHashMap<>();
        JclParameters.putAll(written, parameters, salvagedAt(line));
        return written;
    }

    /** Where a parameter the grammar could not read apart is reported, by the line it stands on. */
    private Consumer<String> salvagedAt(int line) {
        return keyword -> salvagedParameters.add(new Salvaged(line, keyword, false));
    }


    /** The parameters this walk kept whole because nothing read them apart. */
    List<Salvaged> salvagedParameters() {
        return salvagedParameters;
    }

    /** The in-stream data of the DD written on that line, verbatim and without its delimiter. */
    private List<String> inStreamAt(int line) {
        return inStreamData.getOrDefault(line, List.of());
    }

    /** The EXEC statement as a step, or null when recovery left too little of it to read. */
    private RawStep execStep(JCLParser.ExecStatementContext exec) {
        if (exec.execPgmStatement() != null) {
            return pgmStep(exec.execPgmStatement());
        }
        return exec.execProcStatement() == null ? null : procStep(exec.execProcStatement());
    }

    private RawStep pgmStep(JCLParser.ExecPgmStatementContext ctx) {
        if (!written(ctx.EXEC()) || recovered(ctx) || ctx.keywordOrSymbolic() == null
                || ctx.keywordOrSymbolic().getText().isBlank()) {
            return null;
        }
        String condition = null;
        Map<String, String> parameters = new LinkedHashMap<>();
        JclParameters.putAll(parameters, ctx.execParameter(), salvagedAt(line(ctx)));
        for (JCLParser.ExecParameterContext parm : ctx.execParameter()) {
            if (unqualifiedCond(parm.execParmCOND())) {
                condition = parm.execParmCOND().getText();
            }
        }
        return new RawStep(stepName(ctx.stepName()), true, ctx.keywordOrSymbolic().getText(),
                guarded(condition), Map.of(), parameters, List.of(), List.of(), line(ctx));
    }

    private RawStep procStep(JCLParser.ExecProcStatementContext ctx) {
        if (!written(ctx.EXEC()) || recovered(ctx) || ctx.keywordOrSymbolic() == null
                || ctx.keywordOrSymbolic().getText().isBlank()) {
            return null;
        }
        String condition = null;
        Map<String, String> parameters = new LinkedHashMap<>();
        Map<String, String> arguments = new LinkedHashMap<>();
        // The symbolic arguments and the parameter overrides are written mixed together, and the
        // parameter map keeps them in that order; the arguments have a map of their own as well,
        // because they alone open the PROC's scope.
        for (ParseTree child : ctx.children == null ? List.<ParseTree>of() : ctx.children) {
            if (child instanceof JCLParser.ExecProcParmContext parm) {
                if (parm.EXEC_PROC_PARM() == null) {
                    skip(parm);
                    continue;
                }
                JclParameters.put(parameters, parm);
                arguments.put(parm.EXEC_PROC_PARM().getText(),
                        parm.keywordOrSymbolic() == null ? "" : parm.keywordOrSymbolic().getText());
            } else if (child instanceof JCLParser.ExecParameterOverridesContext parm) {
                JclParameters.put(parameters, parm);
                if (unqualifiedCond(parm.execParmCOND())) {
                    condition = parm.execParmCOND().getText();
                }
            }
        }
        return new RawStep(stepName(ctx.stepName()), false, ctx.keywordOrSymbolic().getText(),
                guarded(condition), arguments, parameters, List.of(), List.of(), line(ctx));
    }

    /**
     * Whether a COND parameter is the step's own. A {@code COND.procstep=} names a step inside the
     * PROC the EXEC calls, and belongs to that step rather than to the call; the expansion carries
     * it there.
     */
    private static boolean unqualifiedCond(JCLParser.ExecParmCONDContext cond) {
        return cond != null && cond.EXEC_COND() != null
                && cond.EXEC_COND().getText().indexOf('.') < 0;
    }

    /**
     * Everything that guards the step: its own COND and the IF statements enclosing it, every one
     * of them outermost first, joined by one blank. On z/OS both apply — the IF decides whether
     * the step is selected and the COND whether it is bypassed — so a step that codes both keeps
     * both. An IF the parser could not read stands in the text as {@code IF(?)THEN}, so a reader
     * sees that a guard is there and that nobody read it.
     *
     * <p>The step's own COND stands first, because that is where a reader looks for it and where
     * the rules that tell a COND from an enclosing IF read it.
     */
    private String guarded(String condition) {
        if (ifGuards.isEmpty()) {
            return condition;
        }
        StringBuilder out = new StringBuilder(condition == null ? "" : condition);
        for (Iterator<String> nest = ifGuards.descendingIterator(); nest.hasNext();) {
            out.append(out.length() == 0 ? "" : " ").append(nest.next());
        }
        return out.toString();
    }

    /**
     * Whether recovery had a hand in the tree. Both parse paths already discard a tree on the
     * first syntax error, so today this never changes the outcome; it is the belt to that pair of
     * braces, and what it guards against — a value made half of what was written and half of what
     * the parser invented — is what the fragment-error path exists to prevent.
     */
    private static boolean recovered(ParseTree tree) {
        if (tree instanceof ErrorNode) {
            return true;
        }
        if (tree instanceof ParserRuleContext ctx && ctx.exception != null) {
            return true;
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            if (recovered(tree.getChild(i))) {
                return true;
            }
        }
        return false;
    }

    private String stepName(JCLParser.StepNameContext ctx) {
        return ctx == null || ctx.getText().isBlank() ? "STEP$" + (++unnamedSteps) : ctx.getText();
    }

    /** Whether a token stands in the source, as opposed to one recovery inserted to go on. */
    private static boolean written(TerminalNode token) {
        return token != null && !(token instanceof ErrorNode);
    }

    private int line(ParserRuleContext ctx) {
        return Math.max(1, (ctx.getStart() == null ? 1 : ctx.getStart().getLine()) + lineOffset);
    }

    private void modelled(ParserRuleContext ctx) {
        modelledLines.add(line(ctx));
    }

    private void skip(ParserRuleContext ctx) {
        skippedLines.add(line(ctx));
    }

    private void add(RawItem item) {
        (openProcItems == null ? items : openProcItems).add(item);
    }

    void closeOpenStep() {
        if (openStep == null) {
            return;
        }
        add(new RawStep(openStep.name(), openStep.execPgm(), openStep.target(),
                openStep.condition(), openStep.procArguments(), openStep.parameters(),
                List.copyOf(openStepDds), List.copyOf(openStepIncludes), openStep.line()));
        openStep = null;
        openStepDds = null;
        openStepIncludes = null;
        lastDdName = null;
        lastConcatIndex = 0;
    }

    void closeJob() {
        if (jobName == null) {
            return;
        }
        jobs.add(new RawJob(jobName, jobCondition, new LinkedHashMap<>(jobParameters),
                List.copyOf(joblib), syschk, new LinkedHashMap<>(outputStatements),
                List.copyOf(jcllib), List.copyOf(items), jobLine));
        items.clear();
        joblib.clear();
        outputStatements.clear();
        // A JCLLIB ORDER is the library order of the job it stands in, and the job that follows
        // orders its own; an IF the job left open guards nothing there either.
        jcllib.clear();
        ifGuards.clear();
        stepNotRead = false;
        jobName = null;
        jobCondition = null;
        jobParameters = new LinkedHashMap<>();
        joblibName = null;
        syschk = null;
    }

    void closeOpenProc() {
        if (openProcName == null) {
            return;
        }
        procs.put(openProcName,
                new RawProc(openProcName, sourceFile, new LinkedHashMap<>(openProcDefaults),
                        List.copyOf(openProcItems)));
        openProcName = null;
        openProcDefaults = null;
        openProcItems = null;
    }
}
