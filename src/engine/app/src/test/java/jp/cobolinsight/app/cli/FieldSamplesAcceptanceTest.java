package jp.cobolinsight.app.cli;

import jp.cobolinsight.analysis.linker.CallGraphLinker;
import jp.cobolinsight.app.persistence.PersistenceDao;
import jp.cobolinsight.app.persistence.PersistenceDatabase;
import jp.cobolinsight.app.persistence.model.CallEdgeRecord;
import jp.cobolinsight.app.persistence.model.JclStepRecord;
import jp.cobolinsight.app.persistence.model.NodeRecord;
import jp.cobolinsight.app.persistence.model.SourceRecord;
import jp.cobolinsight.app.pipeline.Pipelines;
import jp.cobolinsight.app.pipeline.ScanOutcome;
import jp.cobolinsight.core.callgraph.CallGraphEdge;
import jp.cobolinsight.core.callgraph.CallGraphNode;
import jp.cobolinsight.core.callgraph.NodeKind;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.core.json.JsonReader;
import jp.cobolinsight.rules.BuiltinRules;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance test over {@code samples-field/}, the field-style asset set, with the rule set that
 * ships. Every finding on the folder is classified: a seeded defect in
 * {@code expected-findings.tsv} or an accepted false positive in {@code baseline.tsv}. A finding in
 * neither, an expected row that does not fire, and a baseline row that no longer fires all fail.
 * The call graph and the decoding of the three {@code FLENC1} renditions are pinned as well; the
 * ground truth is written up in {@code samples-field/README.md}.
 */
class FieldSamplesAcceptanceTest {

    private static final Path FIELD = Path.of("..", "..", "..", "samples-field").toAbsolutePath().normalize();
    private static final List<Path> COPYBOOKS = List.of(FIELD.resolve("batch"), FIELD.resolve("online"));

