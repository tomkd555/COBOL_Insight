package jp.cobolinsight.engineapi.transpile;

import jp.cobolinsight.engineapi.linemap.LineMappingEntry;
import jp.cobolinsight.engineapi.linemap.MappingKind;
import jp.cobolinsight.engineapi.source.LineRange;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranspileResultTest {

    @Test
    void targetLanguageExposesFileExtension() {
        assertEquals("py", TargetLanguage.PYTHON.fileExtension());
        assertEquals("java", TargetLanguage.JAVA.fileExtension());
        assertEquals(2, TargetLanguage.values().length);
    }

    @Test
    void generatedFileHoldsNameAndContent() {
        GeneratedFile file = new GeneratedFile("SYK001.py", "print('hi')\n");
        assertEquals("SYK001.py", file.fileName());
        assertEquals("print('hi')\n", file.content());
    }

    @Test
    void generatedFileRejectsBlankName() {
        assertThrows(IllegalArgumentException.class, () -> new GeneratedFile(" ", "x"));
    }

    @Test
    void generatedFileAllowsEmptyContent() {
        assertEquals("", new GeneratedFile("empty.py", "").content());
    }

    @Test
    void resultHoldsProgramLanguageFilesAndLineMap() {
        GeneratedFile file = new GeneratedFile("SYK001.py", "pass\n");
        LineMappingEntry entry = new LineMappingEntry("SYK001.cbl", new LineRange(1, 1),
                "SYK001.py", new LineRange(1, 1), MappingKind.ONE_TO_ONE, "", "SYK001.cbl#0001");
        TranspileResult result = new TranspileResult("SYK001", TargetLanguage.PYTHON,
                List.of(file), List.of(entry));
        assertEquals("SYK001", result.programId());
        assertEquals(TargetLanguage.PYTHON, result.language());
        assertEquals(1, result.files().size());
        assertEquals(1, result.lineMap().size());
        assertEquals("SYK001.py", result.files().get(0).fileName());
    }

    @Test
    void resultCopiesListsDefensively() {
        List<GeneratedFile> files = new ArrayList<>();
        files.add(new GeneratedFile("SYK001.py", "pass\n"));
        TranspileResult result = new TranspileResult("SYK001", TargetLanguage.PYTHON,
                files, List.of());
        files.clear();
        assertEquals(1, result.files().size());
        assertThrows(UnsupportedOperationException.class,
                () -> result.files().add(new GeneratedFile("x.py", "")));
    }

    @Test
    void resultRejectsBlankProgramId() {
        assertThrows(IllegalArgumentException.class, () -> new TranspileResult(" ",
                TargetLanguage.JAVA, List.of(), List.of()));
    }

    @Test
    void resultAllowsEmptyFilesAndLineMap() {
        TranspileResult result = new TranspileResult("SYK001", TargetLanguage.JAVA,
                List.of(), List.of());
        assertTrue(result.files().isEmpty());
        assertTrue(result.lineMap().isEmpty());
    }
}
