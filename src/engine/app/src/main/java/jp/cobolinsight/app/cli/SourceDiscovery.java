package jp.cobolinsight.app.cli;

import jp.cobolinsight.core.source.AssetKind;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 資産フォルダから解析対象のソースを発見する。scan・lint・sql-lint・translate・fix の
 * 各 Runner はこのクラスだけを走査の入口とする。
 *
 * <p>走査はフォルダの形を問わない。INPUT_DIR 配下を再帰的にたどり、種別は
 * <b>ソースの内容から逆算</b>する({@link SourceClassifier})。拡張子が担うのは2つの補助だけで
 * ある。1つは候補の絞り込みで、テキストでないことが形式から確実な拡張子と本ツール自身が
 * 書き出す拡張子は1バイトも読まない。もう1つは内容で決まらなかったときの手掛かりで、既知の
 * 拡張子ならその種別を採る。拡張子と内容が食い違った場合は内容を採り、食い違いを報告する。
 *
 * <p>報告の優先順位は「黙って落としたもの＞黙って解釈を変えたもの＞打ち切り」である。
 * {@link Result#undecided()}・{@link Result#mismatches()}・{@link Result#unreadable()} は
 * いずれも全件を返し、件数を別に持たない(配列の長さが件数である)。件数と例示を別々に持つと、
 * ここで例示を打ち切る誘惑が残り、利用者は「他にもあるのか」を知る手段を失う。
 *
 * <p>再帰の歯止めは深さの数値ではなく、たどった実パス({@link Path#toRealPath})の記録で行う。
 * 深さの上限には根拠が無く、深い位置に資産を置いた利用者を黙って取りこぼす。上限が本当に
 * 要るのはディレクトリの循環(自分自身へのリンク)を断つためであり、それは訪問済みの実パスを
 * 持てば足りる。
 */
final class SourceDiscovery {

    /** 種別を決めた根拠。 */
    enum Evidence {
        /** ソースの内容から決めた。 */
        CONTENT,
        /** 内容では決まらず、拡張子から決めた。 */
        EXTENSION
    }

    /** 発見した1ファイル。relPath は INPUT_DIR からの相対パスで区切りは / へそろえる。 */
    record DiscoveredFile(String relPath, Path absPath, AssetKind kind, Evidence evidence) {

        String fileName() {
            return absPath.getFileName().toString();
        }
    }

    /** 拡張子と内容が食い違った1件。走査対象には内容の種別で入れる。 */
    record KindMismatch(String relPath, AssetKind byExtension, AssetKind byContent) {
    }

    /**
     * 走査の結果。files は相対パスの辞書順で、この順が SOURCE.id をパス順に振る不変条件の前提に
     * なる。transactionTables は CICS のトランザクション定義表とみなせた CSV の絶対パスである。
     */
    record Result(List<DiscoveredFile> files, List<Path> transactionTables, boolean truncated,
            List<String> undecided, List<KindMismatch> mismatches, List<String> unreadable) {

        Result {
            files = List.copyOf(files);
            transactionTables = List.copyOf(transactionTables);
            undecided = List.copyOf(undecided);
            mismatches = List.copyOf(mismatches);
            unreadable = List.copyOf(unreadable);
        }

        /** 指定した種別だけを取り出す。Runner ごとに要る種別が違うため呼び出し側で絞る。 */
        List<DiscoveredFile> filesOf(Set<AssetKind> kinds) {
            return files.stream().filter(f -> kinds.contains(f.kind())).toList();
        }

        /**
         * 取りこぼしと解釈の変更を利用者へ伝える文言。scan はサマリ JSON へ、
         * lint・sql-lint・translate・fix は標準エラーへ出す。文言をここへ集めるのは、
         * 走査の入口が1つである以上、その報告の入口も1つであるべきだからである。
         */
        List<String> warnings() {
            List<String> messages = new ArrayList<>();
            if (!undecided.isEmpty()) {
                messages.add(undecided.size() + "件は種別を判定できなかったため対象から外した。"
                        + "COBOL 本体なら IDENTIFICATION DIVISION、コピー句ならレベル番号で始まる"
                        + "項目定義、JCL なら // で始まる行、BMS なら DFHMSD を含むか確認する: "
                        + String.join(", ", undecided));
            }
            if (!mismatches.isEmpty()) {
                List<String> details = mismatches.stream()
                        .map(m -> m.relPath() + " (拡張子は" + label(m.byExtension())
                                + "、内容は" + label(m.byContent()) + ")")
                        .toList();
                messages.add(mismatches.size()
                        + "件は拡張子と内容が食い違ったため、内容を優先して取り込んだ: "
                        + String.join(", ", details));
            }
            if (!unreadable.isEmpty()) {
                messages.add(unreadable.size() + "件は読み取れなかったため対象から外した: "
                        + String.join(", ", unreadable));
            }
            if (truncated) {
                messages.add("走査するファイル数が上限(" + MAX_FILES
                        + "件)に達したため、以降のファイルを対象から外した。"
                        + "資産フォルダを分けて取り込む。");
            }
            return messages;
        }

        private static String label(AssetKind kind) {
            return switch (kind) {
                case BMS -> "BMSマップ";
                case COBOL -> "COBOL本体";
                case COPYBOOK -> "コピー句";
                case JCL -> "JCL";
            };
        }
    }

    /**
     * 走査対象の件数上限。SOURCE.id が 1,000,000 未満という採番の不変条件
     * ({@link ScanRunner} のクラス Javadoc)に由来する外部の制約であり、本ツールが選んだ数値では
     * ない。上限に達したときは {@link Result#truncated()} で必ず利用者へ伝える。
     */
    static final int MAX_FILES = 5_000;

    /** 再帰走査で降りないディレクトリ名。名前が . で始まるものも併せて除く。 */
    private static final Set<String> EXCLUDED_DIRS =
            Set.of("node_modules", "build", "target", "out", "dist");

    /**
     * 候補にしない拡張子。件数で決めず、2つの基準だけで定める。テキストでないことが形式から
     * 確実なもの(画像・書庫・実行形式・SQLite の DB)と、資産ではなく資産についての文書である
     * ことが形式から確実なもの(本ツールが書き出す SARIF・HTML・テキストのレポート・JSON と、
     * 資産フォルダへ置かれる Markdown の説明書)である。基準の外にある拡張子は未知として扱い、
     * 内容で判定する。
     *
     * <p>.md を落とすのは、散文に PROGRAM-ID の語が現れるだけで解析対象へ化けるのを断つためで
     * ある。{@code samples/expected-results.md} が実例で、資産の説明として COBOL の語を本文へ書く文書は
     * 資産フォルダに置かれる。文書はどの種別の資産でもないので、判定できなかったものとしてでは
     * なく候補の外として扱う。
     */
    private static final Set<String> EXCLUDED_EXTENSIONS = Set.of(
            ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".ico", ".tif", ".tiff", ".webp",
            ".zip", ".jar", ".war", ".ear", ".gz", ".tar", ".7z", ".rar",
            ".exe", ".dll", ".so", ".dylib", ".class", ".o", ".obj", ".bin", ".pdf",
            ".sarif", ".html", ".txt", ".json", ".md", ".db");

    /** トランザクション定義表の候補とする拡張子。 */
    private static final String TRANSACTION_TABLE_EXTENSION = ".csv";

    /**
     * トランザクション定義表の値の形式(資産名)。上限8桁と使用可能文字はメインフレームの
     * メンバ名の規則に合わせる。表とみなすかの判定と、行の読み込み({@link ScanRunner})が
     * 同じ規則を見るよう、ここを唯一の正とする。
     */
    static final Pattern MEMBER_NAME_PATTERN = Pattern.compile("[A-Za-z0-9@#$-]{1,8}");

    private SourceDiscovery() {
    }

    /** 資産フォルダを走査する。存在しないフォルダを渡した場合は空の結果を返す。 */
    static Result discover(Path inputDir) {
        Walk walk = new Walk(inputDir);
        walk.walk(inputDir);
        walk.files.sort(Comparator.comparing(DiscoveredFile::relPath));
        walk.transactionTables.sort(Comparator.naturalOrder());
        walk.undecided.sort(Comparator.naturalOrder());
        walk.mismatches.sort(Comparator.comparing(KindMismatch::relPath));
        walk.unreadable.sort(Comparator.naturalOrder());
        return new Result(walk.files, walk.transactionTables, walk.truncated,
                walk.undecided, walk.mismatches, walk.unreadable);
    }

    /** 再帰走査の作業状態。 */
    private static final class Walk {

        private final Path inputDir;
        /** 訪問済みディレクトリの実パス。シンボリックリンクによる循環をここで断つ。 */
        private final Set<Path> visited = new HashSet<>();
        private final List<DiscoveredFile> files = new ArrayList<>();
        private final List<Path> transactionTables = new ArrayList<>();
        private final List<String> undecided = new ArrayList<>();
        private final List<KindMismatch> mismatches = new ArrayList<>();
        private final List<String> unreadable = new ArrayList<>();
        private boolean truncated;

        private Walk(Path inputDir) {
            this.inputDir = inputDir;
        }

        private void walk(Path dir) {
            if (truncated || !Files.isDirectory(dir)) {
                return;
            }
            Path real;
            try {
                real = dir.toRealPath();
            } catch (IOException e) {
                // 実パスを取れないディレクトリは循環の判定ができない。降りずに取りこぼしを伝える。
                unreadable.add(relativize(dir));
                return;
            }
            if (!visited.add(real)) {
                return;
            }
            List<Path> children;
            try (Stream<Path> stream = Files.list(dir)) {
                children = stream.sorted().toList();
            } catch (IOException e) {
                unreadable.add(relativize(dir));
                return;
            }
            for (Path child : children) {
                if (truncated) {
                    return;
                }
                if (Files.isDirectory(child)) {
                    if (!isExcluded(child)) {
                        walk(child);
                    }
                } else if (Files.isRegularFile(child)) {
                    consider(child);
                }
            }
        }

        private boolean isExcluded(Path dir) {
            String name = dir.getFileName().toString();
            return name.startsWith(".") || EXCLUDED_DIRS.contains(name.toLowerCase(Locale.ROOT));
        }

        private void consider(Path file) {
            String extension = AssetKind.extensionOf(file.getFileName().toString());
            if (TRANSACTION_TABLE_EXTENSION.equals(extension)) {
                if (looksLikeTransactionTable(file)) {
                    transactionTables.add(file);
                }
                return;
            }
            if (EXCLUDED_EXTENSIONS.contains(extension)) {
                return;
            }
            byte[] content;
            try {
                content = Files.readAllBytes(file);
            } catch (IOException e) {
                unreadable.add(relativize(file));
                return;
            }
            SourceClassifier.Verdict verdict = SourceClassifier.classify(content);
            AssetKind byExtension = AssetKind.ofExtension(extension);
            AssetKind byContent = verdict.kind();
            if (byContent == null && byExtension == null) {
                // NUL を含むファイルは「テキストでない」と確定した結果であり、判定できなかった
                // ものではない。取りこぼしではないので undecided へは載せない。
                if (!verdict.binary()) {
                    undecided.add(relativize(file));
                }
                return;
            }
            AssetKind kind = byContent != null ? byContent : byExtension;
            Evidence evidence = byContent != null ? Evidence.CONTENT : Evidence.EXTENSION;
            if (byContent != null && byExtension != null && byContent != byExtension) {
                mismatches.add(new KindMismatch(relativize(file), byExtension, byContent));
            }
            if (files.size() >= MAX_FILES) {
                truncated = true;
                return;
            }
            files.add(new DiscoveredFile(relativize(file), file, kind, evidence));
        }

        /**
         * CICS のトランザクション定義表とみなせるか。1行目はヘッダとして読み飛ばし、2行目以降に
         * 「2列とも資産名の形式に合う対」が1組でもあることを条件とする。無関係な CSV を表と
         * みなす害は無い。{@code CallGraphLinker} は COBOL 側が実際に参照した ID だけを
         * 問い合わせるため、余分な行はグラフへ寄与しないからである。
         */
        private static boolean looksLikeTransactionTable(Path csv) {
            List<String> lines;
            try {
                lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
            } catch (IOException e) {
                // UTF-8 で読めない CSV は本ツールが読む定義表ではない。資産のソースでもないため、
                // 解析対象から外れることを利用者へ伝える必要も無い。
                return false;
            }
            for (String line : lines.stream().skip(1).toList()) {
                String[] fields = line.split(",");
                if (fields.length == 2
                        && MEMBER_NAME_PATTERN.matcher(fields[0].trim()).matches()
                        && MEMBER_NAME_PATTERN.matcher(fields[1].trim()).matches()) {
                    return true;
                }
            }
            return false;
        }

        private String relativize(Path file) {
            return inputDir.toAbsolutePath().normalize()
                    .relativize(file.toAbsolutePath().normalize())
                    .toString().replace('\\', '/');
        }
    }
}
