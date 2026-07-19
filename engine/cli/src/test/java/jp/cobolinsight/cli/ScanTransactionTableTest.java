package jp.cobolinsight.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** トランザクション定義表CSV(INPUT_DIR/cics/*.csv)の読込の検証。 */
class ScanTransactionTableTest {

    @TempDir
    Path tempDir;

    private Path createAssetsWithCicsProgram() throws IOException {
        Path assets = tempDir.resolve("assets");
        Files.createDirectories(assets.resolve("cobol"));
        Files.createDirectories(assets.resolve("cics"));
        Files.writeString(assets.resolve("cobol").resolve("TRNHDR1.cbl"), String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID.  TRNHDR1.",
                "       PROCEDURE DIVISION.",
                "       MAIN-RTN.",
                "           EXEC CICS",
                "               START TRANSID('TRANID')",
                "           END-EXEC",
                "           EXEC CICS",
                "               START TRANSID('SYK1')",
                "           END-EXEC",
                "           GOBACK.",
                ""), StandardCharsets.UTF_8);
        return assets;
    }

    @Test
    void scanCompletesWhenTransactionCsvHasMalformedBytes() throws IOException {
        Path assets = createAssetsWithCicsProgram();
        // UTF-8として不正なバイト列のCSV(辞書順で先に読まれる名前)と、正常なCSVを並べる
        Files.write(assets.resolve("cics").resolve("aaa-broken.csv"),
                new byte[] {(byte) 0xFF, (byte) 0xFE, (byte) 0x80, 'S', 'Y', 'K'});
        Files.writeString(assets.resolve("cics").resolve("zzz-table.csv"), String.join("\n",
                "トランザクションID,プログラム名",
                "SYK1,TRNHDR1",
                ""), StandardCharsets.UTF_8);

        ScanRunner.Result result = ScanRunner.runWithGraph(new ScanRunner.Options(assets,
                tempDir.resolve("scan-broken.db"), List.of(), Map.of()));

        assertEquals(0, result.summary().exitCode(), "不正バイト列CSVがあってもscanが完走すること");
        assertTrue(result.callGraph().edges().stream().anyMatch(e ->
                        e.fromId().equals("transaction:SYK1")
                                && e.toId().equals("program:TRNHDR1")),
                "不正CSVを読み飛ばした後、残りのCSVの解決を継続すること: "
                        + result.callGraph().toJson());
    }

    @Test
    void firstCsvLineIsAlwaysSkippedAsHeader() throws IOException {
        Path assets = createAssetsWithCicsProgram();
        // 1行目のヘッダが英語8文字以内(資産名の形式に合致)でもデータ行として誤採用されないこと
        Files.writeString(assets.resolve("cics").resolve("table.csv"), String.join("\n",
                "TRANID,PROGRAM",
                "SYK1,TRNHDR1",
                ""), StandardCharsets.UTF_8);

        ScanRunner.Result result = ScanRunner.runWithGraph(new ScanRunner.Options(assets,
                tempDir.resolve("scan-header.db"), List.of(), Map.of()));

        assertTrue(result.callGraph().edges().stream().noneMatch(e ->
                        e.fromId().equals("transaction:TRANID")),
                "ヘッダ行 TRANID,PROGRAM を定義として採用しないこと: "
                        + result.callGraph().toJson());
        assertTrue(result.callGraph().nodes().stream().noneMatch(n ->
                n.id().equals("program:PROGRAM")), "ヘッダ由来のプログラムノードを作らないこと");
        assertTrue(result.callGraph().edges().stream().anyMatch(e ->
                        e.fromId().equals("transaction:SYK1")
                                && e.toId().equals("program:TRNHDR1")),
                "2行目以降のデータ行は解決されること");
    }
}
