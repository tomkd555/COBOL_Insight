package jp.cobolinsight.app.pipeline;

import jp.cobolinsight.core.source.AssetKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 資産の走査({@link SourceDiscovery})の検証。フォルダの形に依らず内容から種別を逆算すること、
 * 取りこぼしと解釈の変更を全件報告することを確かめる。
 */
class SourceDiscoveryTest {

    /** COBOL 本体・コピー句・JCL・BMS の最小の本文。内容判定が確定する形にしてある。 */
    private static final String COBOL = """
                   IDENTIFICATION DIVISION.
                   PROGRAM-ID.  SAMPLE.
                   PROCEDURE DIVISION.
                       STOP RUN.
            """;
    private static final String COPYBOOK = """
                   01  SAMPLE-RECORD.
                       05  SAMPLE-KEY     PIC X(10).
            """;
    private static final String JCL = """
            //SAMPLE   JOB  (ACCT),'TEST',CLASS=A
            //STEP010  EXEC PGM=SAMPLE
            """;
    private static final String BMS = """
            SAMPMAP  DFHMSD TYPE=&SYSPARM,MODE=INOUT
            """;

    @TempDir
    Path tempDir;

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static List<String> relPaths(SourceDiscovery.Result result) {
        return result.files().stream().map(SourceDiscovery.DiscoveredFile::relPath).toList();
    }

    private static AssetKind kindOf(SourceDiscovery.Result result, String relPath) {
        return result.files().stream().filter(f -> f.relPath().equals(relPath))
                .findFirst().orElseThrow().kind();
    }

    @Test
    void collectsAssetsRegardlessOfFolderNames() throws IOException {
        Path assets = tempDir.resolve("assets");
        write(assets.resolve("なんでもよい名前").resolve("A.cbl"), COBOL);
        write(assets.resolve("ライブラリ").resolve("B.cpy"), COPYBOOK);
        write(assets.resolve("C.jcl"), JCL);
        write(assets.resolve("画面").resolve("深い").resolve("D.bms"), BMS);

        SourceDiscovery.Result result = SourceDiscovery.discover(assets);

        assertEquals(List.of("C.jcl", "なんでもよい名前/A.cbl", "ライブラリ/B.cpy", "画面/深い/D.bms"),
                relPaths(result).stream().sorted().toList(),
                "フォルダ名に依らず拾うこと");
        assertEquals(4, result.files().size());
        assertFalse(result.truncated());
        assertEquals(List.of(), result.undecided());
        assertEquals(List.of(), result.mismatches());
    }

    @Test
    void filesAreReturnedInLexicographicRelativePathOrder() throws IOException {
        Path assets = tempDir.resolve("assets");
        write(assets.resolve("z").resolve("A.cbl"), COBOL);
        write(assets.resolve("a").resolve("B.cbl"), COBOL);
        write(assets.resolve("A.jcl"), JCL);

        assertEquals(List.of("A.jcl", "a/B.cbl", "z/A.cbl"),
                relPaths(SourceDiscovery.discover(assets)),
                "SOURCE.id をパス順に振る不変条件の前提となる並びであること");
    }

    @Test
    void contentDecidesTheKindWhenTheExtensionDisagrees() throws IOException {
        Path assets = tempDir.resolve("assets");
        write(assets.resolve("X.cpy"), COBOL);

        SourceDiscovery.Result result = SourceDiscovery.discover(assets);

        assertEquals(AssetKind.COBOL, kindOf(result, "X.cpy"), "内容を採ること");
        assertEquals(List.of(new SourceDiscovery.KindMismatch("X.cpy", AssetKind.COPYBOOK,
                AssetKind.COBOL)), result.mismatches());
        assertEquals(SourceDiscovery.Evidence.CONTENT,
                result.files().get(0).evidence());
    }

