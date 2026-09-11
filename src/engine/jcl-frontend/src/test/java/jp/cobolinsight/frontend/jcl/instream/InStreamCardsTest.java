package jp.cobolinsight.frontend.jcl.instream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import jp.cobolinsight.core.jcl.JclDataset;
import jp.cobolinsight.core.jcl.JclDdStatement;
import jp.cobolinsight.core.jcl.JclDisposition;
import jp.cobolinsight.core.jcl.JclExecKind;
import jp.cobolinsight.core.jcl.JclStep;
import jp.cobolinsight.core.jcl.JclUtilityFacts.BindRequest;
import jp.cobolinsight.core.jcl.JclUtilityFacts.DatasetAccess;
import jp.cobolinsight.core.jcl.JclUtilityFacts.DatasetUse;
import jp.cobolinsight.core.jcl.JclUtilityFacts.ProgramRun;
import jp.cobolinsight.core.jcl.JclUtilityFacts.TableUse;
import jp.cobolinsight.core.source.SourcePosition;

/** Each utility's control cards, read on their own: the card text in, the facts out. */
class InStreamCardsTest {

    private final List<ProgramRun> runs = new ArrayList<>();
    private final List<BindRequest> binds = new ArrayList<>();
    private final List<DatasetUse> datasetUses = new ArrayList<>();
    private final List<TableUse> tableUses = new ArrayList<>();
    private final Map<String, DatasetAccess> ddRoles = new LinkedHashMap<>();

    @Test
    void joinsTheLinesAContinuationMarkCarriesOn() {
        assertEquals(List.of("A B", "C"), Cards.continued(List.of("A -", " B", "C")));
        assertEquals(List.of("SORT FIELDS=(1,8,CH,A, 20,3,PD,D)", "SUM FIELDS=NONE"),
                Cards.comma(List.of("SORT FIELDS=(1,8,CH,A,", "  20,3,PD,D)", "SUM FIELDS=NONE")));
    }

    @Test
    void readsTheDsnRunCardsOfSystsin() {
        TsoCards.read(List.of(
                "  DSN SYSTEM(DB2P)",
                "  RUN PROGRAM(CCP011) PLAN(CCPPLAN1) LIB('CCP.PROD.LOADLIB')      -",
                "      PARMS('20260101,RERUN')",
                "  RUN PROGRAM(CCP012) PLAN(CCPPLAN2)",
                "  END"), runs, binds, datasetUses, ddRoles);

        assertEquals(2, runs.size());
        assertEquals("CCP011", runs.get(0).program());
        assertEquals(Optional.of("CCPPLAN1"), runs.get(0).plan());
        assertEquals(Optional.of("CCP.PROD.LOADLIB"), runs.get(0).library());
        assertEquals(Optional.of("20260101,RERUN"), runs.get(0).parms());
        assertEquals("CCP012", runs.get(1).program());
        assertEquals(Optional.empty(), runs.get(1).library());
    }

    @Test
    void readsBindAndRebindCards() {
        TsoCards.read(List.of(
                "  DSN SYSTEM(DB2P)",
                "  BIND PACKAGE(CCPCOLL1) MEMBER(CCP011) ACTION(REPLACE)  -",
                "      ISOLATION(CS)",
                "  BIND PLAN(CCPPLAN1) MEMBER(CCP011,CCP012) ACTION(REPLACE)",
                "  REBIND PACKAGE(CCPCOLL1.CCP011)",
                "  END"), runs, binds, datasetUses, ddRoles);

        assertEquals(3, binds.size());
        assertEquals("PACKAGE", binds.get(0).kind());
        assertEquals("CCPCOLL1", binds.get(0).name());
        assertEquals(List.of("CCP011"), binds.get(0).members());
        assertEquals(Map.of("ACTION", "REPLACE", "ISOLATION", "CS"), binds.get(0).options());
        assertEquals("PLAN", binds.get(1).kind());
        assertEquals(List.of("CCP011", "CCP012"), binds.get(1).members());
        assertEquals("REBIND-PACKAGE", binds.get(2).kind());
    }

