package jp.cobolinsight.app.pipeline;

import jp.cobolinsight.app.EngineWiring;
import jp.cobolinsight.core.easytrieve.EasytrieveProgram;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.core.jcl.JclDdStatement;
import jp.cobolinsight.core.jcl.JclExecKind;
import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.jcl.JclStep;
import jp.cobolinsight.core.jcl.JclUtilityFacts;
import jp.cobolinsight.core.jcl.JclUtilityFacts.DatasetAccess;
import jp.cobolinsight.core.jcl.JclUtilityFacts.ProgramRun;
import jp.cobolinsight.core.jcl.JclUtilityFacts.TableUse;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.semantic.EmbeddedBlock;
import jp.cobolinsight.core.semantic.EmbeddedBlockKind;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.DecodedSource;
import jp.cobolinsight.core.spi.ParseOutcome;
import jp.cobolinsight.core.spi.SqlParser;
import jp.cobolinsight.core.sql.SqlStatementModel;
import jp.cobolinsight.frontend.easytrieve.EasytrieveSourceParser;
import jp.cobolinsight.frontend.easytrieve.EasytrieveSourceParser.MacroResolver;
import jp.cobolinsight.frontend.easytrieve.EasytrieveSourceParser.SourceLine;
import jp.cobolinsight.frontend.ims.ImsSourceParser;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The parsing of the assets that are neither COBOL, JCL, SQL nor BMS: Easytrieve programs, in a
 * member of their own or in-stream under a JCL step, and the IMS DBD and PSB generations.
 *
 * <p>An Easytrieve program a step hands the compiler in-stream belongs to the JCL file it stands
 * in. It is read where the step is, its statements keep the lines of the JCL, and what it states —
 * the files it reads and writes, the programs it calls, the tables its SQL touches — is written
 * onto the step, the way the control cards of a utility are.
 */
final class MiddlewareParse {

    /** The EXEC targets that compile and run the Easytrieve source on SYSIN. */
    private static final Set<String> EASYTRIEVE_COMPILE_AND_GO = Set.of("EZTPA00");

    private static final String EASYTRIEVE_SOURCE_DD = "SYSIN";

    private final SourceSet set;
    private final EasytrieveSourceParser easytrieve = EngineWiring.easytrieveParser();
    private final ImsSourceParser ims = EngineWiring.imsParser();
    /** The Easytrieve members of the walk by member name, which is how a {@code %NAME} asks. */
    private final Map<String, List<SourceUnit>> membersByName = new HashMap<>();

    MiddlewareParse(SourceSet set) {
        this.set = set;
        for (SourceUnit unit : set.unitsOf(AssetKind.EASYTRIEVE)) {
            membersByName.computeIfAbsent(
                    EasytrieveSourceParser.memberNameOf(unit.fileName()),
                    key -> new ArrayList<>()).add(unit);
        }
    }

    // ---- Easytrieve members ----

    void parseEasytrieve(SourceUnit unit, DecodedSource decoded) {
        EasytrieveProgram program;
        try {
            program = easytrieve.parse(decoded, macrosFor(unit.absPath().getParent()));
        } catch (RuntimeException e) {
            unreadable(unit, e);
            return;
        }
        set.easytrieveByPath().put(unit.relPath(), List.of(program));
        readSql(unit.relPath(), program);
    }

    /**
     * Records a source a frontend gave up on. These frontends read statement by statement and
     * refuse nothing by design, so this is a defect of the reader rather than of the source; the
     * asset is reported and set aside so that the run carries on with every other one.
     */
    private void unreadable(SourceUnit unit, RuntimeException cause) {
        String message = "解析に失敗しました: " + Failures.describe(cause);
        set.addFinding(unit.relPath(), Finding.parseFailure(
                jp.cobolinsight.core.source.SourcePosition.fileStart(unit.relPath()), message));
        set.unanalyzable().put(unit.relPath(), message);
    }

    /**
     * The macro members a program may invoke: an Easytrieve member of the walk named like the
     * macro, the one in the invoking source's own directory first.
     */
    private MacroResolver macrosFor(Path directory) {
        return name -> {
            DecodedSource anywhere = null;
            for (SourceUnit candidate : membersByName.getOrDefault(
                    name.toUpperCase(Locale.ROOT), List.of())) {
                DecodedSource decoded = set.decoded().get(candidate.relPath());
                if (decoded == null) {
                    continue;
                }
                if (directory != null && directory.equals(candidate.absPath().getParent())) {
                    anywhere = decoded;
                    break;
                }
                if (anywhere == null) {
                    anywhere = decoded;
                }
            }
            return Optional.ofNullable(anywhere).map(decoded ->
                    EasytrieveSourceParser.linesOf(decoded.text(), decoded.path()));
        };
    }