    /**
     * The whole call graph of the folder, edge by edge. A step expanded from FLP010 is named
     * job.step.procstep. STEP040 reaches FLB020 as well as IKJEFT01: the {@code RUN PROGRAM(FLB020)}
     * card of its SYSTSIN is read, and the utility node stays because the step does run the monitor.
     * STEP010's {@code DELETE} card names a data set no DD statement of the step does, and the
     * {@code //STEP1.NYUKIN DD} override sends STEP030.STEP1 to the sorted file instead of the
     * daily one the PROC names. The {@code access} of each edge is not printed here; it is pinned in
     * the linker's own tests.
     */
    private static final String EXPECTED_EDGES = """
            job:FLJ010 -> step:FLJ010.STEP010 [EXECUTION/CONSTANT]
            job:FLJ010 -> step:FLJ010.STEP020 [EXECUTION/CONSTANT]
            job:FLJ010 -> step:FLJ010.STEP030.STEP1 [EXECUTION/CONSTANT]
            job:FLJ010 -> step:FLJ010.STEP030.STEP2 [EXECUTION/CONSTANT]
            job:FLJ010 -> step:FLJ010.STEP035 [EXECUTION/CONSTANT]
            job:FLJ010 -> step:FLJ010.STEP040 [EXECUTION/CONSTANT]
            job:FLJ010 -> step:FLJ010.STEP050 [EXECUTION/CONSTANT]
            job:FLJ020 -> step:FLJ020.STEP010 [EXECUTION/CONSTANT]
            job:FLJ020 -> step:FLJ020.STEP020 [EXECUTION/CONSTANT]
            job:FLJ020 -> step:FLJ020.STEP030.STEP1 [EXECUTION/CONSTANT]
            job:FLJ020 -> step:FLJ020.STEP030.STEP2 [EXECUTION/CONSTANT]
            job:FLJ030 -> step:FLJ030.STEP010 [EXECUTION/CONSTANT]
            job:FLJ030 -> step:FLJ030.STEP020 [EXECUTION/CONSTANT]
            job:FLJ030 -> step:FLJ030.STEP030 [EXECUTION/CONSTANT]
            job:FLJ030 -> step:FLJ030.STEP040 [EXECUTION/CONSTANT]
            program:FLB020 -> db2:FLDB.HANBAITEN [REFERENCE/CONSTANT]
            program:FLB020 -> db2:FLDB.KEIYAKU [REFERENCE/CONSTANT]
            program:FLB020 -> db2:FLDB.KESHIKOMI [REFERENCE/CONSTANT]
            program:FLB020 -> db2:FLDB.NYUKIN [REFERENCE/CONSTANT]
            program:FLB020 -> program:FLS010 [CALL/CONSTANT]
            program:FLB020 -> program:FLS020 [CALL/CONSTANT]
            program:FLB050 -> db2:FLDB.KEIYAKU [REFERENCE/CONSTANT]
            program:FLB055 -> db2:FLDB.KEIYAKU [REFERENCE/CONSTANT]
            program:FLB060 -> program:FLS010 [CALL/CONSTANT]
            program:FLB060 -> program:FLS030 [CALL/CONSTANT]
            program:FLO010 -> bmsmap:FLM010.FLM01 [MAP_REFERENCE/CONSTANT]
            program:FLO010 -> bmsmap:FLM010.FLM02 [MAP_REFERENCE/CONSTANT]
            program:FLO010 -> bmsmap:FLM010.FLM03 [MAP_REFERENCE/CONSTANT]
            program:FLO010 -> program:FLO020 [CALL/CONSTANT]
            program:FLO010 -> program:FLO030 [TRANSACTION_TRANSITION/CONSTANT]
            program:FLO010 -> transaction:FL01 [TRANSACTION_TRANSITION/CONSTANT]
            program:FLO020 -> db2:FLDB.KEIYAKU [REFERENCE/CONSTANT]
            program:FLO020 -> db2:FLDB.NYUKIN [REFERENCE/CONSTANT]
            program:FLO030 -> bmsmap:FLM010.FLM01 [MAP_REFERENCE/CONSTANT]
            program:FLO040 -> bmsmap:FLM010.FLM01 [MAP_REFERENCE/CONSTANT]
            program:FLO040 -> bmsmap:FLM010.FLM02 [MAP_REFERENCE/CONSTANT]
            program:FLO040 -> program:FLO050 [TRANSACTION_TRANSITION/CONSTANT]
            program:FLO040 -> transaction:FL03 [TRANSACTION_TRANSITION/CONSTANT]
            program:FLO050 -> bmsmap:FLM010.FLM01 [MAP_REFERENCE/CONSTANT]
            step:FLJ010.STEP010 -> dataset:FLW.D250901.NYUKIN.SORTED [REFERENCE/CONSTANT]
            step:FLJ010.STEP010 -> utility:IDCAMS [EXECUTION/CONSTANT]
            step:FLJ010.STEP020 -> dataset:FLT.D250901.NYUKIN.DAILY [REFERENCE/CONSTANT]
            step:FLJ010.STEP020 -> dataset:FLW.D250901.NYUKIN.SORTED [REFERENCE/CONSTANT]
            step:FLJ010.STEP020 -> utility:SORT [EXECUTION/CONSTANT]
            step:FLJ010.STEP030.STEP1 -> dataset:FLW.D250901.NYUKIN.HENSHU [REFERENCE/CONSTANT]
            step:FLJ010.STEP030.STEP1 -> dataset:FLW.D250901.NYUKIN.SORTED [REFERENCE/CONSTANT]
            step:FLJ010.STEP030.STEP1 -> program:FLB010 [EXECUTION/CONSTANT]
            step:FLJ010.STEP030.STEP2 -> dataset:FLM.KEIYAKU.MASTER [REFERENCE/CONSTANT]
            step:FLJ010.STEP030.STEP2 -> dataset:FLW.D250901.TOKUSOKU.LIST [REFERENCE/CONSTANT]
            step:FLJ010.STEP030.STEP2 -> program:FLB040 [EXECUTION/CONSTANT]
            step:FLJ010.STEP035 -> dataset:FLW.D250901.NYUKIN.ERRFLAG [REFERENCE/CONSTANT]
            step:FLJ010.STEP035 -> utility:IEFBR14 [EXECUTION/CONSTANT]
            step:FLJ010.STEP040 -> program:FLB020 [EXECUTION/CONSTANT]
            step:FLJ010.STEP040 -> utility:IKJEFT01 [EXECUTION/CONSTANT]
            step:FLJ010.STEP050 -> dataset:FLT.NYUKIN.HISTORY(+1) [REFERENCE/CONSTANT]
            step:FLJ010.STEP050 -> dataset:FLW.D250901.NYUKIN.SORTED [REFERENCE/CONSTANT]
            step:FLJ010.STEP050 -> utility:IEBGENER [EXECUTION/CONSTANT]
            step:FLJ020.STEP010 -> dataset:FLM.D250901.SEIKYU.JISSEKI [REFERENCE/CONSTANT]
            step:FLJ020.STEP010 -> dataset:FLM.D250901.SEIKYU.PRINT [REFERENCE/CONSTANT]
            step:FLJ020.STEP010 -> dataset:FLM.KEIYAKU.MASTER [REFERENCE/CONSTANT]
            step:FLJ020.STEP010 -> program:FLB030 [EXECUTION/CONSTANT]
            step:FLJ020.STEP020 -> dataset:FLM.D250901.SEIKYU.SAIHAKKO [REFERENCE/CONSTANT]
            step:FLJ020.STEP020 -> dataset:FLM.D250901.SEIKYU.SAIJISSEKI [REFERENCE/CONSTANT]
            step:FLJ020.STEP020 -> dataset:FLM.KEIYAKU.MASTER [REFERENCE/CONSTANT]
            step:FLJ020.STEP020 -> program:FLB0301 [EXECUTION/CONSTANT]
            step:FLJ020.STEP030.STEP1 -> dataset:FLM.D250901.NYUKIN.HENSHU [REFERENCE/CONSTANT]
            step:FLJ020.STEP030.STEP1 -> dataset:FLT.D250901.NYUKIN.DAILY [REFERENCE/CONSTANT]
            step:FLJ020.STEP030.STEP1 -> program:FLB010 [EXECUTION/CONSTANT]
            step:FLJ020.STEP030.STEP2 -> dataset:FLM.D250901.TOKUSOKU.LIST [REFERENCE/CONSTANT]
            step:FLJ020.STEP030.STEP2 -> dataset:FLM.KEIYAKU.MASTER [REFERENCE/CONSTANT]
            step:FLJ020.STEP030.STEP2 -> program:FLB040 [EXECUTION/CONSTANT]
            step:FLJ030.STEP010 -> program:FLB010 [EXECUTION/CONSTANT]
            step:FLJ030.STEP020 -> program:FLB010 [EXECUTION/CONSTANT]
            step:FLJ030.STEP030 -> program:FLB010 [EXECUTION/CONSTANT]
            step:FLJ030.STEP040 -> program:FLB010 [EXECUTION/CONSTANT]
            transaction:FL01 -> program:FLO010 [TRANSACTION_TRANSITION/CONSTANT]
            transaction:FL03 -> program:FLO040 [TRANSACTION_TRANSITION/CONSTANT]
            """;