    @Test
    void readsTheTsoCommandsOfSystsin() {
        TsoCards.read(List.of(
                "  ALLOCATE DA('CJT.D260910.WORK.INPUT') FI(INFILE) SHR",
                "  DELETE 'CJT.D260909.WORK.OLD'",
                "  FREE FI(INFILE)",
                "  LISTCAT ENTRIES('CJT.D260910.WORK.INPUT') ALL",
                "  CALL 'CCP.PROD.LOADLIB(CCP006)' 'PARM1,PARM2'"),
                runs, binds, datasetUses, ddRoles);

        assertEquals(List.of(new DatasetUse("CJT.D260910.WORK.INPUT", DatasetAccess.READ),
                new DatasetUse("CJT.D260909.WORK.OLD", DatasetAccess.DELETE)), datasetUses);
        assertEquals(DatasetAccess.READ, ddRoles.get("INFILE"));
        assertEquals(1, runs.size());
        assertEquals("CCP006", runs.get(0).program());
        assertEquals(Optional.of("CCP.PROD.LOADLIB"), runs.get(0).library());
        assertEquals(Optional.of("PARM1,PARM2"), runs.get(0).parms());
    }

    @Test
    void readsTheTablesOfADb2UtilityStep() {
        Dsnutilb.read(List.of(
                "  LOAD DATA INDDN(SYSREC) RESUME NO REPLACE LOG NO",
                "      INTO TABLE SCHEMA.TBL",
                "  RUNSTATS TABLESPACE DB.TS TABLE(ALL) INDEX(ALL)",
                "  REORG TABLESPACE DB.TS2"), tableUses);

        assertEquals(List.of(new TableUse("SCHEMA.TBL", DatasetAccess.WRITE),
                new TableUse("DB.TS", DatasetAccess.READ),
                new TableUse("DB.TS2", DatasetAccess.UPDATE)), tableUses);
    }

    @Test
    void readsTheSqlOfADsntep2Step() {
        SqlCards.read(List.of(
                "  SELECT KEIYAKU_NO, ZANDAKA           -- current balance",
                "    FROM SCHEMA.TBL",
                "   WHERE HANBAITEN_CD = 'H0001';",
                "  UPDATE SCHEMA.TBL2",
                "     SET ZANDAKA = ZANDAKA - 1000",
                "   WHERE KEIYAKU_NO = 'K0000000001';",
                "  DELETE FROM SCHEMA.TBL3 WHERE SHORI_KBN = '0';"), tableUses);

        assertEquals(List.of(new TableUse("SCHEMA.TBL", DatasetAccess.READ),
                new TableUse("SCHEMA.TBL2", DatasetAccess.UPDATE),
                new TableUse("SCHEMA.TBL3", DatasetAccess.DELETE)), tableUses);
        assertTrue(SqlCards.isSqlProcessor("DSNTIAUL"));
    }

    @Test
    void readsTheDdNamesASortCardNames() {
        SortCards.read("SORT", List.of(
                "  SORT FIELDS=(1,8,CH,A)",
                "  OUTFIL FNAMES=OUT1,INCLUDE=(1,3,CH,EQ,C'ABC')",
                "  OUTFIL FNAMES=(OUT2,OUT3)",
                "  JOINKEYS FILE=F1,FIELDS=(1,8,A)",
                "  JOINKEYS F2=JOININ2,FIELDS=(1,8,A)"), ddRoles);

        assertEquals(DatasetAccess.WRITE, ddRoles.get("OUT1"));
        assertEquals(DatasetAccess.WRITE, ddRoles.get("OUT2"));
        assertEquals(DatasetAccess.WRITE, ddRoles.get("OUT3"));
        assertEquals(DatasetAccess.READ, ddRoles.get("SORTJNF1"),
                "FILE=F1 names the join file, which DFSORT reads through SORTJNF1");
        assertNull(ddRoles.get("F1"), "F1 is no DD name of the step");
        assertEquals(DatasetAccess.READ, ddRoles.get("JOININ2"),
                "the older F2=ddname form does name a DD");
    }

