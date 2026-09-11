package jp.cobolinsight.frontend.cobol;

import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.FileAccess;
import jp.cobolinsight.core.semantic.FileDefinition;
import jp.cobolinsight.core.spi.ParseOutcome;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The FILE-CONTROL entries of a program against what its PROCEDURE DIVISION does with them: the DD
 * name of each form an ASSIGN clause takes, the ORGANIZATION as written, the mode of each OPEN, and
 * the USING and GIVING sides of a SORT.
 */
class FileDefinitionMappingTest {

    private static final String PROGRAM = """
            000100 IDENTIFICATION DIVISION.
            000200 PROGRAM-ID. FILEDEF.
            000300 ENVIRONMENT DIVISION.
            000400 INPUT-OUTPUT SECTION.
            000500 FILE-CONTROL.
            000600     SELECT INFILE  ASSIGN TO UT-S-ORDIN
            000700            ORGANIZATION IS SEQUENTIAL.
            000800     SELECT OUTFILE ASSIGN TO 'ORD-OUT'
            000900            ORGANIZATION IS RECORD SEQUENTIAL.
            001000     SELECT MASTER  ASSIGN TO EXTERNAL ORDMSTR
            001100            ORGANIZATION IS INDEXED
            001200            ACCESS MODE  IS DYNAMIC
            001300            RECORD KEY   IS MST-KEY.
            001400     SELECT TAPEFILE ASSIGN TO DYNAMIC WS-TAPE-NAME
            001500            ORGANIZATION IS BINARY SEQUENTIAL.
            001600     SELECT LOGFILE ASSIGN TO PRINTER.
            001700     SELECT SRTIN   ASSIGN TO SORTIN.
            001800     SELECT SRTOUT  ASSIGN TO SORTOUT.
            001900     SELECT WORKFILE ASSIGN TO SORTWK.
            001910     SELECT AUXIN1  ASSIGN TO AUX1
            001920            ORGANIZATION IS RECORD BINARY SEQUENTIAL.
            001930     SELECT AUXIN2  ASSIGN TO AUX2.
            002000 DATA DIVISION.
            002100 FILE SECTION.
            002200 FD  INFILE.
            002300 01  IN-REC                  PIC X(80).
            002400 FD  OUTFILE.
            002500 01  OUT-REC                 PIC X(80).
            002600 FD  MASTER.
            002700 01  MST-REC.
            002800     05  MST-KEY             PIC X(10).
            002900     05  MST-REST            PIC X(70).
            003000 FD  TAPEFILE.
            003100 01  TAPE-REC                PIC X(80).
            003200 FD  LOGFILE.
            003300 01  LOG-REC                 PIC X(132).
            003310 FD  AUXIN1.
            003320 01  AUX1-REC                PIC X(80).
            003330 FD  AUXIN2.
            003340 01  AUX2-REC                PIC X(80).
            003400 FD  SRTIN.
            003500 01  SRTIN-REC               PIC X(80).
            003600 FD  SRTOUT.
            003700 01  SRTOUT-REC              PIC X(80).
            003800 SD  WORKFILE.
            003900 01  WORK-REC.
            004000     05  WORK-KEY            PIC X(10).
            004100     05  WORK-REST           PIC X(70).
            004200 WORKING-STORAGE SECTION.
            004300 01  WS-TAPE-NAME            PIC X(8)  VALUE 'TAPEDD'.
            004400 PROCEDURE DIVISION.
            004500 0000-MAIN.
            004600     OPEN INPUT  INFILE.
            004700     OPEN OUTPUT OUTFILE.
            004800     OPEN I-O    MASTER.
            004900     OPEN EXTEND LOGFILE.
            004910     OPEN INPUT  AUXIN1 AUXIN2.
            005000     CLOSE INFILE.
            005100     CLOSE OUTFILE.
            005200     CLOSE MASTER.
            005300     CLOSE LOGFILE.
            005400     SORT WORKFILE ON ASCENDING KEY WORK-KEY
            005500          USING SRTIN GIVING SRTOUT.
            005600     STOP RUN.
            """;

    private static List<FileDefinition> files() {
        ParseOutcome<CobolSemanticModel> outcome = new Che4zCobolParser().parse(
                TestSources.fromText(TestSources.REPO_ROOT.resolve("FILEDEF.cbl").toString(),
                        PROGRAM),
                List.of());
        return outcome.value().orElseThrow(() -> new AssertionError(
                "FILEDEF.cbl のパースが失敗した: " + outcome.failureFinding().orElse(null))).files();
    }