    record Expected(int no, String file, int line, String ruleId, FindingLevel level) {
        String at() {
            return file + ":" + line;
        }
    }

    record Accepted(String ruleId, String at, String reason) {
    }

    @TempDir
    static Path tempDir;

    private static List<Expected> expected;
    private static List<Accepted> baseline;
    private static List<Finding> findings;
    private static ScanOutcome scan;
    private static PersistenceDatabase database;
    private static PersistenceDao dao;

    @BeforeAll
    static void run() throws IOException {
        expected = Files.readAllLines(FIELD.resolve("expected-findings.tsv"), StandardCharsets.UTF_8).stream()
                .skip(1).filter(l -> !l.isBlank())
                .map(l -> l.split("\t"))
                .map(c -> new Expected(Integer.parseInt(c[0]), c[1], Integer.parseInt(c[2]), c[3],
                        FindingLevel.valueOf(c[4])))
                .toList();
        baseline = Files.readAllLines(FIELD.resolve("baseline.tsv"), StandardCharsets.UTF_8).stream()
                .filter(l -> !l.isBlank() && !l.startsWith("#") && !l.startsWith("ruleId\t"))
                .map(l -> l.split("\t", 3))
                .map(c -> new Accepted(c[0].strip(), c[1].strip(), c[2].strip()))
                .toList();
        LintRunner.Result lint = LintRunner.run(new LintRunner.Options(FIELD, COPYBOOKS, Map.of()));
        findings = new ArrayList<>(lint.findings());
        findings.addAll(lint.sqlFindings());
        Path databaseFile = tempDir.resolve("field.db");
        scan = Pipelines.scan(FIELD, databaseFile, COPYBOOKS, Map.of());
        database = PersistenceDatabase.open(databaseFile);
        dao = new PersistenceDao(database.connection());
    }

