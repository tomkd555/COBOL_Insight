package jp.cobolinsight.frontend.jcl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jp.cobolinsight.frontend.jcl.gen.JCLParser;
import jp.cobolinsight.frontend.jcl.gen.JCLParserBaseListener;

/**
 * Collects the raw shape of one JCL source file: the job card, SET symbols, in-stream PROCs and
 * the ordered items (steps and INCLUDE statements) of the job.
 *
 * <p>Nothing is resolved here. Symbolic parameters are kept verbatim and every line number is the
 * line of the statement in this very file, because a PROC body is expanded per call site and the
 * same statement may end up under several steps.
 *
 * <p>A file may hold more than one job. Only the first is collected; the SPI hands back one job
 * model per source.
 */
final class JclModelListener extends JCLParserBaseListener {

    /** One DD statement, or one entry of a DD concatenation. */
    record RawDd(String ddName, String datasetName, int line) {
    }

    /** An item of a job or PROC body, in source order. */
    sealed interface RawItem {
        int line();
    }

    /** An EXEC statement with the DD statements that follow it. */
    record RawStep(String name, boolean execPgm, String target, String condition,
            Map<String, String> procArguments, List<RawDd> ddStatements, int line)
            implements RawItem {
    }

    /**
     * An INCLUDE statement, expanded in place from the procedure libraries.
     *
     * <p>ponytail: an INCLUDE written inside a step, which contributes DD statements to that step
     * rather than steps to the job, lands here as an item of its own and so contributes nothing.
     * Splice the member's DD statements onto the enclosing step if such JCL ever shows up.
     */
    record RawInclude(String member, int line) implements RawItem {
    }

    /** A PROC definition, in-stream or catalogued, with the file it was read from. */
    record RawProc(String name, String sourceFile, Map<String, String> defaults,
            List<RawItem> items) {
    }

    private final String sourceFile;
    private final Map<String, String> symbols = new LinkedHashMap<>();
    private final Map<String, RawProc> procs = new LinkedHashMap<>();
    private final List<RawItem> items = new ArrayList<>();

    private String jobName;
    private String jobCondition;
    private boolean pastFirstJob;
    private String openProcName;
    private Map<String, String> openProcDefaults;
    private List<RawItem> openProcItems;
    private int unnamedSteps;

    JclModelListener(String sourceFile) {
        this.sourceFile = sourceFile;
    }

    String sourceFile() {
        return sourceFile;
    }

    String jobName() {
        return jobName;
    }

    String jobCondition() {
        return jobCondition;
    }

    Map<String, String> symbols() {
        return symbols;
    }

    Map<String, RawProc> procs() {
        return procs;
    }

