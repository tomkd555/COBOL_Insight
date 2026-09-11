package jp.cobolinsight.frontend.jcl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.tree.ParseTreeWalker;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.core.jcl.JclDdStatement;
import jp.cobolinsight.core.jcl.JclExecKind;
import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.jcl.JclMemberMiss;
import jp.cobolinsight.core.jcl.JclOverrideMiss;
import jp.cobolinsight.core.jcl.JclReferbackMiss;
import jp.cobolinsight.core.jcl.JclStep;
import jp.cobolinsight.core.source.DecodedSource;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.JclMemberResolver;
import jp.cobolinsight.core.spi.JclParser;
import jp.cobolinsight.core.spi.ParseOutcome;
import jp.cobolinsight.frontend.jcl.JclModelListener.RawDd;
import jp.cobolinsight.frontend.jcl.JclModelListener.RawInclude;
import jp.cobolinsight.frontend.jcl.JclModelListener.RawItem;
import jp.cobolinsight.frontend.jcl.JclModelListener.RawJob;
import jp.cobolinsight.frontend.jcl.JclModelListener.RawProc;
import jp.cobolinsight.frontend.jcl.JclModelListener.RawStep;
import jp.cobolinsight.frontend.jcl.JclSegmentedParse.Salvaged;
import jp.cobolinsight.frontend.jcl.JclSegmentedParse.Unread;
import jp.cobolinsight.frontend.jcl.JclStatements.Kind;
import jp.cobolinsight.frontend.jcl.JclStatements.Statement;
import jp.cobolinsight.frontend.jcl.instream.InStreamFacts;
import jp.cobolinsight.frontend.jcl.gen.JCLLexer;
import jp.cobolinsight.frontend.jcl.gen.JCLParser;

/**
 * {@link JclParser} over the MAPA grammars, driving {@link JclModelListener}.
 *
 * <p>The source is parsed as it stands, so every line number is a line of the file the statement
 * was written in. A step of an expanded PROC keeps the line of its own EXEC statement inside the
 * PROC (for a catalogued PROC, in the PROC's file, which the step's position then names); the line
 * of the invoking EXEC is carried by the PROC call step itself, which precedes it in the model.
 *
 * <p>PROC steps are flattened onto the job's step list: the call step (execKind=PROC) first, then
 * the expanded steps named "call step name.PROC step name".
 *
 * <p>Two paths lead to the same model. A file is first read whole, which is what the grammar is
 * written for. When the parser reports a syntax error, or when a statement {@link JclStatements}
 * found has no row in the model, the file is read again statement by statement
 * ({@link JclSegmentedParse}): the grammar reads a whole file in one rule, so recovery inside it
 * drops the statements around the one it could not read, and reading each statement on its own
 * limits the loss to that statement. Whatever is still unread is reported line by line as
 * {@code jcl-syntax}, so nothing is dropped without a word.
 */
public final class MapaJclParser implements JclParser {

    /**
     * &amp;NAME, with the optional dot that ends the name rather than belonging to the value. A
     * symbol's name opens with a letter or a national character and runs to eight characters at
     * most, so a longer name is no symbol at all and is left alone rather than cut at the eighth.
     */
    private static final Pattern SYMBOL = Pattern.compile("&([A-Z@#$][A-Z0-9@#$]{0,7})\\.?");

    /** {@code (2:1)} after a symbol: a substring of it, which JCL does not substitute. */
    private static final Pattern SUBSTRING = Pattern.compile("\\(\\d+:\\d+\\)");

    /**
     * The symbols z/OS itself sets. None of them is written anywhere in the JCL, so a job that
     * uses one is not missing a SET statement and the symbol is not reported as unresolved.
     */
    private static final Set<String> SYSTEM_SYMBOLS = Set.of(
            "&SYSUID", "&SYSDATE", "&SYSTIME", "&SYSNAME", "&SYSCLONE", "&SYSPLEX", "&SYSJOBNM",
            "&SYSR1", "&LYYMMDD", "&LYR4", "&LMON", "&LDAY", "&LHR", "&LMIN", "&LSEC", "&YYMMDD",
            "&YR4", "&MON", "&DAY", "&HR", "&MIN", "&SEC", "&JDAY", "&WDAY", "&SYSSYMR",
            "&SYSALVL", "&SYSNUM");

    /** The EXEC parameter whose value a step keeps apart, so a rule need not cut it out again. */
    private static final String PARM = "PARM";

    /** The EXEC parameter a step's condition is read from. */
    private static final String COND = "COND";

    /**
     * The EXEC keywords a PROC call may write over its PROC's own, which is the list the grammar's
     * {@code execParameterOverrides} rule holds. A keyword outside it that stands unqualified on a
     * PROC call is a symbolic argument of the PROC and no override at all.
     */
    private static final Set<String> OVERRIDABLE_KEYWORDS = Set.of("ACCT", "ADDRSPC", "COND",
            "DYNAMNBR", "PARM", "PERFORM", "RD", "REGION", "REGIONX", "TIME");

    /** Guards against a PROC that invokes itself, directly or through another. */
    private static final int MAX_PROC_DEPTH = 8;

    /**
     * How many unread statements of one source are reported. Reading a file that is not JCL at all
     * can report one per line, and a hundred lines saying the same thing help nobody.
     */
    private static final int MAX_SYNTAX_DIAGNOSTICS = 50;

    /** How much of an ANTLR message is kept: enough to name the construct that broke. */
    private static final int MAX_MESSAGE_LENGTH = 160;

    /** What the listener said when it refused a statement recovery had left too little of. */
    private static final String NO_VALUE = "名前または値が読み取れません";

    /** What a statement the whole-file parse dropped without a word says for itself. */
    private static final String NO_OPERAND = "オペランドを読み取れません";

    /** The delimiter that ends in-stream data when the DD statement names none. */
    private static final String DEFAULT_DELIMITER = "/*";

    /**
     * One walked source: what the listener collected, what it could not read, and the statements
     * the segmenter found, which is where the JECL cards of the file are read from.
     */
    private record Walk(JclModelListener listener, List<Finding> findings, boolean segmented,
            List<Statement> statements) {
    }

    /**
     * Whether the last source this instance parsed fell back to reading statement by statement.
     * Only the test that compares the two paths reads it, and it reads it for one file at a time;
     * nothing in the engine branches on it.
     */
    private boolean segmented;