    @Test
    void unknownExtensionIsCollectedWhenTheContentDecides() throws IOException {
        Path assets = tempDir.resolve("assets");
        write(assets.resolve("SAMPLE"), COBOL);
        write(assets.resolve("SAMPLE.src"), JCL);

        SourceDiscovery.Result result = SourceDiscovery.discover(assets);

        assertEquals(List.of("SAMPLE", "SAMPLE.src"), relPaths(result));
        assertEquals(AssetKind.COBOL, kindOf(result, "SAMPLE"));
        assertEquals(AssetKind.JCL, kindOf(result, "SAMPLE.src"));
        assertEquals(List.of(), result.mismatches(), "拡張子が未知なら食い違いにはならないこと");
    }

    @Test
    void knownExtensionIsUsedWhenTheContentDoesNotDecide() throws IOException {
        Path assets = tempDir.resolve("assets");
        // 判定の根拠を持たない本文。既知拡張子の場合だけ拡張子の種別を採る。
        write(assets.resolve("FRAGMENT.cpy"), "            MOVE ZERO TO WS-COUNT\n");

        SourceDiscovery.Result result = SourceDiscovery.discover(assets);

        assertEquals(List.of("FRAGMENT.cpy"), relPaths(result));
        assertEquals(AssetKind.COPYBOOK, kindOf(result, "FRAGMENT.cpy"));
        assertEquals(SourceDiscovery.Evidence.EXTENSION, result.files().get(0).evidence());
        assertEquals(List.of(), result.undecided());
    }

    @Test
    void undecidedFilesAreReportedInFullAndLeftOut() throws IOException {
        Path assets = tempDir.resolve("assets");
        write(assets.resolve("A.cbl"), COBOL);
        for (int i = 1; i <= 12; i++) {
            write(assets.resolve("note" + i + ".memo"), "ただのテキスト\n");
        }

        SourceDiscovery.Result result = SourceDiscovery.discover(assets);

        assertEquals(List.of("A.cbl"), relPaths(result));
        assertEquals(12, result.undecided().size(),
                "報告を件数で打ち切らず全件返すこと");
        assertTrue(result.undecided().contains("note12.memo"));
    }

    @Test
    void excludedExtensionsAreNotCandidates() throws IOException {
        Path assets = tempDir.resolve("assets");
        write(assets.resolve("A.cbl"), COBOL);
        // 資産についての文書。内容に COBOL の語が現れても対象にしない。
        write(assets.resolve("findings.sarif"), COBOL);
        write(assets.resolve("report.html"), COBOL);
        write(assets.resolve("summary.json"), COBOL);
        write(assets.resolve("README.md"), COBOL);

        SourceDiscovery.Result result = SourceDiscovery.discover(assets);

        assertEquals(List.of("A.cbl"), relPaths(result));
        assertEquals(List.of(), result.undecided(), "候補にしないものは未確定でもないこと");
    }

    @Test
    void binaryFilesAreNeitherCollectedNorReportedAsUndecided() throws IOException {
        Path assets = tempDir.resolve("assets");
        write(assets.resolve("A.cbl"), COBOL);
        Files.write(assets.resolve("blob"), new byte[] {1, 2, 0, 3, 4});

        SourceDiscovery.Result result = SourceDiscovery.discover(assets);

        assertEquals(List.of("A.cbl"), relPaths(result));
        assertEquals(List.of(), result.undecided(),
                "NUL を含むファイルはテキストでないと確定した結果であり、取りこぼしではないこと");
    }

    @Test
    void transactionTableIsFoundOutsideTheCicsFolder() throws IOException {
        Path assets = tempDir.resolve("assets");
        write(assets.resolve("定義").resolve("トランザクション.csv"),
                "トランザクションID,プログラム名\nSYK8,SYK008\n");
        write(assets.resolve("other").resolve("無関係.csv"),
                "見出し1,見出し2\nこれは資産名の形式ではない値,別の値\n");

        SourceDiscovery.Result result = SourceDiscovery.discover(assets);

        assertEquals(1, result.transactionTables().size(), "位置ではなく中身で表とみなすこと");
        assertEquals("トランザクション.csv",
                result.transactionTables().get(0).getFileName().toString());
        assertEquals(List.of(), relPaths(result), "CSV は資産のソースにはしないこと");
        assertEquals(List.of(), result.undecided());
    }

