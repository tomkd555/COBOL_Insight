package jp.cobolinsight.frontend.cobol;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Verifies original-text extraction: removal of fixed-format areas and copybook charset detection. */
class SourceTextsTest {

    private static final String MAIN_URI = "file:///main.cbl";

    @TempDir
    Path tempDir;

    @Test
    void sequenceAndIdentificationAreasAreRemovedFromMultiLineSpan() {
        String main = String.join("\n",
                fixedLine(100, "     EXEC SQL", "SEQFMT01"),
                fixedLine(200, "         SELECT COL1 INTO :WS-COL1 FROM TBL", "SEQFMT02"),
                fixedLine(300, "     END-EXEC", "SEQFMT03"));
        SourceTexts texts = new SourceTexts(MAIN_URI, main);

        String extracted = texts.extract(MAIN_URI,
                new Range(new Position(0, 11), new Position(2, 20)));

        assertFalse(extracted.contains("SEQFMT"), () -> "識別領域を含めないこと: " + extracted);
        assertFalse(extracted.contains("000200"), () -> "一連番号領域を含めないこと: " + extracted);
        assertEquals("EXEC SQL SELECT COL1 INTO :WS-COL1 FROM TBL END-EXEC",
                extracted.replaceAll("\\s+", " ").trim());
    }

    @Test
    void copybookIsDecodedByDetectedCodePage() throws IOException {
        Path copybook = tempDir.resolve("SJIS.cpy");
        Files.write(copybook, "       01  受注番号  PIC X(10).\n".getBytes(Charset.forName("Shift_JIS")));
        SourceTexts texts = new SourceTexts(MAIN_URI, "");

        String extracted = texts.extract(copybook.toUri().toString(),
                new Range(new Position(0, 11), new Position(0, 15)));

        assertEquals("受注番号", extracted);
    }

    @Test
    void byteOrderMarkDoesNotShiftTheFirstLine() throws IOException {
        Path copybook = tempDir.resolve("BOM.cpy");
        Files.write(copybook, "﻿       01  WS-A  PIC X(10).\n".getBytes(Charset.forName("UTF-8")));
        SourceTexts texts = new SourceTexts(MAIN_URI, "");

        String extracted = texts.extract(copybook.toUri().toString(),
                new Range(new Position(0, 11), new Position(0, 15)));

        assertEquals("WS-A", extracted);
    }

    /** Builds one fixed-format line: columns 1-6 are the sequence number, column 7 is the indicator, columns 8-72 are the body, and column 73 onward is the identification area. */
    private static String fixedLine(int sequence, String code, String identification) {
        String body = code.length() >= 65 ? code.substring(0, 65)
                : code + " ".repeat(65 - code.length());
        return String.format("%06d", sequence) + " " + body + identification;
    }
}