    /**
     * Reads the SQL statements of a program with the frontend an {@code EXEC SQL} block goes
     * through, and files them under {@code relPath}. A statement the grammar reads only in part is
     * kept and reported where it stands, as it is for a program and for a script.
     */
    private List<SqlStatementModel> readSql(String relPath, EasytrieveProgram program) {
        List<SqlStatementModel> statements = new ArrayList<>();
        if (!set.requires(Needs.SQL) || program.sql().isEmpty()) {
            return statements;
        }
        SqlParser parser = EngineWiring.sqlParser();
        for (EasytrieveProgram.SqlBlock block : program.sql()) {
            ParseOutcome<SqlStatementModel> outcome = parser.parse(new EmbeddedBlock(
                    EmbeddedBlockKind.SQL, block.text(), Map.of(), block.range()));
            if (outcome instanceof ParseOutcome.Failure<SqlStatementModel> failure) {
                set.addFinding(relPath, failure.finding());
                continue;
            }
            SqlStatementModel statement = outcome.value().orElseThrow();
            if (!statement.isFullyAnalysed()) {
                set.addFinding(relPath, Finding.of(Finding.SQL_SYNTAX_RULE_ID,
                        FindingLevel.WARNING,
                        "SQL 文を完全には解析できませんでした（"
                                + statement.diagnostic().orElse("理由は不明です")
                                + "）。文の種別と表名だけを使います。",
                        statement.range().start()));
            }
            statements.add(statement);
        }
        if (!statements.isEmpty()) {
            set.sqlByPath().computeIfAbsent(relPath, key -> new ArrayList<>())
                    .addAll(statements);
        }
        return statements;
    }

    // ---- Easytrieve in-stream under a JCL step ----

    /**
     * The jobs of one JCL file with every in-stream Easytrieve program read and written onto the
     * step that runs it. A job with no such step comes back as it was given.
     */
    List<JclJobModel> withInStreamEasytrieve(SourceUnit unit, DecodedSource decoded,
            List<JclJobModel> jobs) {
        List<JclJobModel> out = new ArrayList<>();
        List<EasytrieveProgram> programs = new ArrayList<>();
        for (JclJobModel job : jobs) {
            List<JclStep> steps = new ArrayList<>();
            boolean changed = false;
            for (JclStep step : job.steps()) {
                List<SourceLine> source = inStreamSource(step, decoded);
                if (source.isEmpty()) {
                    steps.add(step);
                    continue;
                }
                EasytrieveProgram program;
                try {
                    program = easytrieve.parse(
                            job.jobName() + "." + step.name(), source.get(0).file(), source,
                            Optional.of(new EasytrieveProgram.Host(job.jobName(), step.name())),
                            macrosFor(unit.absPath().getParent()));
                } catch (RuntimeException e) {
                    // The step stays as the JCL alone describes it; only its program is lost.
                    set.addFinding(unit.relPath(), Finding.parseFailure(step.position(),
                            step.name() + " のストリーム内の Easytrieve を解析できませんでした: "
                                    + Failures.describe(e)));
                    steps.add(step);
                    continue;
                }
                programs.add(program);
                steps.add(withProgramFacts(step, program, readSql(unit.relPath(), program)));
                changed = true;
            }
            out.add(changed ? withSteps(job, steps) : job);
        }
        if (!programs.isEmpty()) {
            set.easytrieveByPath().put(unit.relPath(), programs);
        }
        return out;
    }

    /**
     * The Easytrieve source a step hands the compiler: the in-stream lines of its SYSIN, each with
     * the line of the file it stands on. Empty for a step that runs something else, or whose SYSIN
     * names a data set.
     */
    private List<SourceLine> inStreamSource(JclStep step, DecodedSource decoded) {
        if (step.execKind() != JclExecKind.PGM
                || !EASYTRIEVE_COMPILE_AND_GO.contains(step.target().toUpperCase(Locale.ROOT))) {
            return List.of();
        }
        List<SourceLine> lines = new ArrayList<>();
        for (JclDdStatement dd : step.ddStatements()) {
            if (!dd.ddName().equalsIgnoreCase(EASYTRIEVE_SOURCE_DD)
                    || dd.inStreamData().isEmpty()) {
                continue;
            }
            String file = dd.position().file();
            int first = firstDataLine(dd, textOf(file, decoded));
            for (int i = 0; i < dd.inStreamData().size(); i++) {
                lines.add(new SourceLine(dd.inStreamData().get(i), file, first + i));
            }
        }
        return lines;
    }

