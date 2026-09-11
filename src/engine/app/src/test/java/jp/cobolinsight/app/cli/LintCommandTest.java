package jp.cobolinsight.app.cli;

import jp.cobolinsight.rules.RuleSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the lint subcommand's picocli wiring, SARIF file output, and exit-code branching. */
class LintCommandTest {

    @TempDir
    Path tempDir;

    private Path assets(String name, String cobolSource) throws IOException {
        Path dir = tempDir.resolve(name);
        Files.createDirectories(dir.resolve("cobol"));
        Files.writeString(dir.resolve("cobol").resolve(name.toUpperCase() + ".cbl"),
                cobolSource, StandardCharsets.UTF_8);
        return dir;
    }

    private static final String CLEAN = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID.  CLEAN1.",
            "       ENVIRONMENT DIVISION.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-COUNT                    PIC 9(03).",
            "       PROCEDURE DIVISION.",
            "       0000-MAIN.",
            "           MOVE 1 TO WS-COUNT",
            "           DISPLAY WS-COUNT",
            "           GOBACK.",
            "");

    /** An asset that reaches only warning level. Contains a paragraph called from nowhere (R011). */
    private static final String WARNING = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID.  WARN1.",
            "       ENVIRONMENT DIVISION.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-COUNT                    PIC 9(03).",
            "       PROCEDURE DIVISION.",
            "       0000-MAIN.",
            "           MOVE 1 TO WS-COUNT",
            "           DISPLAY WS-COUNT",
            "           GOBACK.",
            "       1000-STEP.",
            "           MOVE 2 TO WS-COUNT.",
            "");

    /** An asset containing an error. Contains an item with credentials hardcoded in a VALUE clause (R026). */
    private static final String ERROR = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID.  ERR1.",
            "       ENVIRONMENT DIVISION.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  API-KEY                     PIC X(08) VALUE 'PW12345'.",
            "       PROCEDURE DIVISION.",
            "       0000-MAIN.",
            "           DISPLAY API-KEY",
            "           GOBACK.",
            "");

    private static final String COPY_USER = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID.  COPYUSE.",
            "       ENVIRONMENT DIVISION.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "           COPY EXTC.",
            "       PROCEDURE DIVISION.",
            "       0000-MAIN.",
            "           GOBACK.",
            "");

    /** An external copybook whose REDEFINES outgrows the item it redefines (R015), so a finding lands at the copybook's location. */
    private static final String EXT_COPYBOOK = String.join("\n",
            "       01  EXT-AREA.",
            "           05  EXT-BASE                PIC X(08).",
            "           05  EXT-WIDE REDEFINES EXT-BASE PIC X(10).",
            "");

    @Test
    void cleanAssetsExitWithSuccessAndWriteSarifFile() throws IOException {
        Path dir = assets("clean", CLEAN);
        Path sarif = tempDir.resolve("clean.sarif");

        int exitCode = new CommandLine(new Main()).execute("lint", dir.toString(),
                "--sarif", sarif.toString(), "--sql-sarif", tempDir.resolve("clean-sql.sarif").toString());

        assertEquals(0, exitCode, "検出なしは成功(0)であること");
        assertTrue(Files.exists(sarif), "SARIFファイルが書き出されること");
        String json = Files.readString(sarif, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"version\":\"2.1.0\""));
        assertTrue(json.contains("\"results\":[]"));
    }

    @Test
    void warningLevelFindingsExitWithOne() throws IOException {
        Path dir = assets("warn", WARNING);

        int exitCode = new CommandLine(new Main()).execute("lint", dir.toString(),
                "--sarif", tempDir.resolve("warn.sarif").toString(),
                "--sql-sarif", tempDir.resolve("warn-sql.sarif").toString());

        assertEquals(1, exitCode, "警告あり=1であること");
    }

    /** Only the config file decides whether a rule is enabled or disabled. */
    @Test
    void rulesFileSuppressesItsFindings() throws IOException {
        Path dir = assets("warncfg", WARNING.replace("WARN1", "WARNCFG"));
        Path config = tempDir.resolve("rules.json");
        Files.writeString(config, "{\"version\": 2, \"rules\": {\"R011\": {\"enabled\": false}}}",
                StandardCharsets.UTF_8);

        int exitCode = new CommandLine(new Main()).execute("lint", dir.toString(),
                "--sarif", tempDir.resolve("warncfg.sarif").toString(),
                "--sql-sarif", tempDir.resolve("warncfg-sql.sarif").toString(),
                "--rules", config.toString());

        assertEquals(0, exitCode, "設定ファイルで R011 を無効化すると成功(0)になること");
    }

    /** The config file can list multiple entries. Confirms it does not stop after reading only the first. */
    @Test
    void rulesFileSuppressesEveryListedRule() throws IOException {
        Path dir = assets("warnmany", WARNING.replace("WARN1", "WARNMANY"));
        Path config = tempDir.resolve("rules-many.json");
        Files.writeString(config, "{\"version\": 2, \"rules\": {\"R001\": {\"enabled\": false},"
                        + " \"R011\": {\"enabled\": false}}}",
                StandardCharsets.UTF_8);

        int exitCode = new CommandLine(new Main()).execute("lint", dir.toString(),
                "--sarif", tempDir.resolve("warnmany.sarif").toString(),
                "--sql-sarif", tempDir.resolve("warnmany-sql.sarif").toString(),
                "--rules", config.toString());

        assertEquals(0, exitCode, "並べた R001・R011 のいずれも無効化されること");
    }

    /**
     * A config file that has not been created yet is treated as a config that disables nothing.
     * The GUI always passes --rules regardless of whether a config exists, so a freshly set-up
     * environment does not have the file yet.
     */
    @Test
    void missingRulesFileDisablesNothing() throws IOException {
        Path dir = assets("cfgmiss", CLEAN.replace("CLEAN1", "CFGMISS"));

        int exitCode = new CommandLine(new Main()).execute("lint", dir.toString(),
                "--sarif", tempDir.resolve("cfgmiss.sarif").toString(),
                "--sql-sarif", tempDir.resolve("cfgmiss-sql.sarif").toString(),
                "--rules", tempDir.resolve("absent.json").toString());

        assertEquals(0, exitCode, "指摘の無い資産は、設定ファイルが無くてもそのまま通ること");
    }

    @Test
    void copybookFindingIsRelativizedAgainstCopybookPath() throws IOException {
        Path dir = assets("copyuse", COPY_USER);
        Path external = tempDir.resolve("externalcpy");
        Files.createDirectories(external);
        Files.writeString(external.resolve("EXTC.cpy"), EXT_COPYBOOK, StandardCharsets.UTF_8);
        Path sarif = tempDir.resolve("copyuse.sarif");

        new CommandLine(new Main()).execute("lint", dir.toString(),
                "--sarif", sarif.toString(), "--sql-sarif", tempDir.resolve("copyuse-sql.sarif").toString(),
                "--copybook-path", external.toString());

        String json = Files.readString(sarif, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"ruleId\":\"R015\""), json);
        assertTrue(json.contains("\"uri\":\"EXTC.cpy\""),
                "--copybook-path のディレクトリ基準で相対化されること: " + json);
        assertTrue(!json.contains("externalcpy"),
                "コピー句の絶対パスがSARIFに漏れないこと: " + json);
    }

    /**
     * Two programs with the same seeded error, one per subfolder, so a scope decides which is
     * reported. The one in a/ calls the one in b/, which a scope of a/ leaves out of the run.
     */
    private Path twoFolderAssets(String name) throws IOException {
        Path dir = tempDir.resolve(name);
        Files.createDirectories(dir.resolve("a"));
        Files.createDirectories(dir.resolve("b"));
        Files.writeString(dir.resolve("a").resolve("SCOPEA.cbl"), ERROR.replace("ERR1", "SCOPEA")
                        .replace("           DISPLAY API-KEY",
                                "           CALL 'SCOPEB'\n           CALL 'NOSUCHPG'\n"
                                        + "           DISPLAY API-KEY"),
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("b").resolve("SCOPEB.cbl"), ERROR.replace("ERR1", "SCOPEB"),
                StandardCharsets.UTF_8);
        return dir;
    }

    @Test
    void scopeOfASubfolderAnalysesOnlyTheFilesUnderIt() throws IOException {
        Path dir = twoFolderAssets("scopedir");
        Path sarif = tempDir.resolve("scopedir.sarif");

        int exitCode = new CommandLine(new Main()).execute("lint", dir.toString(),
                "--sarif", sarif.toString(),
                "--sql-sarif", tempDir.resolve("scopedir-sql.sarif").toString(),
                "--scope", "a");

        assertEquals(2, exitCode, "範囲内の資産の指摘で終了コードが決まること");
        String json = Files.readString(sarif, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"uri\":\"a/SCOPEA.cbl\""), json);
        assertTrue(!json.contains("\"uri\":\"b/SCOPEB.cbl\""),
                "範囲外のファイルは解析しないこと: " + json);
        assertTrue(!json.contains("NOSUCHPG") && !json.contains("SCOPEB"),
                "範囲を絞ると呼び出し先の原始プログラムの有無は述べないこと: " + json);
    }

    /** The whole folder is what decides that a callee's source is nowhere in it. */
    @Test
    void aCalleeWithNoSourceInTheFolderIsReportedByAWholeFolderRun() throws IOException {
        Path dir = twoFolderAssets("scopewholecall");
        Path sarif = tempDir.resolve("scopewholecall.sarif");

        new CommandLine(new Main()).execute("lint", dir.toString(),
                "--sarif", sarif.toString(),
                "--sql-sarif", tempDir.resolve("scopewholecall-sql.sarif").toString());

        String json = Files.readString(sarif, StandardCharsets.UTF_8);
        assertTrue(json.contains("NOSUCHPG"),
                "原始プログラムの無い呼び出し先はR048で報告すること: " + json);
        assertTrue(!json.contains("SCOPEB は、"),
                "資産フォルダにある呼び出し先は報告しないこと: " + json);
    }

    /** The scope also reaches the summary, so the GUI can tell a partial run from a whole one. */
    @Test
    void scopeOfOneFileAnalysesThatFileAloneAndIsReportedInTheSummary() throws IOException {
        Path dir = twoFolderAssets("scopefile");

        LintRunner.Result result = LintRunner.run(new LintRunner.Options(dir, List.of(), Map.of(),
                RuleSet.load((Path) null), List.of(), List.of("b/SCOPEB.cbl")));

        assertEquals(List.of("b/SCOPEB.cbl"), result.analyzed());
        assertTrue(result.summaryJson("x.sarif", "y.sarif").contains("\"scope\":[\"b/SCOPEB.cbl\"]"),
                result.summaryJson("x.sarif", "y.sarif"));
    }

    /**
     * A scope leaves the COBOL outside it unread, so nothing in the run knows what those files
     * declare — here the callee sits in a file named otherwise than its PROGRAM-ID. Saying its
     * source is not in the folder would be false, and the whole-folder run does not say it.
     */
    @Test
    void aScopedRunNeverSaysACalleeHasNoSourceInTheFolder() throws IOException {
        Path dir = twoFolderAssets("scopestem");
        Files.move(dir.resolve("b").resolve("SCOPEB.cbl"), dir.resolve("b").resolve("PGM9.cbl"));
        Path sarif = tempDir.resolve("scopestem.sarif");

        new CommandLine(new Main()).execute("lint", dir.toString(),
                "--sarif", sarif.toString(),
                "--sql-sarif", tempDir.resolve("scopestem-sql.sarif").toString(),
                "--scope", "a");

        assertTrue(!Files.readString(sarif, StandardCharsets.UTF_8).contains("SCOPEB"),
                "読んでいないファイルの PROGRAM-ID を推し量らないこと");
    }

    /** What one run of the CLI wrote to standard error, with the stream put back afterwards. */
    private static String standardErrorOf(Runnable run) {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            run.run();
        } finally {
            System.setErr(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    /**
     * A mistyped scope is one line the user can act on. The GUI shows this stream in its run log,
     * where the usage block picocli prints by default would bury that line.
     */
    @Test
    void aScopeThatNamesNothingIsOneLineNamingWhatWasTyped() throws IOException {
        Path dir = twoFolderAssets("scopespelling");

        String stderr = standardErrorOf(() -> Main.commandLine().execute("lint", dir.toString(),
                "--sarif", tempDir.resolve("scopespelling.sarif").toString(),
                "--sql-sarif", tempDir.resolve("scopespelling-sql.sarif").toString(),
                "--scope", "B\\NoSuch.CBL"));

        assertTrue(stderr.contains("B\\NoSuch.CBL は資産フォルダの中に見つかりませんでした。"),
                () -> "書いたとおりの綴りで示すこと: " + stderr);
        assertTrue(!stderr.contains("Usage:"), () -> "使い方の一覧は書かないこと: " + stderr);
    }

    /** A folder the walk took nothing from is there; saying it is not there would be false. */
    @Test
    void aScopeOfAFolderTheWalkTookNothingFromSaysWhatIsWrong() throws IOException {
        Path dir = twoFolderAssets("scopeempty");
        Files.createDirectories(dir.resolve("docs"));
        Files.writeString(dir.resolve("docs").resolve("README.md"), "PROGRAM-ID. NOTAPGM.\n",
                StandardCharsets.UTF_8);

        String stderr = standardErrorOf(() -> Main.commandLine().execute("lint", dir.toString(),
                "--sarif", tempDir.resolve("scopeempty.sarif").toString(),
                "--sql-sarif", tempDir.resolve("scopeempty-sql.sarif").toString(),
                "--scope", "docs"));

        assertTrue(stderr.contains("docs には解析できる資産がありません。"),
                () -> "あるフォルダーを無いとは言わないこと: " + stderr);
    }

    /** A scope that names the asset folder itself analysed everything, and the summary says so. */
    @Test
    void aScopeThatNamesTheAssetFolderItselfIsNotReportedAsAScope() throws IOException {
        Path dir = twoFolderAssets("scopewholearg");

        LintRunner.Result result = LintRunner.run(new LintRunner.Options(dir, List.of(), Map.of(),
                RuleSet.load((Path) null), List.of(), List.of(".")));

        assertEquals(List.of("a/SCOPEA.cbl", "b/SCOPEB.cbl"), result.analyzed());
        assertTrue(!result.summaryJson("x.sarif", "y.sarif").contains("\"scope\""),
                result.summaryJson("x.sarif", "y.sarif"));
    }

    @Test
    void scopeThatNamesNothingInTheWalkIsAUsageError() throws IOException {
        Path dir = twoFolderAssets("scopemiss");
        Path sarif = tempDir.resolve("scopemiss.sarif");

        int exitCode = new CommandLine(new Main()).execute("lint", dir.toString(),
                "--sarif", sarif.toString(),
                "--sql-sarif", tempDir.resolve("scopemiss-sql.sarif").toString(),
                "--scope", "c");

        assertEquals(2, exitCode, "走査で見つからない範囲は使い方の誤りであること");
        assertTrue(!Files.exists(sarif), "解析していないのでSARIFは書き出さないこと");
    }

    /**
     * With no --copybook-path, the COPY search path comes from the run's own walk. The copybook
     * sits in another subfolder, so an unresolved COPY would fail the parse and end in an error.
     */
    @Test
    void copybooksAreFoundByTheWalkWhenNoCopybookPathIsGiven() throws IOException {
        Path dir = tempDir.resolve("implicitcpy");
        Files.createDirectories(dir.resolve("cobol"));
        Files.createDirectories(dir.resolve("copy"));
        Files.writeString(dir.resolve("cobol").resolve("IMPCOPY.cbl"),
                COPY_USER.replace("COPYUSE", "IMPCOPY").replace("COPY EXTC.", "COPY IMPC."),
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("copy").resolve("IMPC.cpy"),
                "       01  IMP-AREA.\n           05  IMP-BASE                PIC X(08).\n",
                StandardCharsets.UTF_8);

        int exitCode = new CommandLine(new Main()).execute("lint", dir.toString(),
                "--sarif", tempDir.resolve("implicitcpy.sarif").toString(),
                "--sql-sarif", tempDir.resolve("implicitcpy-sql.sarif").toString());

        assertEquals(0, exitCode, "走査で見つけたコピー句の置き場所でCOPYが解決すること");
    }

    /**
     * A program whose copybook, whose CICS map and whose job all sit in folders of their own, with
     * a defect in each place: the shape a scope has to keep working across.
     */
    private Path crossAssets(String name) throws IOException {
        Path dir = tempDir.resolve(name);
        for (String folder : List.of("cobol", "copy", "bms", "jcl", "proclib")) {
            Files.createDirectories(dir.resolve(folder));
        }
        Files.writeString(dir.resolve("cobol").resolve("CROSS1.cbl"), CROSS_PROGRAM,
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("copy").resolve("XCPY.cpy"), EXT_COPYBOOK,
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("bms").resolve("CRSMP1.bms"), CROSS_MAPSET,
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("jcl").resolve("JOBC.jcl"), CROSS_JOB,
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("proclib").resolve("MYPROC.proc"), CROSS_MEMBER,
                StandardCharsets.UTF_8);
        return dir;
    }

    /**
     * Copies XCPY, whose REPLACING target the copybook does not hold (R024), sends a map the BMS
     * defines and one it does not (R031), and is run by the PROC member of the job.
     */
    private static final String CROSS_PROGRAM = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID.  CROSS1.",
            "       ENVIRONMENT DIVISION.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "           COPY XCPY REPLACING ==NOSUCH== BY ==WS-KEY==.",
            "       PROCEDURE DIVISION.",
            "       0000-MAIN.",
            "           EXEC CICS",
            "               SEND MAP('CRSM01') MAPSET('CRSMP1')",
            "                   FROM(EXT-AREA)",
            "           END-EXEC",
            "           EXEC CICS",
            "               SEND MAP('NOMAP99') MAPSET('CRSMP1')",
            "                   FROM(EXT-AREA)",
            "           END-EXEC",
            "           GOBACK.",
            "");

    /** A BMS line continued on the next one: the continuation mark stands in column 72. */
    private static String continued(String text) {
        return text + " ".repeat(71 - text.length()) + "X";
    }

    private static final String CROSS_MAPSET = String.join("\n",
            continued("CRSMP1   DFHMSD TYPE=&SYSPARM,"),
            continued("               MODE=INOUT,"),
            continued("               LANG=COBOL,"),
            continued("               STORAGE=AUTO,"),
            "               TIOAPFX=YES",
            "",
            "CRSM01   DFHMDI SIZE=(24,80)",
            "",
            continued("ORDNO    DFHMDF POS=(3,10),"),
            continued("               LENGTH=8,"),
            "               ATTRB=(UNPROT,NUM)",
            "",
            "         DFHMSD TYPE=FINAL",
            "         END",
            "");

    /** The job runs its program through a PROC member of another folder, and one program nobody has. */
    private static final String CROSS_JOB = String.join("\n",
            "//JOBC     JOB  (ACCT),'CROSS',CLASS=A",
            "//STEP1    EXEC MYPROC",
            "//STEP2    EXEC PGM=NOSUCHPG",
            "");

    /** Its second step carries no COND, which is a defect of the member itself (R030). */
    private static final String CROSS_MEMBER = String.join("\n",
            "//MYPROC   PROC",
            "//PSTEP    EXEC PGM=CROSS1",
            "//STEPLIB  DD DSN=CROSS.LOADLIB,DISP=SHR",
            "//PSTEP2   EXEC PGM=CROSS1",
            "//         PEND",
            "");

    private String lintCrossAssets(String name, String... scope) throws IOException {
        return lintAssets(crossAssets(name), name, List.of(), scope);
    }

    /** One run of the CLI over a folder, with the SARIF it wrote as the answer. */
    private String lintAssets(Path dir, String name, List<String> options, String... scope)
            throws IOException {
        Path sarif = tempDir.resolve(name + ".sarif");
        List<String> args = new ArrayList<>(List.of("lint", dir.toString(),
                "--sarif", sarif.toString(),
                "--sql-sarif", tempDir.resolve(name + "-sql.sarif").toString()));
        args.addAll(options);
        for (String path : scope) {
            args.addAll(List.of("--scope", path));
        }
        new CommandLine(new Main()).execute(args.toArray(String[]::new));
        return Files.readString(sarif, StandardCharsets.UTF_8);
    }

    /** The copybooks outside the scope stay in the run, so the rules that read them still fire. */
    @Test
    void aScopeKeepsTheCopybooksTheProgramsInsideItNeed() throws IOException {
        String json = lintCrossAssets("scopecopy", "cobol");

        assertTrue(json.contains("\"ruleId\":\"R024\""),
                "コピー句の本文を引くルールは範囲を絞っても働くこと: " + json);
        assertTrue(json.contains("\"ruleId\":\"R015\"")
                        && json.contains("\"uri\":\"copy/XCPY.cpy\""),
                "コピー句そのものの指摘は資産フォルダ基準の相対パスで報告すること: " + json);
    }

    /**
     * XCPY, which the in-scope program copies, itself copies INNERC (a nested COPY): the whole
     * chain of expansion, not just the program's own inline COPY statements, decides what a scope
     * keeps.
     */
    @Test
    void aScopeKeepsACopybookCopiedByAnotherCopybook() throws IOException {
        Path dir = crossAssets("scopenestedcpy");
        Files.writeString(dir.resolve("copy").resolve("XCPY.cpy"),
                EXT_COPYBOOK + "           COPY INNERC.\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("copy").resolve("INNERC.cpy"),
                "       01  INNER-AREA.\n"
                        + "           05  INNER-PASSWORD          PIC X(08) VALUE 'S3CRET34'.\n",
                StandardCharsets.UTF_8);

        String json = lintAssets(dir, "scopenestedcpy", List.of(), "cobol");

        assertTrue(json.contains("\"ruleId\":\"R026\"")
                        && json.contains("\"uri\":\"copy/INNERC.cpy\""),
                "入れ子で取り込むコピー句の指摘も報告すること: " + json);
    }

    /**
     * A copybook is analysed through the programs that copy it, so one no program inside the scope
     * copies is as much outside the scope as any other file. The rules that read the source text of
     * every decoded unit would otherwise report the whole estate's copybooks under any scope.
     */
    @Test
    void aScopeDropsTheCopybooksNoProgramInsideItCopies() throws IOException {
        Path dir = crossAssets("scopelonecpy");
        Files.writeString(dir.resolve("copy").resolve("LONECPY.cpy"),
                "       01  LONE-AREA.\n"
                        + "           05  LONE-PASSWORD   PIC X(08) VALUE 'S3CRET12'.\n",
                StandardCharsets.UTF_8);

        String json = lintAssets(dir, "scopelonecpy", List.of(), "cobol");

        assertTrue(json.contains("\"uri\":\"copy/XCPY.cpy\""),
                "範囲内のプログラムが取り込むコピー句は報告すること: " + json);
        assertTrue(!json.contains("LONECPY"),
                "範囲内のどのプログラムも取り込まないコピー句は報告しないこと: " + json);
    }

    /** A mapset outside the scope still defines its maps, so a reference to one is not a defect. */
    @Test
    void aScopeDoesNotTurnTheMapsOutsideItIntoUndefinedOnes() throws IOException {
        String json = lintCrossAssets("scopebms", "cobol");

        assertTrue(json.contains("NOMAP99 が BMS のマップ定義にありません"),
                "定義の無いマップは範囲を絞っても報告すること: " + json);
        assertTrue(!json.contains("CRSM01 が BMS のマップ定義にありません"),
                "範囲外の BMS にあるマップ定義も読むこと: " + json);
    }

    /**
     * A PROC member outside the scope still expands, as a copybook outside it still resolves, and
     * what the member itself is at fault for is reported: it is part of the job inside the scope.
     */
    @Test
    void aScopeKeepsTheProcMembersTheJobsInsideItNeed() throws IOException {
        String json = lintCrossAssets("scopeproc", "jcl");

        assertTrue(!json.contains("\"ruleId\":\"R050\""),
                "範囲外の PROC メンバーも展開すること: " + json);
        assertTrue(json.contains("\"uri\":\"jcl/JOBC.jcl\""),
                "範囲内の JCL は解析すること: " + json);
        assertTrue(json.contains("\"uri\":\"proclib/MYPROC.proc\""),
                "範囲内のジョブが展開した PROC メンバーの指摘は報告すること: " + json);
    }

    /**
     * A member reached through --proc-path stands outside the asset folder the scope divides, so
     * the scope drops it; the whole-folder run keeps it, at the path it was read from.
     */
    @Test
    void aScopeDropsTheFindingsOfAProcMemberOutsideTheAssetFolder() throws IOException {
        Path dir = crossAssets("scopeextproc");
        Path library = tempDir.resolve("extproc");
        Files.createDirectories(library);
        Files.move(dir.resolve("proclib").resolve("MYPROC.proc"), library.resolve("MYPROC.proc"));
        List<String> options = List.of("--proc-path", library.toString());

        String wholeFolder = lintAssets(dir, "scopeextprocwhole", options);
        String scoped = lintAssets(dir, "scopeextproc", options, "jcl");

        assertTrue(wholeFolder.contains(library.toString().replace('\\', '/') + "/MYPROC.proc"),
                "資産フォルダの外のメンバーは読み込んだパスのまま報告すること: " + wholeFolder);
        assertTrue(!scoped.contains("MYPROC.proc"),
                "資産フォルダの外のメンバーは範囲の内外を問えないので報告しないこと: " + scoped);
    }

    /** What the files outside the scope report about themselves is not what the user asked for. */
    @Test
    void aScopeReportsNothingOfTheFilesOutsideIt() throws IOException {
        String wholeFolder = lintCrossAssets("scopewhole");
        String scoped = lintCrossAssets("scopepart", "cobol");

        assertTrue(wholeFolder.contains("\"uri\":\"jcl/JOBC.jcl\""), wholeFolder);
        assertTrue(!wholeFolder.contains("\"scope\""),
                "資産フォルダ全体の走行は範囲を名乗らないこと: " + wholeFolder);
        assertTrue(!scoped.contains("\"uri\":\"jcl/JOBC.jcl\""),
                "範囲外の JCL の指摘は報告しないこと: " + scoped);
        assertTrue(scoped.contains("\"scope\":[\"cobol\"]"),
                "SARIF 自体が何を解析したかを述べること: " + scoped);
    }

    /** A program whose SELECT names a DD, and the job that runs it without allocating that DD. */
    private Path ddAssets(String name) throws IOException {
        Path dir = tempDir.resolve(name);
        Files.createDirectories(dir.resolve("cobol"));
        Files.createDirectories(dir.resolve("jcl"));
        Files.writeString(dir.resolve("cobol").resolve("DDPGM1.cbl"), DD_PROGRAM,
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("jcl").resolve("JOBD.jcl"), DD_JOB, StandardCharsets.UTF_8);
        return dir;
    }

    private static final String DD_PROGRAM = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID.  DDPGM1.",
            "       ENVIRONMENT DIVISION.",
            "       INPUT-OUTPUT SECTION.",
            "       FILE-CONTROL.",
            "           SELECT ORDER-FILE ASSIGN TO ORDIN",
            "               ORGANIZATION IS SEQUENTIAL.",
            "       DATA DIVISION.",
            "       FILE SECTION.",
            "       FD  ORDER-FILE.",
            "       01  ORDER-REC                   PIC X(80).",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-COUNT                    PIC 9(03) VALUE ZERO.",
            "       PROCEDURE DIVISION.",
            "       0000-MAIN.",
            "           OPEN INPUT ORDER-FILE",
            "           CLOSE ORDER-FILE",
            "           GOBACK.",
            "");

    private static final String DD_JOB = String.join("\n",
            "//JOBD     JOB  (ACCT),'DD',CLASS=A",
            "//STEP1    EXEC PGM=DDPGM1",
            "");

    /**
     * A scope of the JCL alone leaves no COBOL in the run, so the rules that hold a program and a
     * job side by side have nothing to compare and report nothing. The user hears which kind of
     * check did not run, because the result reads as a clean job control either way.
     */
    @Test
    void aScopeOfTheJclAloneReportsNoMissingDdAndSaysSo() throws IOException {
        Path dir = ddAssets("scopeddjcl");

        assertTrue(lintAssets(dir, "scopeddwhole", List.of()).contains("\"ruleId\":\"R052\""),
                "資産フォルダ全体では割り当て漏れを報告すること");
        StringBuilder scoped = new StringBuilder();
        String stderr = standardErrorOf(() -> {
            try {
                scoped.append(lintAssets(dir, "scopeddjcl", List.of(), "jcl"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        assertTrue(!scoped.toString().contains("\"ruleId\":\"R052\""),
                "範囲に COBOL が無ければ割り当て漏れは報告しないこと: " + scoped);
        assertTrue(stderr.contains("警告: 対象範囲に COBOL プログラムがないため、"
                        + "プログラムとの照合を伴う検査は行いません。"),
                () -> "行わない検査を知らせること: " + stderr);
    }

    @Test
    void errorLevelFindingsExitWithTwo() throws IOException {
        Path dir = assets("err", ERROR);
        Path sarif = tempDir.resolve("err.sarif");

        int exitCode = new CommandLine(new Main()).execute("lint", dir.toString(),
                "--sarif", sarif.toString(), "--sql-sarif", tempDir.resolve("err-sql.sarif").toString());

        assertEquals(2, exitCode, "エラーあり=2であること");
        String json = Files.readString(sarif, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"ruleId\":\"R026\""));
        assertTrue(json.contains("\"level\":\"error\""));
    }
}