    @Test
    void readsTheOperatorsOfAnIcetoolStep() {
        assertEquals("TOOLIN", SortCards.controlDd("ICETOOL"));
        SortCards.read("ICETOOL", List.of(
                "  SELECT FROM(IN) TO(OUT) ON(1,8,CH) FIRST",
                "  COPY FROM(IN) TO(COPYOUT) USING(CTL1)"), ddRoles);

        assertEquals(DatasetAccess.READ, ddRoles.get("IN"));
        assertEquals(DatasetAccess.WRITE, ddRoles.get("OUT"));
        assertEquals(DatasetAccess.WRITE, ddRoles.get("COPYOUT"));
        assertEquals(DatasetAccess.READ, ddRoles.get("CTL1CNTL"));
    }

    /** A DD one card reads and another writes is used both ways, which is an UPDATE. */
    @Test
    void mergesADdReadByOneCardAndWrittenByAnotherIntoAnUpdate() {
        SortCards.read("ICETOOL", List.of(
                "  COPY FROM(IN) TO(WORK)",
                "  COPY FROM(WORK) TO(OUT)"), ddRoles);

        assertEquals(DatasetAccess.READ, ddRoles.get("IN"));
        assertEquals(DatasetAccess.UPDATE, ddRoles.get("WORK"));
        assertEquals(DatasetAccess.WRITE, ddRoles.get("OUT"));
    }

    @Test
    void readsTheAccessMethodServicesCommands() {
        Idcams.read(List.of(
                "  DEFINE CLUSTER (NAME(CJV.KEIYAKU.CLUSTER)                  -",
                "                  INDEXED                                    -",
                "                  KEYS(8 0))",
                "  REPRO INFILE(IN) OUTFILE(OUT)",
                "  REPRO INDATASET(CJT.D260909.OLD) OUTDATASET(CJT.D260910.NEW)",
                "  ALTER CJT.D260909.WORK.OLD NEWNAME(CJT.D260909.WORK.RENAMED)",
                "  DELETE CJT.D260908.WORK.TEMP CLUSTER PURGE",
                "  LISTCAT ENTRIES(CJV.KEIYAKU.CLUSTER) ALL"), datasetUses, ddRoles);

        assertEquals(List.of(new DatasetUse("CJV.KEIYAKU.CLUSTER", DatasetAccess.CREATE),
                new DatasetUse("CJT.D260909.OLD", DatasetAccess.READ),
                new DatasetUse("CJT.D260910.NEW", DatasetAccess.WRITE),
                new DatasetUse("CJT.D260909.WORK.OLD", DatasetAccess.UPDATE),
                new DatasetUse("CJT.D260909.WORK.RENAMED", DatasetAccess.CREATE),
                new DatasetUse("CJT.D260908.WORK.TEMP", DatasetAccess.DELETE)), datasetUses);
        assertEquals(DatasetAccess.READ, ddRoles.get("IN"));
        assertEquals(DatasetAccess.WRITE, ddRoles.get("OUT"));
    }

    /** A cluster names its data and its index components too, and all of them are created. */
    @Test
    void recordsEveryComponentADefineNames() {
        Idcams.read(List.of(
                "  DEFINE CLUSTER (NAME(CJV.K.CLUSTER) INDEXED)   -",
                "         DATA (NAME(CJV.K.DATA))                 -",
                "         INDEX (NAME(CJV.K.INDEX))"), datasetUses, ddRoles);

        assertEquals(List.of(new DatasetUse("CJV.K.CLUSTER", DatasetAccess.CREATE),
                new DatasetUse("CJV.K.DATA", DatasetAccess.CREATE),
                new DatasetUse("CJV.K.INDEX", DatasetAccess.CREATE)), datasetUses);
    }

