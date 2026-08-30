package jp.cobolinsight.app.cli;

import jp.cobolinsight.core.encoding.CodePage;
import jp.cobolinsight.core.encoding.DecodedSource;
import jp.cobolinsight.core.encoding.SourceDecoder;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.json.JsonWriter;
import jp.cobolinsight.core.pipeline.ExitCodes;
import jp.cobolinsight.core.fix.MinimalLineEdit;
import jp.cobolinsight.core.fix.ReparseResult;
import jp.cobolinsight.app.EngineWiring;
import jp.cobolinsight.core.fix.ReparseVerifier;
import jp.cobolinsight.app.persistence.PersistenceDao;
import jp.cobolinsight.app.persistence.PersistenceDatabase;
import jp.cobolinsight.app.persistence.model.EncodingInfoRecord;
import jp.cobolinsight.app.persistence.model.SourceRecord;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * The `save` subcommand. Writes the text edited in the GUI back over the original file, using the
 * original file's code page and line-ending style.
 *
 * <p>This command is the one deliberate exception to this tool's principle of "never rewrite the
 * original file." It is precisely the operation of saving the result of editing in the GUI, and it
 * would serve no purpose if it wrote to a separate location. {@code fix apply} continues to leave
 * the original untouched, as before.
 *
 * <p>The full edited text is received as a UTF-8 text file ({@code --edited}). Node cannot encode
 * to Shift_JIS or EBCDIC (CP930/939), so writing back while preserving the code page is the engine's
 * responsibility. {@link MinimalLineEdit} collapses only the lines that differ from the original into
 * a single edit; untouched lines are carried over as their original byte sequence.
 *
 * <p>Reparse verification runs after the write. The write-back is not rolled back even if the
 * content fails to parse, so as not to block saving work in progress; the verification result is
 * reported in the {@code reparseErrors} field of the summary JSON.
 */
@Command(name = "save", mixinStandardHelpOptions = true,
        description = "画面で編集した本文を原本のコードページのまま原本へ書き戻す")
public class SaveCommand implements Callable<Integer> {

    @Option(names = "--file", paramLabel = "FILE", required = true,
            description = "書き戻す原本(絶対パス)")
    Path file;

    @Option(names = "--edited", paramLabel = "FILE", required = true,
            description = "編集後の全文を収めた UTF-8 テキストファイル")
    Path editedFile;

    @Option(names = "--codepage", paramLabel = "CHARSET",
            description = "原本のコードページ手動指定。自動判別とプロジェクトファイルの記録に優先する")
    String codepage;

    @Option(names = "--copybook-path", paramLabel = "DIR",
            description = "再パース検証で用いるコピー句探索パス(既定: 走査で見つかったコピー句の置き場所)")
    List<Path> copybookPaths = new ArrayList<>();

    @Option(names = "--db", paramLabel = "FILE",
            description = "プロジェクトファイル。走査時に記録したコードページを引く")
    Path databaseFile;

    @Override
    public Integer call() {
        Path target = file.toAbsolutePath().normalize();
        try {
            return save(target);
        } catch (IOException | UncheckedIOException | IllegalArgumentException e) {
            // Failure before the write-back. Whether decoding, encoding, or I/O fails, the original file is left unchanged.
            System.out.println(summaryJson(target, false, 0, 0, List.of(), ExitCodes.ERRORS,
                    e.getMessage()));
            return ExitCodes.ERRORS;
        }
    }