    @Test
    void excludedDirectoriesAreNotEntered() throws IOException {
        Path assets = tempDir.resolve("assets");
        write(assets.resolve("A.cbl"), COBOL);
        write(assets.resolve("node_modules").resolve("X.cbl"), COBOL);
        write(assets.resolve("build").resolve("Y.cbl"), COBOL);
        write(assets.resolve(".git").resolve("Z.cbl"), COBOL);

        assertEquals(List.of("A.cbl"), relPaths(SourceDiscovery.discover(assets)));
    }

    /** 深さの上限を持たないことの歯止め。11段より深い位置の資産も拾う。 */
    @Test
    void assetsDeeperThanTenLevelsAreCollected() throws IOException {
        Path assets = tempDir.resolve("assets");
        Path deep = assets;
        for (int i = 0; i < 15; i++) {
            deep = deep.resolve("d" + i);
        }
        write(deep.resolve("DEEP.cbl"), COBOL);

        SourceDiscovery.Result result = SourceDiscovery.discover(assets);

        assertEquals(1, result.files().size(), "深さで打ち切らないこと");
        assertTrue(relPaths(result).get(0).endsWith("d14/DEEP.cbl"));
        assertFalse(result.truncated());
    }

    /**
     * 残す唯一の上限({@code MAX_FILES})に達したときは、必ず {@code truncated} で伝えること。
     * この上限は SOURCE.id が 1,000,000 未満という採番の不変条件に由来する。
     */
    @Test
    void reachingTheFileLimitIsReportedAsTruncated() throws IOException {
        Path assets = tempDir.resolve("assets");
        Files.createDirectories(assets);
        for (int i = 0; i <= SourceDiscovery.MAX_FILES; i++) {
            Files.writeString(assets.resolve("A" + i + ".cbl"), COBOL, StandardCharsets.UTF_8);
        }

        SourceDiscovery.Result result = SourceDiscovery.discover(assets);

        assertEquals(SourceDiscovery.MAX_FILES, result.files().size());
        assertTrue(result.truncated(), "上限で打ち切ったことを伝えること");
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("上限")),
                result.warnings().toString());
    }

    @Test
    void filesOfNarrowsToTheRequestedKinds() throws IOException {
        Path assets = tempDir.resolve("assets");
        write(assets.resolve("A.cbl"), COBOL);
        write(assets.resolve("B.cpy"), COPYBOOK);
        write(assets.resolve("C.jcl"), JCL);

        SourceDiscovery.Result result = SourceDiscovery.discover(assets);

        assertEquals(List.of("A.cbl", "B.cpy"),
                result.filesOf(Set.of(AssetKind.COBOL, AssetKind.COPYBOOK)).stream()
                        .map(SourceDiscovery.DiscoveredFile::relPath).toList());
    }

    @Test
    void warningsCoverUndecidedAndMismatch() throws IOException {
        Path assets = tempDir.resolve("assets");
        write(assets.resolve("X.cpy"), COBOL);
        write(assets.resolve("memo.text"), "ただのテキスト\n");

        List<String> warnings = SourceDiscovery.discover(assets).warnings();

        assertEquals(2, warnings.size());
        assertTrue(warnings.get(0).contains("memo.text"), warnings.get(0));
        assertTrue(warnings.get(1).contains("X.cpy"), warnings.get(1));
    }

    @Test
    void missingInputDirectoryYieldsEmptyResult() {
        SourceDiscovery.Result result = SourceDiscovery.discover(tempDir.resolve("absent"));

        assertEquals(List.of(), relPaths(result));
        assertFalse(result.truncated());
        assertEquals(List.of(), result.undecided());
        assertEquals(List.of(), result.mismatches());
        assertEquals(List.of(), result.unreadable());
        assertEquals(List.of(), result.warnings());
    }
}