    /** DELETE takes a parenthesised list of names as readily as one name. */
    @Test
    void readsADeleteListInBothProducts() {
        Idcams.read(List.of("  DELETE (CJT.A CJT.B) CLUSTER PURGE"), datasetUses, ddRoles);
        assertEquals(List.of(new DatasetUse("CJT.A", DatasetAccess.DELETE),
                new DatasetUse("CJT.B", DatasetAccess.DELETE)), datasetUses);

        List<DatasetUse> tso = new ArrayList<>();
        TsoCards.read(List.of("  DELETE ('CJT.C' 'CJT.D')"), runs, binds, tso, ddRoles);
        assertEquals(List.of(new DatasetUse("CJT.C", DatasetAccess.DELETE),
                new DatasetUse("CJT.D", DatasetAccess.DELETE)), tso);
    }

    /** RUN and BIND are the DSN command processor's; once END has closed it they are not read. */
    @Test
    void readsNoRunCardAfterEndHasClosedTheDsnCommand() {
        TsoCards.read(List.of(
                "  DSN SYSTEM(DB2P)",
                "  RUN PROGRAM(CCP011) PLAN(CCPPLAN1)",
                "  END",
                "  RUN PROGRAM(CCP099) PLAN(CCPPLAN9)"), runs, binds, datasetUses, ddRoles);

        assertEquals(1, runs.size(), "only the RUN inside the DSN command is one");
        assertEquals("CCP011", runs.get(0).program());
    }

    /** ALLOCATE names its data set by DA, DATASET or DSNAME, and NEW says it creates one. */
    @Test
    void readsAnAllocateThatCreatesADataset() {
        TsoCards.read(List.of("  ALLOCATE DSNAME('CJT.NEW.FILE') DD(OUTFILE) NEW"),
                runs, binds, datasetUses, ddRoles);

        assertEquals(List.of(new DatasetUse("CJT.NEW.FILE", DatasetAccess.CREATE)), datasetUses);
        assertEquals(DatasetAccess.CREATE, ddRoles.get("OUTFILE"));
    }

    /** The cards are read whatever case they were written in. */
    @Test
    void readsLowercaseCards() {
        TsoCards.read(List.of(
                "  dsn system(db2p)",
                "  run program(ccp011) plan(ccpplan1)",
                "  bind plan(ccpplan1) member(ccp011)",
                "  end"), runs, binds, datasetUses, ddRoles);

        assertEquals(1, runs.size());
        assertEquals("ccp011", runs.get(0).program());
        assertEquals(1, binds.size());
        assertEquals("PLAN", binds.get(0).kind());
    }

    /** A verb standing inside a qualified table name starts no statement of its own. */
    @Test
    void readsAVerbInATableNameAsPartOfTheName() {
        Dsnutilb.read(List.of("  UNLOAD TABLESPACE DB.TS FROM TABLE SCHEMA.REPORT"), tableUses);

        assertEquals(List.of(new TableUse("DB.TS", DatasetAccess.READ),
                new TableUse("SCHEMA.REPORT", DatasetAccess.READ)), tableUses);
    }

    /** An asterisk in column 1 is a comment card of the sort products, not a control statement. */
    @Test
    void readsPastASortCommentCard() {
        SortCards.read("SORT", List.of(
                "* this is a comment card and names no DD",
                "  OUTFIL FNAMES=OUT1"), ddRoles);

        assertEquals(1, ddRoles.size());
        assertEquals(DatasetAccess.WRITE, ddRoles.get("OUT1"));
    }