    @Override
    public ParseOutcome<List<JclJobModel>> parse(DecodedSource source, JclMemberResolver members) {
        return parse(source, members, false);
    }

    /** Whether the source last parsed was read statement by statement rather than whole. */
    boolean segmented() {
        return segmented;
    }

    /** Parses with the fast path skipped, which is how the two paths are compared in a test. */
    ParseOutcome<List<JclJobModel>> parse(DecodedSource source, JclMemberResolver members,
            boolean forceSegmented) {
        SchedulerMarkers markers = new SchedulerMarkers();
        Walk walk;
        try {
            walk = walk(source.text(), source.path(), markers, forceSegmented);
            segmented = walk.segmented();
        } catch (RuntimeException e) {
            return ParseOutcome.failure(Finding.parseFailure(
                    SourcePosition.fileStart(source.path()),
                    source.path() + " の JCL を解析できませんでした。予期しない内部エラーです（"
                            + e.getClass().getSimpleName() + "）。"));
        }
        JclModelListener listener = walk.listener();
        if (listener.jobs().isEmpty()) {
            return ParseOutcome.failure(Finding.parseFailure(
                    SourcePosition.fileStart(source.path()),
                    source.path() + " に JOB 文が見つかりません。"));
        }
        List<JclJobModel> jobs = new ArrayList<>();
        // One cache of walked members for the whole file: a member two jobs name is read once, and
        // its own diagnostics are attached to the first job that reached it.
        Map<String, Walk> loaded = new LinkedHashMap<>();
        List<RawJob> raw = listener.jobs();
        for (int at = 0; at < raw.size(); at++) {
            RawJob job = raw.get(at);
            Expansion expansion = new Expansion(members, markers, forceSegmented, loaded);
            // One scope for the whole job. The steps are expanded first, because an INCLUDE member
            // a step names may hold the SET statement that gives a job-level parameter its value.
            Map<String, String> scope = new LinkedHashMap<>(listener.symbols());
            List<JclStep> steps = restore(markers, expansion.expandItems(job.items(), "",
                    listener.sourceFile(), scope, listener.procs(), 0));
            List<JclDdStatement> joblib = restoreDds(markers,
                    expansion.ddStatements(job.joblib(), source.path(), scope));
            Optional<JclDdStatement> syschk = Optional.ofNullable(job.syschk())
                    .map(dd -> expansion.ddStatements(List.of(dd), source.path(), scope).get(0))
                    .map(dd -> restoreDds(markers, List.of(dd)).get(0));
            List<JclReferbackMiss> referbacks = new ArrayList<>();
            Referbacks.Resolved resolved =
                    Referbacks.resolve(jobLevel(joblib, syschk), steps, referbacks);
            steps = InStreamFacts.readCards(resolved.steps());
            // The file's own diagnostics belong to the file, so they are carried by its first job
            // rather than repeated under every job the file holds.
            List<Finding> diagnostics =
                    new ArrayList<>(jobs.isEmpty() ? walk.findings() : List.of());
            diagnostics.addAll(expansion.diagnostics());
            diagnostics.sort(Comparator.comparing((Finding f) -> f.location().file())
                    .thenComparingInt(f -> f.location().line())
                    .thenComparingInt(f -> f.location().column()));
            // The first job of a file takes the cards written before any JOB statement with it;
            // every other job takes the cards written from its own JOB statement onwards.
            int from = at == 0 ? 0 : job.line();
            int to = at + 1 < raw.size() ? raw.get(at + 1).line() : Integer.MAX_VALUE;
            // Every field is resolved before the model is built, because resolving one may meet a
            // symbol nothing answers and the list of those has to be complete when it is read.
            List<String> jcllib = job.jcllib().stream()
                    .map(library -> expansion.resolve(library, scope)).map(markers::restore)
                    .toList();
            Map<String, String> parameters =
                    restoreValues(markers, expansion.resolveValues(job.parameters(), scope));
            Map<String, Map<String, String>> outputStatements = restoreOutputStatements(markers,
                    expansion.resolveOutputStatements(job.outputStatements(), scope));
            jobs.add(new JclJobModel(markers.restore(job.name()), source.path(),
                    Optional.ofNullable(job.condition()).map(markers::restore), steps, diagnostics,
                    expansion.members(), jcllib, markers.variables(), parameters,
                    resolved.jobLevel().subList(0, joblib.size()),
                    syschk.map(dd -> resolved.jobLevel().get(joblib.size())),
                    jes2Cards(walk.statements(), from, to), expansion.unresolvedSymbols(),
                    outputStatements,
                    restoreOverrides(markers, expansion.unresolvedOverrides()),
                    restoreReferbacks(markers, referbacks),
                    restoreMembers(markers, expansion.missingMembers()),
                    new SourcePosition(source.path(), Math.max(1, job.line()), 1,
                            SourcePosition.UNKNOWN_BYTE_OFFSET)));
        }
        return ParseOutcome.success(jobs);
    }

    /** The DD statements the job owns, JOBLIB first and SYSCHK after it, as one list to resolve. */
    private static List<JclDdStatement> jobLevel(List<JclDdStatement> joblib,
            Optional<JclDdStatement> syschk) {
        List<JclDdStatement> all = new ArrayList<>(joblib);
        syschk.ifPresent(all::add);
        return all;
    }

    /** The JECL cards written between those two lines, as the author wrote them. */
    private static List<String> jes2Cards(List<Statement> statements, int from, int to) {
        List<String> cards = new ArrayList<>();
        for (Statement statement : statements) {
            if (statement.kind() == Kind.JECL && !statement.isDelimiter()
                    && statement.line() >= from && statement.line() < to) {
                cards.add(statement.text());
            }
        }
        return cards;
    }

    /**
     * Whether the source is a member rather than a job. The statement map decides it, so a member
     * the grammar cannot read whole — an INCLUDE member of DD statements alone, which no rule of
     * the grammar accepts on its own — is still a member and not a job that failed.
     */
    @Override
    public boolean isMember(DecodedSource source) {
        List<Statement> statements = JclStatements.of(source.text());
        return statements.stream().noneMatch(s -> s.kind() == Kind.JOB)
                && statements.stream().anyMatch(Statement::isJclStatement);
    }