    @AfterAll
    static void closeDatabase() {
        database.close();
    }

    private static String at(Finding finding) {
        return finding.location().file() + ":" + finding.location().line();
    }

    private static boolean isExpected(Finding finding) {
        return expected.stream().anyMatch(e -> e.ruleId().equals(finding.ruleId())
                && e.at().equals(at(finding)));
    }

    private static boolean isAccepted(Finding finding) {
        return baseline.stream().anyMatch(a -> a.ruleId().equals(finding.ruleId())
                && ("*".equals(a.at()) || a.at().equals(at(finding))));
    }

    // ---- lint and sql-lint ----

    @Test
    void fiftySevenDefectSitesAreListed() {
        assertEquals(57, expected.size());
    }

    @Test
    void everyExpectedDefectIsDetectedExactlyOnceWithItsLevel() {
        for (Expected e : expected) {
            List<Finding> hits = findings.stream()
                    .filter(f -> f.ruleId().equals(e.ruleId()) && at(f).equals(e.at()))
                    .toList();
            assertEquals(1, hits.size(), () -> "No." + e.no() + " " + e.ruleId() + " " + e.at()
                    + " must be detected exactly once; " + e.ruleId() + " fired at "
                    + findings.stream().filter(f -> f.ruleId().equals(e.ruleId()))
                            .map(FieldSamplesAcceptanceTest::at).toList());
            assertEquals(e.level(), hits.get(0).level(), () -> "level of No." + e.no());
        }
    }

    @Test
    void everyFindingIsASeededDefectOrAnAcceptedFalsePositive() {
        Set<String> unclassified = findings.stream()
                .filter(f -> f.ruleId().matches("[RS]\\d+"))
                .filter(f -> !isExpected(f) && !isAccepted(f))
                .map(f -> f.ruleId() + " " + at(f) + " " + f.message())
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(Set.of(), unclassified,
                "add the row to expected-findings.tsv if it is a defect, else to baseline.tsv with a reason");
    }

    @Test
    void theFolderParsesAndDecodesCleanly() {
        List<String> failures = findings.stream()
                .filter(f -> !f.ruleId().matches("[RS]\\d+"))
                .map(f -> f.ruleId() + " " + at(f) + " " + f.message())
                .toList();
        assertEquals(List.of(), failures);
    }

    /** A row for a rule that ships off (R008) is the harness's business; this test runs the shipped set. */
    @Test
    void everyBaselineRowStillFires() {
        Set<String> shipped = BuiltinRules.all().stream()
                .filter(rule -> rule.meta().defaultEnabled())
                .map(rule -> rule.meta().id()).collect(Collectors.toSet());
        Set<String> stale = new TreeSet<>();
        for (Accepted a : baseline) {
            if (!shipped.contains(a.ruleId())) {
                continue;
            }
            boolean fires = findings.stream().anyMatch(f -> f.ruleId().equals(a.ruleId())
                    && ("*".equals(a.at()) || a.at().equals(at(f))));
            if (!fires) {
                stale.add(a.ruleId() + " " + a.at());
            }
        }
        assertEquals(Set.of(), stale, "delete these rows from baseline.tsv");
    }