    private int save(Path target) throws IOException {
        byte[] originalBytes = Files.readAllBytes(target);
        String editedText = Files.readString(editedFile, StandardCharsets.UTF_8);
        String charsetName = resolveCodepage(target);
        SourceDecoder decoder = new SourceDecoder();
        DecodedSource original = charsetName == null
                ? decoder.decode(originalBytes)
                : decoder.decode(originalBytes, CodePage.fromName(charsetName));

        MinimalLineEdit.Result edit = MinimalLineEdit.apply(target.toString(), original, editedText);
        if (!edit.changed()) {
            System.out.println(summaryJson(target, false, 0, 0, List.of(), ExitCodes.SUCCESS, null));
            return ExitCodes.SUCCESS;
        }

        replace(target, edit.bytes());
        // From this point on, the original file has already been replaced. Even on failure, report written as true.
        // Reporting the already-replaced original as "untouched" would make the user rely on original content that no longer exists.
        try {
            ReparseResult reparse = verifyReparse(target, edit.bytes(),
                    original.encodingInfo().codePage().charsetName());
            List<Finding> reparseErrors = reparse.errorFinding().map(List::of).orElse(List.of());
            int exitCode = reparseErrors.isEmpty() ? ExitCodes.SUCCESS : ExitCodes.WARNINGS;
            System.out.println(summaryJson(target, true, edit.changedLineFrom(),
                    edit.changedLineTo(), reparseErrors, exitCode, null));
            return exitCode;
        } catch (IOException | RuntimeException e) {
            System.out.println(summaryJson(target, true, edit.changedLineFrom(),
                    edit.changedLineTo(), List.of(), ExitCodes.ERRORS,
                    "書き戻しは済んだが、その後の再パース検証に失敗した: " + e));
            return ExitCodes.ERRORS;
        }
    }

    /**
     * Reparse verification performed after the write-back. This is factored out separately so
     * tests can trigger a failure that occurs after the write-back.
     */
    ReparseResult verifyReparse(Path target, byte[] bytes, String charsetName) throws IOException {
        return EngineWiring.reparseVerifier().verify(target.toString(), bytes, charsetName,
                reparseCopybookPaths(target));
    }

    /**
     * Replaces the original file. A temporary file is written in the same folder and then moved
     * into place, so if the write fails partway through, the original file is left with its
     * original content intact. Opening and writing directly to the original would leave it
     * truncated with incomplete content at the point of failure.
     */
    private void replace(Path target, byte[] bytes) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), target.getFileName().toString(),
                ".save");
        try {
            writeTemp(temp, bytes);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Writes to the temporary file. This is factored out separately so tests can trigger a failure partway through the write-back. */
    void writeTemp(Path temp, byte[] bytes) throws IOException {
        Files.write(temp, bytes);
    }

    /**
     * The copybook search path used for reparse verification. When none is specified, it is
     * found from the scanned asset folder using the same procedure as lint and report. Treating
     * an unspecified path as "there are no copybooks at all" would make saving any asset that
     * has a COPY statement always trigger a reparse error.
     */
    private List<Path> reparseCopybookPaths(Path target) {
        return copybookPaths.isEmpty()
                ? CommonScanOptions.resolveCopybookPaths(scanRoot(target), List.of())
                : copybookPaths;
    }

    /**
     * The starting point for locating copybooks. If a project file exists, this is the asset
     * folder recorded at scan time (SOURCE.root); otherwise it is the original file's location.
     */
    private Path scanRoot(Path target) {
        if (databaseFile != null && Files.isRegularFile(databaseFile)) {
            try (PersistenceDatabase database = PersistenceDatabase.open(databaseFile)) {
                PersistenceDao dao = new PersistenceDao(database.connection());
                for (SourceRecord source : dao.findAllSources()) {
                    return Path.of(source.root());
                }
            }
        }
        return target.getParent();
    }

    /**
     * The code page name used for decoding. The manually specified value takes highest priority,
     * then the value the project file recorded at scan time; if neither is available, returns
     * null to leave it to auto-detection (the same resolution order as {@code lint} and others).
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

    private String summaryJson(Path target, boolean written, int changedLineFrom, int changedLineTo,
            List<Finding> reparseErrors, int exitCode, String error) {
        JsonWriter writer = new JsonWriter();
        writer.beginObject()
                .name("written").value(written)
                .name("path").value(target.toString().replace('\\', '/'))
                .name("changedLineFrom").value(changedLineFrom)
                .name("changedLineTo").value(changedLineTo)
                .name("reparseErrors").beginArray();
        for (Finding finding : reparseErrors) {
            writer.beginObject()
                    .name("line").value(finding.location().line())
                    .name("message").value(finding.message())
                    .endObject();
        }
        writer.endArray()
                .name("error").value(error == null ? "" : error)
                .name("exitCode").value(exitCode)
                .endObject();
        return writer.toString();
    }
}