    /** A FROM naming several tables reads all of them, and a CREATE AS SELECT reads its source. */
    @Test
    void readsEveryTableOfAFromListAndOfACreateAsSelect() {
        SqlCards.read(List.of(
                "  SELECT A.C1, B.C2 FROM SCHEMA.TA, SCHEMA.TB WHERE A.K = B.K;",
                "  CREATE TABLE SCHEMA.TC AS (SELECT * FROM SCHEMA.TA) WITH NO DATA;",
                "  DELETE FROM SCHEMA.TD WHERE K IN (SELECT K FROM SCHEMA.TD);"), tableUses);

        assertEquals(List.of(new TableUse("SCHEMA.TA", DatasetAccess.READ),
                new TableUse("SCHEMA.TB", DatasetAccess.READ),
                new TableUse("SCHEMA.TC", DatasetAccess.CREATE),
                new TableUse("SCHEMA.TD", DatasetAccess.DELETE),
                new TableUse("SCHEMA.TD", DatasetAccess.READ)), tableUses);
    }

    /** A semicolon inside a literal ends no statement. */
    @Test
    void readsASemicolonInsideALiteralAsPartOfTheStatement() {
        SqlCards.read(List.of(
                "  UPDATE SCHEMA.TBL SET NOTE = 'a;b' WHERE K = '1';"), tableUses);

        assertEquals(List.of(new TableUse("SCHEMA.TBL", DatasetAccess.UPDATE)), tableUses);
    }

    /** A plus sign carries a card on the way a hyphen does. */
    @Test
    void joinsACardAPlusSignCarriesOn() {
        assertEquals(List.of("DELETE CJT.A"), Cards.continued(List.of("DELETE +", "  CJT.A")));
    }

    /** A COPY card split on a comma continuation names both its libraries all the same. */
    @Test
    void readsAnIebcopyPairSplitAcrossAContinuation() {
        Iebcopy.read(List.of("  COPY INDD=IN,", "       OUTDD=OUT"), ddRoles);

        assertEquals(DatasetAccess.READ, ddRoles.get("IN"));
        assertEquals(DatasetAccess.WRITE, ddRoles.get("OUT"));
    }

    @Test
    void readsTheCopyCardsOfALibraryCopy() {
        Iebcopy.read(List.of("  COPY INDD=IN,OUTDD=OUT",
                "  SELECT MEMBER=(MEMBA,MEMBB)"), ddRoles);

        assertEquals(DatasetAccess.READ, ddRoles.get("IN"));
        assertEquals(DatasetAccess.WRITE, ddRoles.get("OUT"));
    }

    @Test
    void readsWhatIefbr14DoesFromTheDispositions() {
        JclStep step = step("IEFBR14",
                dd("NEWDS", "CJT.NEW", new JclDisposition("NEW", Optional.of("CATLG"),
                        Optional.of("DELETE"), "(NEW,CATLG,DELETE)")),
                dd("OLDDS", "CJT.OLD", new JclDisposition("MOD", Optional.of("DELETE"),
                        Optional.of("DELETE"), "(MOD,DELETE,DELETE)")));
        Iefbr14.read(step, ddRoles);

        assertEquals(DatasetAccess.CREATE, ddRoles.get("NEWDS"));
        assertEquals(DatasetAccess.DELETE, ddRoles.get("OLDDS"));
    }

    @Test
    void fallsBackToTheDispositionOfEveryDdNoCardNamed() {
        JclStep step = step("FLB010",
                dd("IN1", "CJT.IN", new JclDisposition("SHR", Optional.empty(), Optional.empty(),
                        "SHR")),
                dd("OUT1", "CJT.OUT", new JclDisposition("NEW", Optional.of("CATLG"),
                        Optional.empty(), "(NEW,CATLG)")));
        UtilityDdRoles.apply("FLB010", step, ddRoles);

        assertEquals(DatasetAccess.READ, ddRoles.get("IN1"));
        assertEquals(DatasetAccess.WRITE, ddRoles.get("OUT1"));
    }

    /**
     * A verb in the middle of a card is an operand of the statement it stands in and starts nothing,
     * so the table space belongs to REORG and is updated rather than read.
     */
    @Test
    void readsOnlyAVerbThatOpensACardAsAStatement() {
        Dsnutilb.read(List.of(
                "  REORG UNLOAD CONTINUE",
                "      TABLESPACE DB.TS"), tableUses);

        assertEquals(List.of(new TableUse("DB.TS", DatasetAccess.UPDATE)), tableUses);
    }

