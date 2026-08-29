package jp.cobolinsight.app.cli;

import jp.cobolinsight.app.persistence.PersistenceDao;
import jp.cobolinsight.app.persistence.PersistenceDatabase;
import jp.cobolinsight.app.persistence.model.EncodingInfoRecord;
import jp.cobolinsight.app.persistence.model.SourceRecord;
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
 * `decode`。1本のソースを原本のコードページで復号し、本文と行ごとの桁の境目を JSON へ書く。
 *
 * <p>画面のソースビューアは固定形式の領域(一連番号・標識・A領域・B領域・識別)を塗り分けるが、
 * その境目は<b>バイト桁</b>で決まる。全角文字を含む行では文字数とバイト桁が一致しないため、
 * 画面側で数えると領域がずれる。境目の算出はバイト対応表を持つ engine 側の仕事である。
 */
@Command(name = "decode", mixinStandardHelpOptions = true,
        description = "1本のソースを復号し、本文と固定形式の桁境界を JSON で書き出す")
public final class DecodeCommand implements Callable<Integer> {

    /** 桁を塗り分けるための境目。固定形式の各領域が始まるバイト桁(1起点)である。 */
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
            Paths.writeString(outFile, errorJson(e.getMessage() == null
                    ? e.toString() : e.getMessage()));
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
     * 行内で、1起点のバイト桁 {@code column} が始まる UTF-16 の文字位置(行頭からの相対)。
     * その桁に届かない短い行では -1 を返す。全角文字の途中に当たる桁は、その文字の位置を指す。
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
     * 復号に用いるコードページ名。手動指定を最優先とし、次にプロジェクトファイルが走査時に記録した
     * 値、いずれも無ければ null を返して自動判別に委ねる({@code save} と同じ解決順)。
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