    private static FileDefinition find(List<FileDefinition> files, String fileName) {
        return files.stream().filter(file -> file.fileName().equalsIgnoreCase(fileName))
                .findFirst().orElseThrow(() -> new AssertionError(
                        fileName + " の SELECT が見つからない: " + files));
    }

    @Test
    void everySelectEntryIsMappedInSourceOrder() {
        assertEquals(List.of("INFILE", "OUTFILE", "MASTER", "TAPEFILE", "LOGFILE", "SRTIN",
                "SRTOUT", "WORKFILE", "AUXIN1", "AUXIN2"),
                files().stream().map(FileDefinition::fileName).toList());
    }

    /** The last qualifier of a system name, and a literal whole because that is what the system reads. */
    @Test
    void theDdNameIsTheLastQualifierOfASystemNameAndAWholeLiteral() {
        List<FileDefinition> files = files();
        assertEquals(Optional.of("ORDIN"), find(files, "INFILE").ddName());
        assertEquals(Optional.of("ORD-OUT"), find(files, "OUTFILE").ddName(),
                "リテラルはハイフンごと保つ");
        assertEquals(Optional.of("SORTWK"), find(files, "WORKFILE").ddName());
    }

    /** EXTERNAL says how the assignment works, and the name after it is still the name. */
    @Test
    void theExternalKeywordBeforeTheNameIsSteppedOver() {
        assertEquals(Optional.of("ORDMSTR"), find(files(), "MASTER").ddName());
    }

    /**
     * A DYNAMIC or VARYING assignment names a data item holding the DD name at run time, so which
     * data set the program reads is not in the source and the entry carries no DD name.
     */
    @Test
    void aDynamicAssignmentYieldsNoDdName() {
        assertEquals(Optional.empty(), find(files(), "TAPEFILE").ddName(),
                "実行時に決まる割当てはDD名を持たないこと");
    }

    /** A clause naming only a device class points at no DD. */
    @Test
    void aBareDeviceClassYieldsNoDdName() {
        assertEquals(Optional.empty(), find(files(), "LOGFILE").ddName());
    }

    @Test
    void theOrganisationIsKeptAsWrittenIncludingItsPrefix() {
        List<FileDefinition> files = files();
        assertEquals(Optional.of("SEQUENTIAL"), find(files, "INFILE").organisation());
        assertEquals(Optional.of("RECORD SEQUENTIAL"), find(files, "OUTFILE").organisation());
        assertEquals(Optional.of("BINARY SEQUENTIAL"), find(files, "TAPEFILE").organisation());
        assertEquals(Optional.of("RECORD BINARY SEQUENTIAL"),
                find(files, "AUXIN1").organisation());
        assertEquals(Optional.of("INDEXED"), find(files, "MASTER").organisation());
        assertEquals(Optional.empty(), find(files, "WORKFILE").organisation());
    }

    @Test
    void everyOpenModeIsRecorded() {
        List<FileDefinition> files = files();
        assertEquals(Set.of(FileAccess.INPUT), find(files, "INFILE").accesses());
        assertEquals(Set.of(FileAccess.OUTPUT), find(files, "OUTFILE").accesses());
        assertEquals(Set.of(FileAccess.IO), find(files, "MASTER").accesses());
        assertEquals(Set.of(FileAccess.EXTEND), find(files, "LOGFILE").accesses());
    }

    /** One OPEN may name several files, and the mode belongs to every one of them. */
    @Test
    void anOpenNamingTwoFilesRecordsTheModeForBoth() {
        List<FileDefinition> files = files();
        assertEquals(Set.of(FileAccess.INPUT), find(files, "AUXIN1").accesses());
        assertEquals(Set.of(FileAccess.INPUT), find(files, "AUXIN2").accesses());
    }

    /** SRTIN and SRTOUT are named by USING and GIVING alone, so each side is the only evidence. */
    @Test
    void theSortSideIsRecordedForAFileNoOpenNames() {
        List<FileDefinition> files = files();
        assertEquals(Set.of(FileAccess.INPUT), find(files, "SRTIN").accesses());
        assertEquals(Set.of(FileAccess.OUTPUT), find(files, "SRTOUT").accesses());
        assertEquals(Set.of(), find(files, "WORKFILE").accesses(),
                "整列作業ファイルは OPEN も USING も GIVING も指さない");
    }

    @Test
    void theEntryCarriesTheLineItsSelectStandsOn() {
        assertEquals(6, find(files(), "INFILE").position().line());
    }
}