    @Override
    public List<Finding> diagnose(DecodedSource source) {
        try {
            return walk(source.text(), source.path(), new SchedulerMarkers(), false).findings();
        } catch (RuntimeException e) {
            return List.of(Finding.parseFailure(SourcePosition.fileStart(source.path()),
                    source.path() + " の JCL を解析できませんでした。予期しない内部エラーです（"
                            + e.getClass().getSimpleName() + "）。"));
        }
    }

    /** Reads one JCL source, whole if the grammar manages and statement by statement if not. */
    private static Walk walk(String text, String path, SchedulerMarkers markers,
            boolean forceSegmented) {
        List<Statement> written = JclStatements.of(text);
        SchedulerMarkers.Result prepared = markers.apply(text, path, written);
        // The pre-pass never changes the length of a line, so the statements of the prepared text
        // are the statements of the source with their text rewritten: the map is built once.
        List<Statement> statements = JclStatements.rewritten(written, prepared.text());
        List<Finding> findings = new ArrayList<>(prepared.directives());
        // The in-stream data belongs to the DD statement that opened it, which is the statement
        // before it in the map; both parse paths read it from there rather than from the grammar,
        // because a statement read on its own never meets the data lines under it.
        Map<Integer, List<String>> inStream = JclStatements.inStreamData(written);

        JclModelListener listener = new JclModelListener(path, inStream);
        List<Unread> unread = forceSegmented ? List.of()
                : readWholeFile(prepared.text(), path, listener);
        Set<Salvaged> salvaged = new LinkedHashSet<>();
        boolean readWhole = !forceSegmented && unread.isEmpty()
                && unaccounted(statements, listener).isEmpty()
                && !readDataAsJcl(statements, listener);
        if (!readWhole) {
            listener = new JclModelListener(path, inStream);
            JclSegmentedParse.Outcome outcome =
                    JclSegmentedParse.parse(statements, prepared.text(), path, listener, markers);
            unread = outcome.unread();
            salvaged.addAll(outcome.salvaged());
            listener.finish();
        }
        // A parameter the grammar answered with an errorChars node is a parse success that hides an
        // unread operand, so the walk reports it whichever path read the file.
        salvaged.addAll(listener.salvagedParameters());
        List<Unread> all = new ArrayList<>(unread);
        listener.skippedLines().forEach(line -> all.add(new Unread(line, 1, NO_VALUE)));
        unaccounted(statements, listener).forEach(line -> all.add(new Unread(line, 1, NO_OPERAND)));
        Set<Integer> seen = new LinkedHashSet<>();
        int reported = 0;
        int suppressed = 0;
        int firstSuppressed = 0;
        for (Unread one : all) {
            if (!seen.add(one.line())) {
                continue;
            }
            if (reported < MAX_SYNTAX_DIAGNOSTICS) {
                findings.add(syntaxDiagnostic(path, one.line(), one.column(), one.message()));
                reported++;
            } else {
                suppressed++;
                firstSuppressed = firstSuppressed == 0 ? one.line() : firstSuppressed;
            }
        }
        if (suppressed > 0) {
            findings.add(diagnostic(path, firstSuppressed,
                    "これ以降の " + suppressed + " 文も解析できませんでした。"));
        }
        for (Salvaged one : salvaged) {
            findings.add(diagnostic(path, one.line(), one.line() + " 行の JCL 文のパラメーター "
                    + one.keyword() + (one.removed()
                            ? " を解析できないため、取り除いて解析しました。"
                            : " を解析できませんでした。値は原文のまま保持します。")));
        }
        for (int line : listener.unclosedProcLines()) {
            findings.add(diagnostic(path, line,
                    line + " 行の PROC 文に対応する PEND 文がないため、JOB 文の手前で閉じました。"));
        }
        for (int line : listener.steplessDdLines()) {
            findings.add(diagnostic(path, line, line
                    + " 行の DD 文は、EXEC 文を解析できないため、"
                    + "どのステップにも取り込めませんでした。"));
        }
        for (Statement statement : statements) {
            if (!statement.missingDelimiter().isEmpty()) {
                findings.add(diagnostic(path, statement.line(), statement.line()
                        + (statement.missingDelimiter().equals(DEFAULT_DELIMITER)
                                ? " 行のインストリームデータに区切り行 /* がないまま、"
                                : " 行の DLM に指定した区切り文字 "
                                        + statement.missingDelimiter() + " が現れないまま、")
                        + "ファイルの終わりに達しました。"));
            }
        }
        return new Walk(listener, restored(markers, findings), !readWhole, written);
    }

    /**
     * Every diagnostic with the author's own text put back. A message quotes what the parser read,
     * and what the parser read is the text the pre-pass handed it, so a placeholder would otherwise
     * reach the reader in place of the token or the value that stands in the file.
     */
    private static List<Finding> restored(SchedulerMarkers markers, List<Finding> findings) {
        if (markers.isEmpty()) {
            return findings;
        }
        List<Finding> out = new ArrayList<>();
        for (Finding finding : findings) {
            out.add(Finding.of(finding.ruleId(), finding.level(),
                    markers.restore(finding.message()), finding.location()));
        }
        return out;
    }