    /** A Db2 utility statement may carry a {@code --} comment, and nothing in it is a statement. */
    @Test
    void readsPastADoubleHyphenCommentInAUtilityStream() {
        Dsnutilb.read(List.of(
                "  LOAD DATA INDDN(SYSREC) REPLACE  -- COPY INTO TABLE SCHEMA.WRONG",
                "      INTO TABLE SCHEMA.TBL"), tableUses);

        assertEquals(List.of(new TableUse("SCHEMA.TBL", DatasetAccess.WRITE)), tableUses);
    }

    /** A number is no table name, so the 1 of a SUBSTRING is not booked as a table. */
    @Test
    void readsNoNumberAsATableName() {
        SqlCards.read(List.of(
                "  SELECT SUBSTRING(SHIMEI FROM 1 FOR 3)",
                "    FROM SCHEMA.TBL;"), tableUses);

        assertEquals(List.of(new TableUse("SCHEMA.TBL", DatasetAccess.READ)), tableUses);
    }

    /** A control DD concatenated out of several in-stream parts is one stream to the program. */
    @Test
    void readsEveryInStreamPartOfAConcatenatedControlDd() {
        JclStep step = new JclStep("STEP010", JclExecKind.PGM, "IDCAMS", Optional.empty(),
                List.of(inStream("SYSIN", 0, "  DELETE CJT.OLD.FILE"),
                        inStream("SYSIN", 1, "  DEFINE CLUSTER (NAME(CJT.NEW.FILE))")),
                position());

        assertEquals(List.of("  DELETE CJT.OLD.FILE", "  DEFINE CLUSTER (NAME(CJT.NEW.FILE))"),
                UtilityDdRoles.cards(step, "SYSIN"));
    }

    /** NEW and MOD allocate the data set whatever becomes of it afterwards, so the DD creates it. */
    @Test
    void recordsACreateForEveryDispositionThatAllocates() {
        JclStep step = step("IEFBR14",
                dd("PASSDS", "CJT.PASS", new JclDisposition("NEW", Optional.of("PASS"),
                        Optional.empty(), "(NEW,PASS)")),
                dd("PLAINDS", "CJT.PLAIN", new JclDisposition("NEW", Optional.empty(),
                        Optional.empty(), "NEW")),
                dd("APPENDDS", "CJT.APPEND", new JclDisposition("MOD", Optional.empty(),
                        Optional.empty(), "MOD")));
        Iefbr14.read(step, ddRoles);

        assertEquals(DatasetAccess.CREATE, ddRoles.get("PASSDS"));
        assertEquals(DatasetAccess.CREATE, ddRoles.get("PLAINDS"));
        assertEquals(DatasetAccess.CREATE, ddRoles.get("APPENDDS"));
    }

    /** One entry of a control DD, its cards under it and nothing else. */
    private static JclDdStatement inStream(String ddName, int concatIndex, String... lines) {
        return new JclDdStatement(ddName, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), false, Map.of("*", ""), Map.of(),
                List.of(lines), concatIndex, position());
    }

    private static JclStep step(String target, JclDdStatement... dds) {
        return new JclStep("STEP010", JclExecKind.PGM, target, Optional.empty(), List.of(dds),
                position());
    }

    private static JclDdStatement dd(String ddName, String dsn, JclDisposition disposition) {
        return new JclDdStatement(ddName, Optional.of(dsn),
                Optional.of(new JclDataset(dsn)),
                Optional.of(disposition.raw()), Optional.of(disposition), Optional.empty(), false,
                Map.of("DSN", dsn, "DISP", disposition.raw()), Map.of(), List.of(), 0, position());
    }

    private static SourcePosition position() {
        return new SourcePosition("JOB1.jcl", 1, 1, SourcePosition.UNKNOWN_BYTE_OFFSET);
    }
}
