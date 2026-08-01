package jp.cobolinsight.cli;

import jp.cobolinsight.persistence.PersistenceDao;
import jp.cobolinsight.persistence.PersistenceDatabase;
import jp.cobolinsight.persistence.model.LineMapRecord;
import jp.cobolinsight.persistence.model.SourceRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * translate サブコマンドの picocli 配線・生成ファイル出力・LINE_MAP 永続化・決定論・言語選択・
 * 終了コードを、samples/ を対象に end-to-end で検証する。
 */
class TranspileCommandTest {

    private static final Path SAMPLES = Path.of("..", "..", "..", "samples").toAbsolutePath().normalize();

    @TempDir
    Path tempDir;

    private int transpile(Path out, Path db, String... extra) {
        java.util.List<String> args = new java.util.ArrayList<>(List.of(
                "translate", SAMPLES.toString(), "--out", out.toString(), "--db", db.toString()));
        args.addAll(List.of(extra));
        return new CommandLine(new Main()).execute(args.toArray(new String[0]));
    }

    private Map<String, Long> sourceIdByPath(PersistenceDao dao) {
        return dao.findAllSources().stream()
                .collect(Collectors.toMap(SourceRecord::path, SourceRecord::id));
    }

    @Test
    void transpilesAllSamplesWritingFilesAndPersistingLineMap() throws IOException {
        Path out = tempDir.resolve("out");
        Path db = tempDir.resolve("t.db");

        int exitCode = transpile(out, db);

        assertEquals(0, exitCode, "復号・パース失敗が無ければ成功(0)");
        try (Stream<Path> pyFiles = Files.walk(out)) {
            assertTrue(pyFiles.anyMatch(p -> p.getFileName().toString().endsWith("_program.py")),
                    "Python プログラムファイルが出力されること");
        }
        try (Stream<Path> javaFiles = Files.walk(out)) {
            assertTrue(javaFiles.anyMatch(p -> p.getFileName().toString().endsWith("Program.java")),
                    "Java プログラムファイルが出力されること(既定 both)");
        }

        try (PersistenceDatabase database = PersistenceDatabase.open(db)) {
            PersistenceDao dao = new PersistenceDao(database.connection());
            Map<String, Long> ids = sourceIdByPath(dao);
            long syk006 = ids.get("cobol/SYK006.cbl");
            List<LineMapRecord> syk006Maps = dao.findLineMapsBySource(syk006);
            assertFalse(syk006Maps.isEmpty(), "SYK006 の行対応が LINE_MAP に載ること");
            // 直訳できない PERFORM UNTIL 条件は、注記付きで行対応へ載る。
            assertTrue(syk006Maps.stream().anyMatch(
                    m -> m.note().contains("直訳不能") && m.note().contains("SQLCODE")),
                    "直訳不能条件(SQLCODE)の注記が LINE_MAP に載ること: " + syk006Maps);
            // コピー句由来のレコードクラス対応も、コピー句ソースへ紐づいて載ること。
            long copybook = ids.get("copybook/SYKCPY3.cpy");
            assertFalse(dao.findLineMapsBySource(copybook).isEmpty(),
                    "コピー句 SYKCPY3 由来の行対応がコピー句ソースへ紐づいて載ること");
        }
    }

    @Test
    void rerunIsIdempotentForLineMapRows() {
        Path out = tempDir.resolve("out2");
        Path db = tempDir.resolve("t2.db");
        transpile(out, db);
        int firstCount;
        try (PersistenceDatabase database = PersistenceDatabase.open(db)) {
            firstCount = totalLineMaps(new PersistenceDao(database.connection()));
        }
        assertEquals(0, transpile(out, db), "2回目も成功");
        try (PersistenceDatabase database = PersistenceDatabase.open(db)) {
            assertEquals(firstCount, totalLineMaps(new PersistenceDao(database.connection())),
                    "再実行で LINE_MAP 行が重複せず同数であること");
        }
    }

    @Test
    void languageOptionSelectsOnlyRequestedLanguage() throws IOException {
        Path out = tempDir.resolve("outpy");
        Path db = tempDir.resolve("t3.db");

        assertEquals(0, transpile(out, db, "--language", "python"));

        try (Stream<Path> files = Files.walk(out)) {
            List<String> names = files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString()).toList();
            assertTrue(names.stream().anyMatch(n -> n.endsWith(".py")), "Python が出ること");
            assertFalse(names.stream().anyMatch(n -> n.endsWith(".java")),
                    "python 指定では Java を出さないこと: " + names);
        }
    }

    @Test
    void invalidLanguageIsRejected() {
        int exitCode = transpile(tempDir.resolve("outx"), tempDir.resolve("t4.db"),
                "--language", "ruby");
        assertEquals(2, exitCode, "不正な --language は使用方法エラー(2)で拒否されること");
    }

    @Test
    void generatedPythonProgramContainsRawLoopCondition() throws IOException {
        Path out = tempDir.resolve("outsyk006");
        transpile(out, tempDir.resolve("t5.db"), "--language", "python");
        Path program = out.resolve("SYK006_program.py");
        assertTrue(Files.exists(program), "SYK006 の Python プログラムが出力されること");
        String content = Files.readString(program, StandardCharsets.UTF_8);
        assertTrue(content.contains("while not (SQLCODE == 100):"),
                "直訳不能条件が逐語で描画されること: " + content);
    }

    private static int totalLineMaps(PersistenceDao dao) {
        int total = 0;
        for (SourceRecord source : dao.findAllSources()) {
            total += dao.findLineMapsBySource(source.id()).size();
        }
        return total;
    }
}