    /** The whole-file parse: the grammar as it is meant to be used. */
    private static List<Unread> readWholeFile(String text, String path,
            JclModelListener listener) {
        List<Unread> unread = new ArrayList<>();
        JCLLexer lexer = new JCLLexer(CharStreams.fromString(text, path));
        // Lexer errors are recovered from by the lexer itself and MAPA's grammars raise them on
        // input the parser then handles; only parser errors mean a statement was not understood.
        lexer.removeErrorListeners();
        JCLParser parser = new JCLParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                    int charPositionInLine, String message, RecognitionException e) {
                unread.add(new Unread(line, charPositionInLine + 1, message));
            }
        });
        ParseTreeWalker.DEFAULT.walk(listener, parser.startRule());
        listener.finish();
        return unread;
    }

    /**
     * The statements the model has no row for: what the whole-file parse quietly dropped.
     *
     * <p>The check is line-granular. It asks whether the model holds any row that starts on the
     * statement's line, not whether the row says what the statement says, so a statement read
     * wrongly or in part passes it — a DD whose dataset name the grammar mangled has a row and is
     * counted as read. What the check does catch is a statement with no row at all, which is the
     * loss the whole-file rule causes when recovery inside it gives up on a stretch of the file.
     *
     * <p>Salvaging a parameter narrows the gap from the other side: a statement the grammar
     * refuses whole now enters the model with the parameter it could not read put aside, and that
     * parameter is reported on its own rather than the whole statement being lost.
     */
    private static List<Integer> unaccounted(List<Statement> statements,
            JclModelListener listener) {
        List<Integer> lines = new ArrayList<>();
        for (Statement statement : statements) {
            if (statement.carriesModel() && !listener.modelledLines().contains(statement.line())
                    && !listener.skippedLines().contains(statement.line())) {
                lines.add(statement.line());
            }
        }
        return lines;
    }

    /**
     * Whether the model holds a row on a line the file gives to in-stream data. The other half of
     * the cross-check: a row too many rather than one too few. An XMIT payload and a
     * {@code DD DATA} stream both read as JCL, and the whole-file rule takes them for JCL of this
     * job, so the file goes round again statement by statement, where the segmenter's map says
     * which lines a program reads as its own input.
     */
    private static boolean readDataAsJcl(List<Statement> statements, JclModelListener listener) {
        for (Statement statement : statements) {
            if (statement.kind() != Kind.DATA) {
                continue;
            }
            for (int line = statement.line(); line <= statement.endLine(); line++) {
                if (listener.modelledLines().contains(line)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** One statement the parser could not read, at the line it stands on. */
    private static Finding syntaxDiagnostic(String path, int line, int column, String message) {
        String reason = message.replace("\\n", " ").replace("\\r", " ")
                .replaceAll("\\s+", " ").strip();
        return Finding.of(Finding.JCL_SYNTAX_RULE_ID, FindingLevel.WARNING,
                line + " 行の JCL 文を解析できませんでした（"
                        + SchedulerMarkers.head(reason, MAX_MESSAGE_LENGTH) + "）。",
                new SourcePosition(path, Math.max(1, line), Math.max(1, column),
                        SourcePosition.UNKNOWN_BYTE_OFFSET));
    }

    /** One note about the source, at the line it belongs to. */
    private static Finding diagnostic(String path, int line, String message) {
        return Finding.of(Finding.JCL_SYNTAX_RULE_ID, FindingLevel.WARNING, message,
                new SourcePosition(path, Math.max(1, line), 1,
                        SourcePosition.UNKNOWN_BYTE_OFFSET));
    }

    /**
     * Puts the scheduler tokens back into every string of the finished steps. The fields a DD
     * reads out of its parameters are built again from the restored map, so the data set name a
     * reader sees and the parameter it came from say the same thing.
     */
    private static List<JclStep> restore(SchedulerMarkers markers, List<JclStep> steps) {
        if (markers.isEmpty()) {
            return steps;
        }
        List<JclStep> out = new ArrayList<>();
        for (JclStep step : steps) {
            Map<String, String> parameters = restoreValues(markers, step.parameters());
            out.add(new JclStep(markers.restore(step.name()), step.execKind(),
                    markers.restore(step.target()), step.condition().map(markers::restore),
                    restoreDds(markers, step.ddStatements()), parameters,
                    Optional.ofNullable(parameters.get(PARM)).map(JclParameters::unquote),
                    step.procStepName().map(markers::restore), step.utility(), step.position()));
        }
        return out;
    }

    private static List<JclDdStatement> restoreDds(SchedulerMarkers markers,
            List<JclDdStatement> dds) {
        if (markers.isEmpty()) {
            return dds;
        }
        List<JclDdStatement> out = new ArrayList<>();
        for (JclDdStatement dd : dds) {
            out.add(Expansion.ddStatement(markers.restore(dd.ddName()),
                    restoreValues(markers, dd.parameters()), dd.inStreamData(), dd.concatIndex(),
                    dd.position()));
        }
        return out;
    }

    private static Map<String, String> restoreValues(SchedulerMarkers markers,
            Map<String, String> parameters) {
        if (markers.isEmpty() || parameters.isEmpty()) {
            return parameters;
        }
        Map<String, String> out = new LinkedHashMap<>();
        parameters.forEach((keyword, value) -> out.put(keyword, markers.restore(value)));
        return out;
    }

    /**
     * The overrides nothing could be done with, quoting the author's own text. The name an override
     * is reported by is the name the statement writes, so a scheduler token in it belongs to the
     * reader the same way it does in a diagnostic.
     */
    private static List<JclOverrideMiss> restoreOverrides(SchedulerMarkers markers,
            List<JclOverrideMiss> misses) {
        if (markers.isEmpty()) {
            return misses;
        }
        return misses.stream()
                .map(miss -> new JclOverrideMiss(miss.line(), markers.restore(miss.text())))
                .toList();
    }

    /** The members the library does not hold, named the way the statement names them. */
    private static List<JclMemberMiss> restoreMembers(SchedulerMarkers markers,
            List<JclMemberMiss> misses) {
        if (markers.isEmpty()) {
            return misses;
        }
        return misses.stream().map(miss -> new JclMemberMiss(miss.line(), miss.kind(),
                markers.restore(miss.name()))).toList();
    }

    /** The referbacks nothing could be resolved against, quoting the author's own text. */
    private static List<JclReferbackMiss> restoreReferbacks(SchedulerMarkers markers,
            List<JclReferbackMiss> misses) {
        if (markers.isEmpty()) {
            return misses;
        }
        return misses.stream()
                .map(miss -> new JclReferbackMiss(miss.line(), markers.restore(miss.text())))
                .toList();
    }

    private static Map<String, Map<String, String>> restoreOutputStatements(
            SchedulerMarkers markers, Map<String, Map<String, String>> statements) {
        if (markers.isEmpty() || statements.isEmpty()) {
            return statements;
        }
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        statements.forEach((name, parameters) ->
                out.put(markers.restore(name), restoreValues(markers, parameters)));
        return out;
    }

    /**
     * Replaces {@code &NAME} and {@code &NAME.} with the value the scope holds. A symbol the scope
     * does not know is left as written, and {@code &&NAME} is a deferred system symbol, not ours.
     */
    static String resolveSymbols(String text, Map<String, String> scope) {
        return resolveSymbols(text, scope, null);
    }

    /**
     * The same, telling {@code unresolved} the name of every symbol the scope does not know. A
     * substring of a symbol ({@code &SYSCLONE(2:1)}) is left alone: JCL substitutes it at run time
     * and the scope has no way to cut it.
     */
    static String resolveSymbols(String text, Map<String, String> scope,
            Consumer<String> unresolved) {
        if (text == null || text.indexOf('&') < 0) {
            return text;
        }
        Matcher matcher = SYMBOL.matcher(text);
        StringBuilder out = new StringBuilder();
        int copied = 0;
        while (matcher.find()) {
            if (matcher.start() > 0 && text.charAt(matcher.start() - 1) == '&'
                    || substringed(text, matcher.end()) || nameCharAt(text, matcher.end(1))) {
                continue;
            }
            String value = scope.get(matcher.group(1));
            if (value == null) {
                if (unresolved != null) {
                    unresolved.accept(matcher.group(1));
                }
                continue;
            }
            out.append(text, copied, matcher.start()).append(value);
            copied = matcher.end();
        }
        return out.append(text, copied, text.length()).toString();
    }

    /** Whether a substring notation stands where the symbol ends. */
    private static boolean substringed(String text, int at) {
        Matcher matcher = SUBSTRING.matcher(text);
        return matcher.find(at) && matcher.start() == at;
    }

    /**
     * Whether a character that may stand inside a symbol's name stands at that offset. Where one
     * does after the eighth character of a match, the name is longer than JCL allows a symbol's to
     * be, and what stands there is not a symbol whose first eight characters happen to be known.
     */
    private static boolean nameCharAt(String text, int at) {
        if (at < 0 || at >= text.length()) {
            return false;
        }
        char c = text.charAt(at);
        return Character.isLetterOrDigit(c) || c == '@' || c == '#' || c == '$';
    }

    /** Expands one job into steps, reading catalogued PROCs and INCLUDE members on the way. */
    private static final class Expansion {

        /** Why an expansion was left undone, as the diagnostic puts it. */
        private static final String SELF_CALL = "入れ子で自分自身を呼び出す";
        private static final String TOO_DEEP = "入れ子が深すぎる";

        private final JclMemberResolver members;
        private final SchedulerMarkers markers;
        private final boolean forceSegmented;
        /** Members walked for this file, shared by every job of it. */
        private final Map<String, Walk> loaded;
        private final Set<String> memberPaths = new LinkedHashSet<>();
        private final List<Finding> diagnostics = new ArrayList<>();
        /** The members being expanded, so one that calls itself is stopped at the second turn. */
        private final Deque<String> expanding = new ArrayDeque<>();
        /** Symbols nothing gave a value to, once each and in the order they were met. */
        private final Set<String> unresolvedSymbols = new LinkedHashSet<>();
        /** Overrides the expansion had no step or no DD to put. */
        private final List<JclOverrideMiss> unresolvedOverrides = new ArrayList<>();
        private final List<JclMemberMiss> missingMembers = new ArrayList<>();

        Expansion(JclMemberResolver members, SchedulerMarkers markers, boolean forceSegmented,
                Map<String, Walk> loaded) {
            this.members = members;
            this.markers = markers;
            this.forceSegmented = forceSegmented;
            this.loaded = loaded;
        }

        List<String> members() {
            return List.copyOf(memberPaths);
        }

        List<Finding> diagnostics() {
            return diagnostics;
        }

        List<String> unresolvedSymbols() {
            return List.copyOf(unresolvedSymbols);
        }

        List<JclOverrideMiss> unresolvedOverrides() {
            return List.copyOf(unresolvedOverrides);
        }

        List<JclMemberMiss> missingMembers() {
            return List.copyOf(missingMembers);
        }

        /**
         * A member the library does not hold, or holds and nothing could read. The expansion carries
         * on without it, so this is what the model has instead of the member's own statements: one
         * entry for each statement that named it, whether or not another statement named it too.
         */
        private void missing(String kind, String member, int line) {
            missingMembers.add(new JclMemberMiss(line, kind, member));
        }

        List<JclStep> expandItems(List<RawItem> items, String namePrefix, String file,
                Map<String, String> scope, Map<String, RawProc> procs, int depth) {
            List<JclStep> out = new ArrayList<>();
            Map<String, RawProc> visible = procs;
            for (RawItem item : items) {
                if (item instanceof RawStep step) {
                    expandStep(out, step, namePrefix, file, scope, visible, depth);
                } else if (item instanceof RawInclude include) {
                    if (depth >= MAX_PROC_DEPTH) {
                        notExpanded(include.member(), include.line(), file, TOO_DEEP);
                        continue;
                    }
                    String name = resolve(include.member(), scope);
                    JclModelListener member = load(name, include.line(), file);
                    if (member == null) {
                        missing(JclMemberMiss.INCLUDE, name, include.line());
                        continue;
                    }
                    if (enter(name, include.line(), file)) {
                        used(member);
                        scope.putAll(member.symbols());
                        // A PROC the member defines stands for the statements after the INCLUDE
                        // as well, the way an in-stream PROC written there would.
                        visible = merge(visible, member.procs());
                        out.addAll(expandItems(member.items(), namePrefix, member.sourceFile(),
                                scope, visible, depth + 1));
                        expanding.pop();
                    }
                }
            }
            return out;
        }

        private void expandStep(List<JclStep> out, RawStep step, String namePrefix, String file,
                Map<String, String> scope, Map<String, RawProc> procs, int depth) {
            String name = namePrefix.isEmpty() ? step.name() : namePrefix + "." + step.name();
            Optional<String> procStepName =
                    namePrefix.isEmpty() ? Optional.empty() : Optional.of(step.name());
            String target = resolve(step.target(), scope);
            List<JclDdStatement> dds = ddStatements(step.ddStatements(), file, scope);
            // An INCLUDE written after an EXEC adds the member's DD statements to that step; the
            // steps the member holds, if any, follow the whole of what the EXEC itself runs.
            List<JclStep> included = new ArrayList<>();
            for (String memberName : step.includes()) {
                String resolved = resolve(memberName, scope);
                JclModelListener member = load(resolved, step.line(), file);
                if (member == null) {
                    missing(JclMemberMiss.INCLUDE, resolved, step.line());
                    continue;
                }
                if (!enter(resolved, step.line(), file)) {
                    continue;
                }
                used(member);
                dds.addAll(ddStatements(member.memberDds(), member.sourceFile(), scope));
                if (depth < MAX_PROC_DEPTH) {
                    included.addAll(expandItems(member.items(), namePrefix, member.sourceFile(),
                            scope, merge(procs, member.procs()), depth + 1));
                } else {
                    notExpanded(resolved, step.line(), file, TOO_DEEP);
                }
                expanding.pop();
            }
            Map<String, String> parameters = resolveValues(step.parameters(), scope);
            // The DD statements written under an EXEC PGM are the step's own. Under an EXEC that
            // calls a PROC they are overrides of the PROC's steps, and belong to the call step
            // only while the PROC is not expanded and there is nowhere else to put them.
            Call call = step.execPgm() ? Call.NONE
                    : expandCall(step, name, target, file, scope, procs, depth);
            boolean expanded = call.expanded();
            Applied applied = expanded
                    ? applyOverrides(call.body(), name, dds, parameters, step.line())
                    : new Applied(List.of(), List.of());
            if (!expanded && !step.execPgm()) {
                unplaced(dds, parameters, step.line());
            }
            out.add(new JclStep(name, step.execPgm() ? JclExecKind.PGM : JclExecKind.PROC, target,
                    Optional.ofNullable(step.condition()),
                    expanded ? applied.unplaced() : dds, parameters,
                    Optional.ofNullable(parameters.get(PARM)).map(JclParameters::unquote),
                    procStepName, Optional.empty(), position(file, step.line())));
            // The steps of the PROC run where the EXEC that calls it stands, so they come before
            // the steps an INCLUDE written under that EXEC contributes.
            out.addAll(applied.steps());
            out.addAll(included);
        }

        /**
         * Every override of a PROC call nothing expanded. The rows stay where the author wrote
         * them, on the call step, so the model still says what the job asked for; each of them is
         * recorded as a miss because no step of a PROC took it.
         */
        private void unplaced(List<JclDdStatement> overrides, Map<String, String> parameters,
                int line) {
            for (JclDdStatement override : overrides) {
                if (override.ddName().indexOf('.') >= 0) {
                    unresolvedOverrides.add(
                            new JclOverrideMiss(override.position().line(), override.ddName()));
                }
            }
            for (String keyword : parameters.keySet()) {
                if (keyword.indexOf('.') >= 0) {
                    unresolvedOverrides.add(new JclOverrideMiss(line, keyword));
                }
            }
        }

        /**
         * What a PROC call expanded to. {@code expanded} says a PROC was found and read, which is
         * what tells the caller its DD statements were overrides rather than DD statements of its
         * own; a PROC of no steps at all is expanded all the same.
         */
        private record Call(boolean expanded, List<JclStep> body) {

            static final Call NONE = new Call(false, List.of());
        }

        /** Reads the PROC a call step invokes, and expands its body under the call step's name. */
        private Call expandCall(RawStep step, String name, String target, String file,
                Map<String, String> scope, Map<String, RawProc> procs, int depth) {
            if (depth >= MAX_PROC_DEPTH) {
                notExpanded(target, step.line(), file, TOO_DEEP);
                return Call.NONE;
            }
            RawProc proc = procs.get(target);
            Map<String, RawProc> visible = procs;
            if (proc == null) {
                JclModelListener source = load(target, step.line(), file);
                if (source == null) {
                    missing(JclMemberMiss.PROC, target, step.line());
                    return Call.NONE;
                }
                // The member was read, so it belongs to this job whether or not it turned out to
                // hold the PROC: reading it again for its own diagnostics would report it twice.
                used(source);
                proc = source.procNamed(target);
                if (proc == null) {
                    return Call.NONE;
                }
                visible = merge(procs, source.procs());
                scope = merge(scope, source.symbols());
            }
            if (!enter(target, step.line(), file)) {
                return Call.NONE;
            }
            Map<String, String> inner = new LinkedHashMap<>(scope);
            proc.defaults().forEach((key, value) -> inner.put(key, resolve(value, inner)));
            Map<String, String> caller = scope;
            step.procArguments().forEach((key, value) -> inner.put(key, resolve(value, caller)));
            List<JclStep> body =
                    expandItems(proc.items(), name, proc.sourceFile(), inner, visible, depth + 1);
            expanding.pop();
            return new Call(true, body);
        }

        /**
         * The PROC's steps with the call's overrides written into them.
         *
         * <p>A DD override qualified {@code procstep.} names the step it belongs to; an unqualified
         * one names the PROC's first step, and where that step is itself a PROC call the override
         * carries on down into what that call expanded to. The override's keywords replace or add
         * to the DD's own, so a DSN override on its own leaves the DISP the PROC coded. A DD the
         * PROC does not carry is an addition; a concatenation of overrides is matched against the
         * concatenation of the DD it names entry by entry.
         *
         * <p>An EXEC keyword qualified {@code .procstep} replaces that step's parameter. An
         * unqualified one follows the JCL Reference: PARM reaches the PROC's first step and
         * nullifies the PARM of every other, while every other keyword reaches every step of the
         * PROC. An override naming a step that is not there is recorded as a miss; a DD override
         * among them stays on the call step, where the author wrote it, so the model still says
         * what the job asked for.
         */
        private Applied applyOverrides(List<JclStep> body, String callName,
                List<JclDdStatement> overrides, Map<String, String> parameters, int line) {
            List<JclStep> steps = new ArrayList<>(body);
            // Only the PROC's own steps may be overridden: a step of a PROC it calls in turn is
            // one level too far down, which is what the extra dot in its name says.
            Map<String, Integer> directAt = new LinkedHashMap<>();
            for (int at = 0; at < steps.size(); at++) {
                String stepName = steps.get(at).name();
                if (stepName.startsWith(callName + ".")
                        && stepName.indexOf('.', callName.length() + 1) < 0) {
                    directAt.put(stepName.substring(callName.length() + 1), at);
                }
            }
            List<Integer> direct = List.copyOf(directAt.values());
            parameters.forEach((keyword, value) -> {
                int dot = keyword.indexOf('.');
                if (dot >= 0) {
                    Integer at = directAt.get(keyword.substring(dot + 1));
                    if (at == null) {
                        unresolvedOverrides.add(new JclOverrideMiss(line, keyword));
                        return;
                    }
                    steps.set(at, withParameter(steps.get(at), keyword.substring(0, dot), value));
                    return;
                }
                if (!OVERRIDABLE_KEYWORDS.contains(keyword.toUpperCase(Locale.ROOT))) {
                    // A symbolic argument of the PROC, which opened its scope and is no override.
                    return;
                }
                if (direct.isEmpty()) {
                    unresolvedOverrides.add(new JclOverrideMiss(line, keyword));
                    return;
                }
                if (keyword.equalsIgnoreCase(PARM)) {
                    // The PROC's first step takes it, and where that step calls a PROC of its own
                    // the PARM carries on down to the first step that runs a program.
                    Integer first = unqualifiedTarget(steps, direct);
                    if (first == null) {
                        unresolvedOverrides.add(new JclOverrideMiss(line, keyword));
                        return;
                    }
                    steps.set(first, withParameter(steps.get(first), PARM, value));
                    direct.subList(1, direct.size())
                            .forEach(at -> steps.set(at, withoutParm(steps.get(at))));
                    return;
                }
                direct.forEach(at -> steps.set(at, withParameter(steps.get(at), keyword, value)));
            });
            // The overrides of one DD are written together, the concatenation entries under the
            // named one included, so they are applied together as the group they are.
            Map<String, List<JclDdStatement>> groups = new LinkedHashMap<>();
            for (JclDdStatement override : overrides) {
                groups.computeIfAbsent(override.ddName(), key -> new ArrayList<>()).add(override);
            }
            List<JclDdStatement> unplaced = new ArrayList<>();
            groups.forEach((written, group) -> {
                int dot = written.indexOf('.');
                Integer at = dot < 0 ? unqualifiedTarget(steps, direct)
                        : directAt.get(written.substring(0, dot));
                if (at == null) {
                    group.forEach(override -> unresolvedOverrides.add(
                            new JclOverrideMiss(override.position().line(), override.ddName())));
                    unplaced.addAll(group);
                    return;
                }
                steps.set(at, withDds(steps.get(at), written.substring(dot + 1), group));
            });
            return new Applied(steps, unplaced);
        }

        /**
         * What the overrides of one PROC call came to: the PROC's steps, and the DD overrides no
         * step of it could take, which stay on the call step under the name the author wrote.
         */
        private record Applied(List<JclStep> steps, List<JclDdStatement> unplaced) {
        }

        /**
         * Where an unqualified DD override goes: the PROC's first step, and where that step calls a
         * PROC of its own, the first step of what that call expanded to. A DD statement cannot be
         * overridden onto a step that runs no program.
         */
        private static Integer unqualifiedTarget(List<JclStep> steps, List<Integer> direct) {
            if (direct.isEmpty()) {
                return null;
            }
            int at = direct.get(0);
            while (steps.get(at).execKind() == JclExecKind.PROC) {
                String prefix = steps.get(at).name() + ".";
                int nested = -1;
                for (int below = at + 1; below < steps.size() && nested < 0; below++) {
                    String name = steps.get(below).name();
                    if (name.startsWith(prefix) && name.indexOf('.', prefix.length()) < 0) {
                        nested = below;
                    }
                }
                if (nested < 0) {
                    return null;
                }
                at = nested;
            }
            return at;
        }

        /** One EXEC parameter of a PROC step replaced by the value the call wrote for it. */
        private static JclStep withParameter(JclStep step, String keyword, String value) {
            Map<String, String> parameters = new LinkedHashMap<>(step.parameters());
            parameters.put(keyword, value);
            Optional<String> condition = keyword.equalsIgnoreCase(COND)
                    ? Optional.of(COND + "=" + value) : step.condition();
            return withParameters(step, condition, parameters, step.ddStatements());
        }

        /** A step whose PARM an unqualified PARM override on the call statement nullified. */
        private static JclStep withoutParm(JclStep step) {
            if (!step.parameters().containsKey(PARM)) {
                return step;
            }
            Map<String, String> parameters = new LinkedHashMap<>(step.parameters());
            parameters.remove(PARM);
            return withParameters(step, step.condition(), parameters, step.ddStatements());
        }

        /**
         * One DD of a PROC step, and the concatenation under it, with the call's overrides written
         * in. The JCL Reference matches the overrides against the concatenation entry by entry, in
         * the order both are written: override i is merged into entry i, an entry no override was
         * written for stands as the PROC coded it, and an override past the last entry is
         * concatenated after it. A DD the PROC does not carry is added after the ones it does, its
         * own concatenation with it.
         *
         * <p>Each merged DD stands where the statement a reader would go to stands. An override
         * that supplied the data set name or the disposition says what the DD reads or writes, so
         * the row takes the override's line; an override of anything else leaves the row on the
         * PROC's line, which is still where the data set is named.
         */
        private static JclStep withDds(JclStep step, String ddName,
                List<JclDdStatement> overrides) {
            List<JclDdStatement> dds = new ArrayList<>(step.ddStatements());
            int at = -1;
            for (int i = 0; i < dds.size() && at < 0; i++) {
                if (dds.get(i).ddName().equals(ddName) && dds.get(i).concatIndex() == 0) {
                    at = i;
                }
            }
            if (at < 0) {
                for (int i = 0; i < overrides.size(); i++) {
                    JclDdStatement override = overrides.get(i);
                    dds.add(ddStatement(ddName, override.parameters(), override.inStreamData(), i,
                            override.position()));
                }
                return withParameters(step, step.condition(), step.parameters(), dds);
            }
            int end = at + 1;
            while (end < dds.size() && dds.get(end).ddName().equals(ddName)
                    && dds.get(end).concatIndex() > 0) {
                end++;
            }
            List<JclDdStatement> written = new ArrayList<>();
            for (int i = 0; i < Math.max(end - at, overrides.size()); i++) {
                JclDdStatement entry = at + i < end ? dds.get(at + i) : null;
                JclDdStatement override = i < overrides.size() ? overrides.get(i) : null;
                if (override == null) {
                    // No overriding statement was written for this entry, so it stands unchanged.
                    written.add(entry);
                    continue;
                }
                boolean namesTheData = JclParameters
                        .value(override.parameters(), JclParameters.DSN_KEYWORDS).isPresent()
                        || JclParameters.value(override.parameters(), "DISP").isPresent();
                written.add(ddStatement(ddName,
                        entry == null ? override.parameters()
                                : JclParameters.overridden(entry.parameters(),
                                        override.parameters()),
                        entry == null || !override.inStreamData().isEmpty()
                                ? override.inStreamData() : entry.inStreamData(),
                        i, entry == null || namesTheData
                                ? override.position() : entry.position()));
            }
            dds.subList(at, end).clear();
            dds.addAll(at, written);
            return withParameters(step, step.condition(), step.parameters(), dds);
        }

        /** The same step with those parameters and those DD statements, and nothing else changed. */
        private static JclStep withParameters(JclStep step, Optional<String> condition,
                Map<String, String> parameters, List<JclDdStatement> dds) {
            return new JclStep(step.name(), step.execKind(), step.target(), condition, dds,
                    parameters, Optional.ofNullable(parameters.get(PARM))
                            .map(JclParameters::unquote),
                    step.procStepName(), step.utility(), step.position());
        }

        List<JclDdStatement> ddStatements(List<RawDd> raw, String file,
                Map<String, String> scope) {
            List<JclDdStatement> out = new ArrayList<>();
            for (RawDd dd : raw) {
                out.add(ddStatement(resolve(dd.ddName(), scope),
                        resolveValues(dd.parameters(), scope), dd.inStreamData(), dd.concatIndex(),
                        position(file, dd.line())));
            }
            return out;
        }

        /**
         * A DD statement built out of its parameters: the data set, the disposition, the SYSOUT
         * operand, whether it is a DUMMY and what it points back at all come from the same map, so
         * an override that changes the map changes every one of them.
         *
         * <p>{@code DSN=NULLFILE} names no data set at all, which is what DUMMY says too, and a DD
         * that says either of them holds no data set the model can name: neither the name nor the
         * data set is filled in, so nothing downstream takes NULLFILE for a data set of the site.
         * An override coding a DSN over a PROC's DUMMY says the DD does name one after all, so the
         * DUMMY keyword the merged map still carries no longer stands.
         */
        static JclDdStatement ddStatement(String ddName, Map<String, String> parameters,
                List<String> inStreamData, int concatIndex, SourcePosition position) {
            Optional<String> dsn = JclParameters.value(parameters, JclParameters.DSN_KEYWORDS)
                    .map(JclParameters::unquote);
            Optional<String> disp = JclParameters.value(parameters, "DISP");
            boolean nullfile = dsn.filter(JclParameters.NULLFILE::equalsIgnoreCase).isPresent();
            boolean dummy = nullfile || parameters.containsKey("DUMMY") && dsn.isEmpty();
            dsn = nullfile ? Optional.empty() : dsn;
            return new JclDdStatement(ddName, dsn, dsn.flatMap(JclParameters::dataset), disp,
                    disp.flatMap(JclParameters::disposition),
                    JclParameters.value(parameters, "SYSOUT"), dummy, parameters,
                    JclParameters.referbacks(parameters), inStreamData, concatIndex, position);
        }

        /** Every value of a parameter map with the symbols of that scope resolved in it. */
        Map<String, String> resolveValues(Map<String, String> parameters,
                Map<String, String> scope) {
            Map<String, String> out = new LinkedHashMap<>();
            parameters.forEach((keyword, value) -> out.put(keyword, resolve(value, scope)));
            return out;
        }

        /** The OUTPUT statements of a job with the symbols of that scope resolved in them. */
        Map<String, Map<String, String>> resolveOutputStatements(
                Map<String, Map<String, String>> statements, Map<String, String> scope) {
            Map<String, Map<String, String>> out = new LinkedHashMap<>();
            statements.forEach((name, parameters) ->
                    out.put(resolve(name, scope), resolveValues(parameters, scope)));
            return out;
        }

        /** Resolves the symbols of one string, taking note of those the scope cannot answer. */
        private String resolve(String text, Map<String, String> scope) {
            return resolveSymbols(text, scope, this::unresolved);
        }

        /**
         * One symbol nothing gave a value to. A system symbol z/OS sets itself, a scheduler token
         * the pre-pass put a name in place of, and a variable a scheduler directive declares are
         * none of them missing: no SET statement of the job is meant to set any of them.
         */
        private void unresolved(String name) {
            String symbol = "&" + name;
            if (!SYSTEM_SYMBOLS.contains(symbol) && !markers.declares(name)
                    && markers.restore(symbol).equals(symbol)) {
                unresolvedSymbols.add(symbol);
            }
        }

        /** Takes note that a member's content reached the model, which is what an edge records. */
        private void used(JclModelListener member) {
            memberPaths.add(member.sourceFile());
        }

        /** Whether the member may be expanded here, or is already being expanded further up. */
        private boolean enter(String member, int line, String file) {
            if (expanding.contains(member)) {
                notExpanded(member, line, file, SELF_CALL);
                return false;
            }
            expanding.push(member);
            return true;
        }

        /** One expansion left undone, which the model then does without. */
        private void notExpanded(String member, int line, String file, String reason) {
            diagnostics.add(Finding.of(Finding.JCL_SYNTAX_RULE_ID, FindingLevel.WARNING,
                    line + " 行のメンバー " + markers.restore(member) + " は" + reason
                            + "ため展開しません。",
                    position(file, line)));
        }

        /** Reads a catalogued PROC or an INCLUDE member through the resolver, once per file. */
        private JclModelListener load(String member, int line, String file) {
            if (loaded.containsKey(member)) {
                Walk cached = loaded.get(member);
                return cached == null ? null : cached.listener();
            }
            Walk walk = null;
            try {
                Optional<DecodedSource> found = members.resolve(member);
                if (found.isPresent()) {
                    walk = walk(found.get().text(), found.get().path(), markers, forceSegmented);
                    diagnostics.addAll(walk.findings());
                }
            } catch (RuntimeException e) {
                // An unreadable member leaves the call unexpanded rather than failing the whole
                // job: the steps written in the job itself are still worth having.
                walk = null;
            }
            loaded.put(member, walk);
            return walk == null ? null : walk.listener();
        }

        private static <V> Map<String, V> merge(Map<String, V> base, Map<String, V> added) {
            Map<String, V> out = new LinkedHashMap<>(base);
            out.putAll(added);
            return out;
        }

        private static SourcePosition position(String file, int line) {
            return new SourcePosition(file, Math.max(1, line), 1,
                    SourcePosition.UNKNOWN_BYTE_OFFSET);
        }
    }
}
