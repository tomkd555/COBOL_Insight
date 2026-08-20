package jp.cobolinsight.cli;

import jp.cobolinsight.encoding.CodePage;
import jp.cobolinsight.encoding.DecodedSource;
import jp.cobolinsight.encoding.SourceDecoder;
import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.json.JsonWriter;
import jp.cobolinsight.engineapi.pipeline.ExitCodes;
import jp.cobolinsight.fix.MinimalLineEdit;
import jp.cobolinsight.fix.ReparseResult;
import jp.cobolinsight.fix.ReparseVerifier;
import jp.cobolinsight.persistence.PersistenceDao;
import jp.cobolinsight.persistence.PersistenceDatabase;
import jp.cobolinsight.persistence.model.EncodingInfoRecord;
import jp.cobolinsight.persistence.model.SourceRecord;
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
 * `save` サブコマンド。画面で編集した本文を、原本と同じコードページ・改行様式で原本へ上書きする。
 *
 * <p>本コマンドは「原本を書き換えない」という本ツールの原則の、意図した唯一の例外である。利用者が
 * 画面で編集した結果を保存する操作そのものであり、書き出し先を分ければ用を成さない。{@code fix apply}
 * は従来どおり原本へ触れない。
 *
 * <p>編集後の全文は UTF-8 のテキストファイル({@code --edited})で受け取る。Node は Shift_JIS や
 * EBCDIC(CP930/939)へ符号化できないため、コードページを保った書き戻しは engine 側が担う。原本と
 * 食い違う行だけを {@link MinimalLineEdit} が1つの編集へまとめ、触れていない行は原バイト列のまま
 * 持ち越す。
 *
 * <p>書き出しの後に再パース検証を通す。パースできない内容でも書き戻しは取り消さない。作業途中の
 * 保存を妨げないためであり、検証結果は要約 JSON の {@code reparseErrors} で示す。
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
            // 書き戻す前の失敗。復号・符号化・入出力のいずれで失敗しても原本は元のままである。
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
        // ここから先はもう原本を置き換えた後である。失敗しても written は true として報告する。
        // 置き換え済みの原本を「触れていない」と伝えると、利用者は残っていない元の内容を当てにする。
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
     * 書き戻し後の再パース検証。試験が書き戻しの後の失敗を起こせるよう、ここで区切っている。
     */
    ReparseResult verifyReparse(Path target, byte[] bytes, String charsetName) throws IOException {
        return new ReparseVerifier().verify(target.toString(), bytes, charsetName,
                reparseCopybookPaths(target));
    }

    /**
     * 原本を置き換える。同じフォルダへ一時ファイルを書いてから移し替えるため、書き出しの途中で
     * 失敗しても原本は元の内容のまま残る。原本を直接開いて書くと、失敗した時点で切り詰められた
     * 中途半端な内容が原本として残る。
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

    /** 一時ファイルへの書き出し。試験が書き戻しの途中の失敗を起こせるよう、ここで区切っている。 */
    void writeTemp(Path temp, byte[] bytes) throws IOException {
        Files.write(temp, bytes);
    }

    /**
     * 再パース検証で用いるコピー句探索パス。指定が無い場合は lint・report と同じ手順で、走査した
     * 資産フォルダから見つける。指定が無いことを「コピー句が1つも無い」と扱うと、COPY を持つ
     * 資産の保存が必ず再パースの誤りを伴う。
     */
    private List<Path> reparseCopybookPaths(Path target) {
        return copybookPaths.isEmpty()
                ? CommonScanOptions.resolveCopybookPaths(scanRoot(target), List.of())
                : copybookPaths;
    }

    /**
     * コピー句を探す起点。プロジェクトファイルがあれば走査時の資産フォルダ(SOURCE.root)、
     * 無ければ原本の置き場所とする。
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
     * 復号に用いるコードページ名。手動指定を最優先とし、次にプロジェクトファイルが走査時に記録した
     * 値、いずれも無ければ null を返して自動判別に委ねる({@code lint} などの解決順と同じ)。
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