    // ---- scan and encoding ----

    @Test
    void scanAnalysesEveryFileWithoutFailure() {
        ScanOutcome.Summary summary = scan.summary();
        assertEquals(0, summary.exitCode());
        assertEquals(0, summary.findingCount(), "no parse or decode failure");
        assertEquals(30, summary.analyzed().size(),
                "3 jobs, 1 PROC, 9 batch programs, 5 batch copybooks, 5 online programs, "
                        + "3 online copybooks, 1 mapset, 3 encoding renditions");
    }

    @Test
    void codePagesAreDetectedWithoutAnOverride() {
        for (SourceRecord source : dao.findAllSources()) {
            String wanted = switch (source.path()) {
                case "encoding/FLENC1_SJIS.cbl" -> "windows-31j";
                case "encoding/FLENC1_CP930.cbl" -> "x-IBM930";
                default -> "UTF-8";
            };
            assertEquals(wanted, source.codepage(), source.path());
        }
    }

    /** The three renditions are one program; the decoder must give the same text for each. */
    @Test
    void theThreeEncodingRenditionsDecodeToTheSameText() {
        List<String> utf8 = decodedLines("FLENC1_UTF8.cbl");
        assertEquals(utf8, decodedLines("FLENC1_SJIS.cbl"));
        assertEquals(utf8, decodedLines("FLENC1_CP930.cbl", "--codepage", "cp930"));
        assertTrue(utf8.stream().anyMatch(l -> l.contains("ｼｮﾘ")), "half-width katakana survive");
        assertTrue(utf8.stream().anyMatch(l -> l.contains("入金消込")), "full-width kanji survive");
    }

