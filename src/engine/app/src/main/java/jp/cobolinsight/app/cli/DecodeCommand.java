package jp.cobolinsight.app.cli;

import jp.cobolinsight.app.persistence.PersistenceDao;
import jp.cobolinsight.app.persistence.PersistenceDatabase;
import jp.cobolinsight.app.persistence.model.EncodingInfoRecord;
import jp.cobolinsight.app.persistence.model.SourceRecord;
import jp.cobolinsight.app.pipeline.Failures;
import jp.cobolinsight.app.pipeline.Paths;
import jp.cobolinsight.core.encoding.ByteOffsetTable;
import jp.cobolinsight.core.encoding.CodePage;
import jp.cobolinsight.core.encoding.DecodedSource;
import jp.cobolinsight.core.encoding.SourceDecoder;
import jp.cobolinsight.core.json.JsonWriter;
import jp.cobolinsight.core.pipeline.ExitCodes;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * `decode`. Decodes a single source using its original code page and writes the text plus the
 * per-line column boundaries to JSON.
 *
 * <p>The GUI's source viewer colors in the fixed-format regions (sequence number, indicator,
 * area A, area B, identification), but those boundaries are determined by <b>byte column</b>. On
 * lines that contain double-byte characters, the character count and the byte column do not
 * match, so counting on the GUI side would put the regions in the wrong place. Computing the
 * boundaries is the engine's job, since it holds the byte offset table.
 */
@Command(name = "decode", mixinStandardHelpOptions = true,
        description = "1本の原始プログラムを復号し、本文と固定形式の各領域の境界を JSON で書き出す")
public final class DecodeCommand implements Callable<Integer> {

    /** The boundaries used to color in the columns: the byte column (1-based) where each fixed-format region starts. */
    private static final int[] BOUNDARY_COLUMNS = {7, 8, 12, 73};

    @Option(names = "--file", paramLabel = "FILE", required = true,
            description = "復号する原本(絶対パス)")
    Path file;

    @Option(names = "--codepage", paramLabel = "CHARSET",
            description = "コードページ手動指定。自動判別とプロジェクトファイルの記録に優先する")
    String codepage;

    @Option(names = "--db", paramLabel = "FILE",
            description = "プロジェクトファイル。走査時に記録したコードページを引く")
    Path databaseFile;

    @Option(names = "--out", paramLabel = "FILE", required = true,
            description = "復号結果の JSON 出力先")
    Path outFile;

    @Override
    public Integer call() {
        Path target = file.toAbsolutePath().normalize();
        try {
            Paths.writeString(outFile, decode(target));
            return ExitCodes.SUCCESS;
        } catch (IOException | RuntimeException e) {
            // The remedy is offered only when a different code page could help.
            String remedy = Failures.codepageRelated(e) ? "文字コードを選び直してください。" : "";
            Paths.writeString(outFile, errorJson(Failures.describe(e) + "。" + remedy));
            return ExitCodes.ERRORS;
        }
    }

    private String decode(Path target) throws IOException {
        byte[] bytes = Files.readAllBytes(target);
        String charsetName = resolveCodepage(target);
        SourceDecoder decoder = new SourceDecoder();
        DecodedSource decoded = charsetName == null
                ? decoder.decode(bytes)
                : decoder.decode(bytes, CodePage.fromName(charsetName));

        JsonWriter writer = new JsonWriter();
        writer.beginObject()
                .name("text").value(decoded.text())
                .name("codepage").value(decoded.encodingInfo().codePage().charsetName())
                .name("detected").value(!decoded.encodingInfo().manualOverride())
                .name("soSiPresent").value(decoded.encodingInfo().soSiPresent())
                .name("lines").beginArray();
        ByteOffsetTable table = decoded.offsetTable();
        for (int line = 1; line <= table.lineCount(); line++) {
            int lineStartByte = table.lineStartByteOffset(line);
            int lineEndByte = line < table.lineCount()
                    ? table.lineStartByteOffset(line + 1) : table.byteLength();
            int lineStartChar = table.lineStartCharIndex(line);
            int lineEndChar = line < table.lineCount()
                    ? table.lineStartCharIndex(line + 1) : table.charCount();
            writer.beginObject()
                    .name("byteLength").value(lineEndByte - lineStartByte)
                    .name("boundaries").beginArray();
            for (int column : BOUNDARY_COLUMNS) {
                writer.value(charIndexAtByteColumn(table, lineStartByte, lineStartChar,
                        lineEndChar, column));
            }
            writer.endArray().endObject();
        }
        writer.endArray()
                .name("stamp").beginObject()
                .name("mtimeMs").value(Files.getLastModifiedTime(target).toMillis())
                .name("byteSize").value(bytes.length)
                .endObject()
                .name("error").value("")
                .endObject();
        return writer.toString();
    }

    /**
     * Within a line, the UTF-16 character position (relative to the start of the line) of the
     * first character whose byte offset reaches or passes the 1-based byte column {@code column}.
     * Returns -1 for a line too short to reach that column. When {@code column} falls in the
     * middle of a double-byte character, that character's own byte offset is still short of the
     * target, so it is skipped; the result then points to the character immediately following it,
     * not to the double-byte character {@code column} falls inside.
     */
    private static int charIndexAtByteColumn(ByteOffsetTable table, int lineStartByte,
            int lineStartChar, int lineEndChar, int column) {
        int wanted = lineStartByte + column - 1;
        for (int charIndex = lineStartChar; charIndex < lineEndChar; charIndex++) {
            if (table.byteOffsetOfChar(charIndex) >= wanted) {
                return charIndex - lineStartChar;
            }
        }
        return -1;
    }

    /**
     * The code page name used for decoding. The manually specified value takes highest priority,
     * then the value the project file recorded at scan time; if neither is available, returns
     * null to leave it to auto-detection (the same resolution order as {@code save}).
     */
    private String resolveCodepage(Path target) {
        if (codepage != null) {
            return codepage;
        }
        if (databaseFile == null || !Files.isRegularFile(databaseFile)) {
            return null;
        }
        try (PersistenceDatabase database = PersistenceDatabase.open(databaseFile)) {
            PersistenceDao dao = new PersistenceDao(database.connection());
            for (SourceRecord source : dao.findAllSources()) {
                if (!Path.of(source.root()).resolve(source.path()).normalize().equals(target)) {
                    continue;
                }
                Optional<EncodingInfoRecord> encoding = dao.findEncodingInfo(source.id());
                return encoding.map(EncodingInfoRecord::detectedCharset).orElse(source.codepage());
            }
        }
        return null;
    }

    private static String errorJson(String message) {
        JsonWriter writer = new JsonWriter();
        writer.beginObject()
                .name("text").value("")
                .name("codepage").value("")
                .name("detected").value(false)
                .name("soSiPresent").value(false)
                .name("lines").beginArray().endArray()
                .name("stamp").beginObject()
                .name("mtimeMs").value(0)
                .name("byteSize").value(0)
                .endObject()
                .name("error").value(message)
                .endObject();
        return writer.toString();
    }
}
