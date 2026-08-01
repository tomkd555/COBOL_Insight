package jp.cobolinsight.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 資産の走査({@link SourceDiscovery})の検証。従来のフォルダ規約を優先し、それで1件も
 * 拾えないときだけ再帰探索へ落ちること、規約外に残る対象ファイルを数え上げることを確かめる。
 */
class SourceDiscoveryTest {

    @TempDir
    Path tempDir;

    private static void write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "dummy\n", StandardCharsets.UTF_8);
    }

    private static List<String> relPaths(SourceDiscovery.Result result) {
        return result.files().stream().map(SourceDiscovery.DiscoveredFile::relPath).toList();
    }

    @Test
    void conventionLayoutCollectsOnlyConventionDirectories() throws IOException {
        Path assets = tempDir.resolve("assets");
        write(assets.resolve("cobol").resolve("A.cbl"));
        write(assets.resolve("copybook").resolve("B.cpy"));
        write(assets.resolve("jcl").resolve("C.jcl"));
        write(assets.resolve("bms").resolve("D.bms"));

        SourceDiscovery.Result result = SourceDiscovery.discover(assets);

        assertEquals(SourceDiscovery.Mode.CONVENTION, result.mode());
        assertEquals(List.of("bms/D.bms", "cobol/A.cbl", "copybook/B.cpy", "jcl/C.jcl"),
                relPaths(result), "相対パスの辞書順で返すこと");
        assertFalse(result.truncated());
        assertEquals(List.of(), result.outsideConvention());
    }

    @Test
    void conventionLayoutIgnoresNestedFilesAndReportsThemAsOutside() throws IOException {
        Path assets = tempDir.resolve("assets");
        write(assets.resolve("cobol").resolve("A.cbl"));
        // 規約ディレクトリは1階層しか見ないため、その配下のサブフォルダは対象外になる。
        write(assets.resolve("cobol").resolve("old").resolve("OLD.cbl"));
        write(assets.resolve("encoding").resolve("E.cbl"));

        SourceDiscovery.Result result = SourceDiscovery.discover(assets);

        assertEquals(List.of("cobol/A.cbl"), relPaths(result));
        assertEquals(List.of("cobol/old/OLD.cbl", "encoding/E.cbl"), result.outsideConvention(),
                "走査対象から漏れた対象拡張子のファイルを数え上げること");
    }

    @Test
    void conventionLayoutReportsFilesInNonConventionDirectory() throws IOException {
        Path assets = tempDir.resolve("assets");
        write(assets.resolve("cobol").resolve("A.cbl"));
        write(assets.resolve("COPYLIB").resolve("B.cpy"));
        write(assets.resolve("COPYLIB").resolve("C.cpy"));

        SourceDiscovery.Result result = SourceDiscovery.discover(assets);

        assertEquals(SourceDiscovery.Mode.CONVENTION, result.mode());
        assertEquals(List.of("cobol/A.cbl"), relPaths(result));
        assertEquals(List.of("COPYLIB/B.cpy", "COPYLIB/C.cpy"), result.outsideConvention());
    }

    @Test
    void fallsBackToRecursiveWhenConventionYieldsNothing() throws IOException {
        Path assets = tempDir.resolve("assets");
        write(assets.resolve("A.cbl"));
        write(assets.resolve("B.jcl"));

        SourceDiscovery.Result result = SourceDiscovery.discover(assets);

        assertEquals(SourceDiscovery.Mode.RECURSIVE, result.mode());
        assertEquals(List.of("A.cbl", "B.jcl"), relPaths(result));
        assertEquals(List.of(), result.outsideConvention(),
                "再帰で全て拾うため規約外の残余は無いこと");
    }

    @Test
    void fallsBackToRecursiveWhenConventionDirectoryIsEmpty() throws IOException {
        Path assets = tempDir.resolve("assets");
        Files.createDirectories(assets.resolve("cobol"));
        write(assets.resolve("src").resolve("A.cbl"));

        SourceDiscovery.Result result = SourceDiscovery.discover(assets);

        assertEquals(SourceDiscovery.Mode.RECURSIVE, result.mode(),
                "規約ディレクトリが空なら再帰へ落ちること");
        assertEquals(List.of("src/A.cbl"), relPaths(result));
    }

    @Test
    void recursiveCollectsNestedFilesAndAlternativeExtensions() throws IOException {
        Path assets = tempDir.resolve("assets");
        write(assets.resolve("src").resolve("main").resolve("A.cob"));
        write(assets.resolve("src").resolve("B.cobol"));
        write(assets.resolve("inc").resolve("C.copy"));
        write(assets.resolve("D.CBL"));

        SourceDiscovery.Result result = SourceDiscovery.discover(assets);

        assertEquals(List.of("D.CBL", "inc/C.copy", "src/B.cobol", "src/main/A.cob"),
                relPaths(result), "拡張子は大小を無視し、区切りは / へそろえること");
        assertEquals(SourceDiscovery.Kind.COPYBOOK,
                result.files().stream().filter(f -> f.relPath().equals("inc/C.copy"))
                        .findFirst().orElseThrow().kind());
    }

    @Test
    void recursiveIgnoresExcludedDirectories() throws IOException {
        Path assets = tempDir.resolve("assets");
        write(assets.resolve("A.cbl"));
        write(assets.resolve("node_modules").resolve("X.cbl"));
        write(assets.resolve("build").resolve("Y.cbl"));
        write(assets.resolve(".git").resolve("Z.cbl"));

        SourceDiscovery.Result result = SourceDiscovery.discover(assets);

        assertEquals(List.of("A.cbl"), relPaths(result));
    }

    @Test
    void recursiveStopsAtDepthLimitAndMarksTruncated() throws IOException {
        Path assets = tempDir.resolve("assets");
        Path deep = assets;
        for (int i = 0; i <= SourceDiscovery.MAX_DEPTH; i++) {
            deep = deep.resolve("d" + i);
        }
        write(deep.resolve("DEEP.cbl"));
        write(assets.resolve("d0").resolve("SHALLOW.cbl"));

        SourceDiscovery.Result result = SourceDiscovery.discover(assets);

        assertEquals(List.of("d0/SHALLOW.cbl"), relPaths(result));
        assertTrue(result.truncated(), "深さ上限で打ち切ったことを伝えること");
    }

    @Test
    void missingInputDirectoryYieldsEmptyResult() {
        SourceDiscovery.Result result = SourceDiscovery.discover(tempDir.resolve("absent"));

        assertEquals(List.of(), relPaths(result));
        assertFalse(result.truncated());
        assertEquals(List.of(), result.outsideConvention());
    }
}