    private static List<String> decodedLines(String fileName, String... extraArgs) {
        Path out = tempDir.resolve(fileName + ".json");
        List<String> args = new ArrayList<>(List.of("decode",
                "--file", FIELD.resolve("encoding").resolve(fileName).toString()));
        args.addAll(List.of(extraArgs));
        args.addAll(List.of("--out", out.toString()));
        assertEquals(0, new CommandLine(new Main()).execute(args.toArray(String[]::new)), fileName);
        try {
            Map<String, Object> result = JsonReader.asObject(
                    JsonReader.parse(Files.readString(out, StandardCharsets.UTF_8)));
            assertEquals("", result.get("error"), fileName);
            // EBCDIC records are padded to their record length; compare without trailing blanks.
            return ((String) result.get("text")).lines().map(String::stripTrailing).toList();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    // ---- call graph ----

    private static String edgeKey(CallGraphEdge edge) {
        return edge.fromId() + " -> " + edge.toId() + " [" + edge.kind() + "/" + edge.resolution() + "]";
    }

    @Test
    void graphMatchesTheGroundTruthEdgeByEdge() {
        Set<String> wanted = EXPECTED_EDGES.lines().map(String::strip).filter(l -> !l.isEmpty())
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> actual = scan.callGraph().edges().stream()
                .map(FieldSamplesAcceptanceTest::edgeKey)
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(wanted, actual);
    }

    @Test
    void nodesAreTypedAndMarkedAsExpected() {
        Map<String, CallGraphNode> byId = scan.callGraph().nodes().stream()
                .collect(Collectors.toMap(CallGraphNode::id, n -> n));
        assertEquals(62, byId.size(), "3 jobs, 15 steps, 17 programs, 13 datasets, 4 Db2 tables, "
                + "2 transactions, 3 maps, 5 utilities");
        assertEquals(Map.of("external", "true"), byId.get("program:FLB0301").attributes(),
                "PGM=FLB0301 names no program in the folder");
        assertEquals(Map.of("external", "true"), byId.get("program:FLS020").attributes(),
                "CALL WS-PGM-NAME resolves through the VALUE clause to a program outside the folder");
        assertEquals(Map.of("undefined", "true"), byId.get("bmsmap:FLM010.FLM03").attributes(),
                "SEND MAP('FLM03') names a map the analysed mapset does not define");
        assertEquals(Map.of(), byId.get("bmsmap:FLM010.FLM02").attributes());
        assertEquals(Set.of("IDCAMS", "IEBGENER", "IEFBR14", "IKJEFT01", "SORT"),
                byId.values().stream().filter(n -> n.kind() == NodeKind.EXTERNAL_UTILITY)
                        .map(CallGraphNode::label).collect(Collectors.toSet()));
        assertTrue(byId.values().stream().noneMatch(n -> n.kind() == NodeKind.UNRESOLVED));
        // FL02 and FL04 are in the definition table but no program references them, so neither is a node
        assertEquals(Set.of("FL01", "FL03"), byId.values().stream()
                .filter(n -> n.kind() == NodeKind.TRANSACTION).map(CallGraphNode::label)
                .collect(Collectors.toSet()));
    }

    /** STEP030 expands from FLP010 and its steps carry the PROC's line numbers; seq still follows the job. */
    @Test
    void stepsOfAJobAreNumberedInExecutionOrder() {
        List<String> order = scan.callGraph().edges().stream()
                .filter(e -> e.fromId().equals("job:FLJ010"))
                .sorted(Comparator.comparingInt(CallGraphEdge::seq))
                .map(CallGraphEdge::toId).toList();
        assertEquals(List.of("step:FLJ010.STEP010", "step:FLJ010.STEP020",
                "step:FLJ010.STEP030.STEP1", "step:FLJ010.STEP030.STEP2", "step:FLJ010.STEP035",
                "step:FLJ010.STEP040", "step:FLJ010.STEP050"), order);
    }

    // ---- what the scan wrote to SQLite ----

    private static long sourceIdOf(String relPath) {
        return dao.findAllSources().stream().filter(s -> s.path().equals(relPath))
                .map(SourceRecord::id).findFirst()
                .orElseThrow(() -> new AssertionError("SOURCE row not found: " + relPath));
    }

    /** FLJ010's steps in execution order, with the PROC-expanded pair naming the step inside FLP010. */
    @Test
    void jclStepRowsFollowExecutionOrderAndNameTheirProcStep() {
        List<JclStepRecord> steps = dao.findJclStepsBySource(sourceIdOf("batch/FLJ010.jcl"));
        assertEquals(List.of("STEP010", "STEP020", "STEP030", "STEP030.STEP1", "STEP030.STEP2",
                        "STEP035", "STEP040", "STEP050"),
                steps.stream().map(JclStepRecord::stepName).toList(),
                "EXEC FLP010 itself is a row too, since JCL_STEP records the JCL and not the graph");
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8),
                steps.stream().map(JclStepRecord::seq).toList());
        JclStepRecord expanded = steps.stream()
                .filter(step -> "STEP030.STEP1".equals(step.stepName())).findFirst().orElseThrow();
        assertEquals("STEP1", expanded.procStep());
        assertEquals("FLB010", expanded.target());
        assertEquals("batch/FLP010.proc", expanded.file(),
                "an expanded step stands in the PROC member, named as SOURCE.path names it");
        assertEquals("batch/FLJ010.jcl", steps.get(0).file());
    }

    /**
     * The FILE-CONTROL of FLB010 decides how FLJ010's STEP030.STEP1 uses its two data sets: NYUKIN
     * is read and NYUOUT is written. Guards the path from the SELECT entries of the program, through
     * the linker, into CALL_EDGE.access. Both data sets belong to FLJ010 alone — FLJ020's own
     * STEP030.STEP1 reads FLT.D250901.NYUKIN.DAILY and writes FLM.D250901.NYUKIN.HENSHU.
     */
    @Test
    void datasetEdgeRowsCarryTheAccessTheProgramMakesOfThem() {
        assertEquals("READ", datasetEdgeAccess("FLW.D250901.NYUKIN.SORTED", "STEP030.STEP1"),
                "FLB010 opens NYUKIN for input, and the //STEP1.NYUKIN override points it here");
        assertEquals("WRITE", datasetEdgeAccess("FLW.D250901.NYUKIN.HENSHU", "STEP030.STEP1"),
                "FLB010 opens NYUOUT for output");
    }

