package jp.cobolinsight.fix;

import jp.cobolinsight.engineapi.finding.Finding;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 再パース検証ゲート。AnalysisServices.load() が ServiceLoader で束ねる CobolParser 実装で
 * 修正後ソースを再パースし、成否を返すことを検証する。samples の無編集は成功し、壊れたソースは失敗する。
 */
class ReparseVerifierTest {

    private static final Path SAMPLES = Path.of("..", "..", "samples");

    private final ReparseVerifier verifier = new ReparseVerifier();

    @Test
    void unmodifiedSampleReparsesSuccessfully() throws IOException {
        Path file = SAMPLES.resolve("cobol").resolve("SYK001.cbl");
        byte[] bytes = Files.readAllBytes(file);
        ReparseResult result = verifier.verify(file.toAbsolutePath().toString(), bytes, "UTF-8",
                List.of(SAMPLES.resolve("copybook")));

        assertTrue(result.success(),
                () -> "無編集の samples は再パース成功: " + result.errorFinding().orElse(null));
        assertTrue(result.errorFinding().isEmpty());
    }

    @Test
    void brokenSourceIsDetectedAsFailure() {
        String broken = "       IDENTIFICATION DIVISION.\n"
                + "       PROGRAM-ID.  BROKEN.\n"
                + "       PROCEDURE DIVISION.\n"
                + "       0000-MAIN.\n"
                + "           MOVE TO .\n";

        ReparseResult result = verifier.verify("BROKEN.cbl", broken, List.of());

        assertFalse(result.success(), "構文誤りは再パース失敗として検出する");
        Finding finding = result.errorFinding().orElseThrow();
        assertEquals(Finding.PARSE_FAILURE_RULE_ID, finding.ruleId());
    }

    @Test
    void foldedR017HandlerReparsesSuccessfully() {
        // 語境界で2行へ折り返した R017 ハンドラが、挿入後に正しい COBOL として再パースできることを確認する。
        String handler =
                "IF WS-ORDIN-STATUS NOT = '00' DISPLAY 'FILE ERROR: ORDIN ' WS-ORDIN-STATUS END-IF";
        List<String> handlerLines =
                new FixedFormatNormalizer().layoutStatement(handler, StandardCharsets.UTF_8);
        assertEquals(2, handlerLines.size(), "R017 ハンドラは B領域予算超過で2行へ折り返す");

        List<String> program = new ArrayList<>();
        program.add("       IDENTIFICATION DIVISION.");
        program.add("       PROGRAM-ID. FOLDCHK.");
        program.add("       DATA DIVISION.");
        program.add("       WORKING-STORAGE SECTION.");
        program.add("       01  WS-ORDIN-STATUS PIC X(02).");
        program.add("       PROCEDURE DIVISION.");
        program.add("       0000-MAIN.");
        program.addAll(handlerLines);
        program.add("           STOP RUN.");
        String text = String.join("\n", program) + "\n";

        ReparseResult result = verifier.verify("FOLDCHK.cbl", text, List.of());

        assertTrue(result.success(),
                () -> "折り返した R017 ハンドラは再パース可能: " + result.errorFinding().orElse(null));
    }
}