    List<RawItem> items() {
        return items;
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

    /** Closes a PROC left open by a catalogued procedure file that ends without PEND. */
    void finish() {
        closeOpenProc();
    }

    @Override
    public void exitJobCard(JCLParser.JobCardContext ctx) {
        if (jobName != null) {
            pastFirstJob = true;
            return;
        }
        jobName = ctx.jobName().getText();
        for (JCLParser.JobKeywordParameterContext parm : ctx.jobKeywordParameter()) {
            if (parm.jobParmCOND() != null) {
                jobCondition = parm.jobParmCOND().getText();
            }
        }
    }

    @Override
    public void exitSetStatement(JCLParser.SetStatementContext ctx) {
        if (pastFirstJob) {
            return;
        }
        for (JCLParser.SetOperationContext operation : ctx.setOperation()) {
            String value = operation.keywordOrSymbolic() == null
                    ? "" : operation.keywordOrSymbolic().getText();
            symbols.put(operation.SET_PARM_NAME().getText(),
                    MapaJclParser.resolveSymbols(value, symbols));
        }
    }

    @Override
    public void exitProcStatement(JCLParser.ProcStatementContext ctx) {
        if (pastFirstJob) {
            return;
        }
        closeOpenProc();
        openProcName = ctx.procName() == null ? "" : ctx.procName().getText();
        openProcDefaults = new LinkedHashMap<>();
        openProcItems = new ArrayList<>();
        for (JCLParser.DefinedSymbolicParametersContext group : ctx.definedSymbolicParameters()) {
            for (JCLParser.DefineSymbolicParameterContext parm : group.defineSymbolicParameter()) {
                openProcDefaults.put(parm.PROC_PARM_NAME().getText(),
                        parm.keywordOrSymbolic() == null ? "" : parm.keywordOrSymbolic().getText());
            }
        }
    }

    @Override
    public void exitPendStatement(JCLParser.PendStatementContext ctx) {
        closeOpenProc();
    }

    @Override
    public void exitIncludeStatement(JCLParser.IncludeStatementContext ctx) {
        if (pastFirstJob) {
            return;
        }
        add(new RawInclude(ctx.keywordOrSymbolic().getText(), ctx.getStart().getLine()));
    }

    @Override
    public void exitJclStep(JCLParser.JclStepContext ctx) {
        if (pastFirstJob) {
            return;
        }
        JCLParser.ExecStatementContext exec = ctx.execStatement();
        RawStep step = exec.execPgmStatement() != null
                ? pgmStep(exec.execPgmStatement()) : procStep(exec.execProcStatement());
        List<RawDd> dds = new ArrayList<>(step.ddStatements());
        for (JCLParser.DdStatementAmalgamationContext amalgamation : ctx.ddStatementAmalgamation()) {
            JCLParser.DdStatementContext dd = amalgamation.ddStatement();
            String ddName = dd.ddName().getText();
            dds.add(new RawDd(ddName, datasetName(dd.ddParameter()), dd.getStart().getLine()));
            for (JCLParser.DdStatementConcatenationContext next
                    : amalgamation.ddStatementConcatenation()) {
                dds.add(new RawDd(ddName, datasetName(next.ddParameter()),
                        next.getStart().getLine()));
            }
        }
        add(new RawStep(step.name(), step.execPgm(), step.target(), step.condition(),
                step.procArguments(), List.copyOf(dds), step.line()));
    }

    private RawStep pgmStep(JCLParser.ExecPgmStatementContext ctx) {
        String condition = null;
        for (JCLParser.ExecParameterContext parm : ctx.execParameter()) {
            if (parm.execParmCOND() != null) {
                condition = parm.execParmCOND().getText();
            }
        }
        return new RawStep(stepName(ctx.stepName()), true, ctx.keywordOrSymbolic().getText(),
                condition, Map.of(), List.of(), ctx.getStart().getLine());
    }

    private RawStep procStep(JCLParser.ExecProcStatementContext ctx) {
        String condition = null;
        for (JCLParser.ExecParameterOverridesContext parm : ctx.execParameterOverrides()) {
            if (parm.execParmCOND() != null) {
                condition = parm.execParmCOND().getText();
            }
        }
        Map<String, String> arguments = new LinkedHashMap<>();
        for (JCLParser.ExecProcParmContext parm : ctx.execProcParm()) {
            arguments.put(parm.EXEC_PROC_PARM().getText(),
                    parm.keywordOrSymbolic() == null ? "" : parm.keywordOrSymbolic().getText());
        }
        return new RawStep(stepName(ctx.stepName()), false, ctx.keywordOrSymbolic().getText(),
                condition, arguments, List.of(), ctx.getStart().getLine());
    }

    /** DUMMY, SYSOUT= and DD * carry no dataset, which the model represents as an absent DSN. */
    private static String datasetName(List<JCLParser.DdParameterContext> parameters) {
        for (JCLParser.DdParameterContext parameter : parameters) {
            if (parameter.ddParmDSNAME() != null) {
                return parameter.ddParmDSNAME().datasetName().getText();
            }
        }
        return null;
    }

    private String stepName(JCLParser.StepNameContext ctx) {
        return ctx == null ? "STEP$" + (++unnamedSteps) : ctx.getText();
    }

    private void add(RawItem item) {
        (openProcItems == null ? items : openProcItems).add(item);
    }

    private void closeOpenProc() {
        if (openProcName == null) {
            return;
        }
        procs.put(openProcName,
                new RawProc(openProcName, sourceFile, Map.copyOf(openProcDefaults),
                        List.copyOf(openProcItems)));
        openProcName = null;
        openProcDefaults = null;
        openProcItems = null;
    }
}