    /**
     * The row of the edge STEP040's {@code RUN PROGRAM(FLB020) PLAN(FLPLAN1)} card makes. What the
     * card said is kept in attrs_json; {@code access} has a column of its own and is never repeated
     * there, so nobody has two spellings of one fact to keep in step.
     */
    @Test
    void theLauncherEdgeRowKeepsItsCardInAttributesAndAccessInItsColumn() {
        Map<Long, NodeRecord> byId = dao.findAllNodes().stream()
                .collect(Collectors.toMap(NodeRecord::id, node -> node));
        long program = byId.values().stream()
                .filter(node -> "PROGRAM".equals(node.type()) && "FLB020".equals(node.label()))
                .map(NodeRecord::id).findFirst()
                .orElseThrow(() -> new AssertionError("no PROGRAM node for FLB020"));
        List<CallEdgeRecord> edges = dao.findEdgesTo(program).stream()
                .filter(edge -> "EXECUTION".equals(edge.kind()))
                .filter(edge -> "STEP040".equals(byId.get(edge.fromNode()).label())).toList();
        assertEquals(1, edges.size(), () -> "STEP040 -> FLB020 は 1 行であること: " + edges);
        CallEdgeRecord edge = edges.get(0);
        assertEquals("{\"launcher\":\"IKJEFT01\",\"plan\":\"FLPLAN1\"}", edge.attrsJson());
        assertNull(edge.access(), "実行の辺はデータセットの使い方を持たないこと");

        CallEdgeRecord read = dao.findEdgesTo(datasetId("FLW.D250901.NYUKIN.SORTED")).stream()
                .filter(candidate -> "READ".equals(candidate.access())).findFirst()
                .orElseThrow(() -> new AssertionError("no READ edge for the sorted data set"));
        assertNull(read.attrsJson(), "access だけを持つ辺は attrs_json を持たないこと");
    }

    private static long datasetId(String dataset) {
        return dao.findAllNodes().stream()
                .filter(node -> "DATASET".equals(node.type()) && dataset.equals(node.label()))
                .map(NodeRecord::id).findFirst()
                .orElseThrow(() -> new AssertionError("no DATASET node for " + dataset));
    }

    /** The access of the one REFERENCE row reaching a data set from a step with the given label. */
    private static String datasetEdgeAccess(String dataset, String stepLabel) {
        Map<Long, NodeRecord> byId = dao.findAllNodes().stream()
                .collect(Collectors.toMap(NodeRecord::id, node -> node));
        long datasetId = byId.values().stream()
                .filter(node -> "DATASET".equals(node.type()) && dataset.equals(node.label()))
                .map(NodeRecord::id).findFirst()
                .orElseThrow(() -> new AssertionError("no DATASET node for " + dataset));
        List<String> accesses = dao.findEdgesTo(datasetId).stream()
                .filter(edge -> "REFERENCE".equals(edge.kind()))
                .filter(edge -> stepLabel.equals(byId.get(edge.fromNode()).label()))
                .map(CallEdgeRecord::access).toList();
        assertEquals(1, accesses.size(),
                () -> stepLabel + " -> " + dataset + " must be a single row: " + accesses);
        return accesses.get(0);
    }

    @Test
    void theDynamicCallIsResolvedFromItsValueClause() {
        List<Finding> notes = scan.linkerFindings();
        assertEquals(1, notes.size(), () -> "one linker note: " + notes);
        Finding note = notes.get(0);
        assertEquals(CallGraphLinker.DYNAMIC_CALL_RESOLVED_RULE_ID, note.ruleId());
        assertTrue(note.location().file().replace('\\', '/').endsWith("batch/FLB020.cbl"),
                note.location().file());
        assertEquals(242, note.location().line());
        assertTrue(note.message().contains("WS-PGM-NAME"), note.message());
        assertTrue(note.message().contains("FLS020"), note.message());
    }
}