    /** The text of the file a DD stands in: the JCL itself, or the member the DD was expanded from. */
    private String textOf(String file, DecodedSource jcl) {
        if (file.equals(jcl.path())) {
            return jcl.text();
        }
        for (DecodedSource decoded : set.decoded().values()) {
            if (decoded.path().equals(file)) {
                return decoded.text();
            }
        }
        return null;
    }

    /**
     * The line the first in-stream record stands on. The DD statement may run over several lines,
     * so the record is looked for below the DD's own line rather than assumed to follow it.
     */
    private static int firstDataLine(JclDdStatement dd, String text) {
        int fallback = dd.position().line() + 1;
        if (text == null) {
            return fallback;
        }
        String[] lines = text.split("\\r\\n|\\r|\\n|\\u0085", -1);
        String wanted = dd.inStreamData().get(0).stripTrailing();
        for (int i = dd.position().line(); i < lines.length && i < dd.position().line() + 20;
                i++) {
            if (lines[i].stripTrailing().equals(wanted)) {
                return i + 1;
            }
        }
        return fallback;
    }

    /** The step with what its in-stream program states added to what its DD statements say. */
    private static JclStep withProgramFacts(JclStep step, EasytrieveProgram program,
            List<SqlStatementModel> sql) {
        JclUtilityFacts facts = step.utility().orElseGet(() -> new JclUtilityFacts(List.of(),
                List.of(), List.of(), List.of(), Map.of()));
        Map<String, DatasetAccess> roles = new LinkedHashMap<>(facts.ddRoles());
        Set<String> ddNames = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        step.ddStatements().forEach(dd -> ddNames.add(dd.ddName()));
        program.fileAccess().forEach((file, access) -> {
            if (ddNames.contains(file)) {
                // The program says what it does with the file; the DISP only says what it may.
                roles.put(file, DatasetAccess.valueOf(access));
            }
        });
        List<ProgramRun> runs = new ArrayList<>(facts.programRuns());
        for (EasytrieveProgram.Call call : program.calls()) {
            if (call.literal()) {
                runs.add(new ProgramRun(call.target(), Optional.empty(), Optional.empty(),
                        Optional.empty(), ProgramRun.EASYTRIEVE, Optional.empty()));
            }
        }
        List<TableUse> tables = new ArrayList<>(facts.tableUses());
        for (SqlStatementModel statement : sql) {
            statement.tableAccess().forEach((table, letters) -> {
                if (!table.equals("?")) {
                    tables.add(new TableUse(table, accessOf(letters)));
                }
            });
        }
        program.files().forEach(file -> file.tables().forEach(table ->
                tables.add(new TableUse(table, DatasetAccess.READ))));
        JclUtilityFacts merged = new JclUtilityFacts(runs, facts.binds(), facts.datasetUses(),
                tables, roles, facts.resourceDefinitions());
        return new JclStep(step.name(), step.execKind(), step.target(), step.condition(),
                step.ddStatements(), step.parameters(), step.parm(), step.procStepName(),
                merged.isEmpty() ? Optional.empty() : Optional.of(merged), step.position());
    }

    /** The access the letters of {@link SqlStatementModel#tableAccess()} come to. */
    private static DatasetAccess accessOf(String letters) {
        if (letters.equals("R")) {
            return DatasetAccess.READ;
        }
        if (letters.equals("C")) {
            return DatasetAccess.WRITE;
        }
        return letters.equals("D") ? DatasetAccess.DELETE : DatasetAccess.UPDATE;
    }

    private static JclJobModel withSteps(JclJobModel job, List<JclStep> steps) {
        return new JclJobModel(job.jobName(), job.sourceFile(), job.condition(), steps,
                job.diagnostics(), job.members(), job.jcllib(), job.schedulerVariables(),
                job.parameters(), job.joblib(), job.syschk(), job.jes2Cards(),
                job.unresolvedSymbols(), job.outputStatements(), job.unresolvedOverrides(),
                job.unresolvedReferbacks(), job.missingMembers(), job.position());
    }

    // ---- IMS ----

    void parseDbd(SourceUnit unit, DecodedSource decoded) {
        try {
            set.dbdsByPath().put(unit.relPath(), ims.parseDbd(decoded));
        } catch (RuntimeException e) {
            unreadable(unit, e);
        }
    }

    void parsePsb(SourceUnit unit, DecodedSource decoded) {
        try {
            set.psbsByPath().put(unit.relPath(), ims.parsePsb(decoded));
        } catch (RuntimeException e) {
            unreadable(unit, e);
        }
    }
}
