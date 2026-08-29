package jp.cobolinsight.core.source;

import java.util.List;
import java.util.Locale;

/**
 * 解析対象の資産の種別と、種別ごとの拡張子の正典。
 *
 * <p>拡張子と種別の対応はこの1箇所だけに置く。片方だけを増やすと、走査の対象・利用者定義ルールの
 * 対象・コピー句の収集がそれぞれ別の表を見ることになる。
 *
 * <p>入力フォルダの走査で種別を決めるのはソースの内容であり、拡張子はその補助にとどまる
 * (内容で決まらなかったときの手掛かり)。これに対し、コピー句探索パス配下の収集は COPY 文の
 * 解決先を集める別の関心であり、そこでは拡張子が意味を持つ。COBOL の COPY 解決は「コピー句名＋
 * 拡張子」でファイル名を組み立てるため、拡張子の一致がそのまま解決の可否になるからである。
 */
public enum AssetKind {
    /** BMS マップ定義。 */
    BMS(".bms"),
    /** COBOL 本体。 */
    COBOL(".cbl", ".cob", ".cobol"),
    /** コピー句。 */
    COPYBOOK(".cpy", ".copy"),
    /** JCL。 */
    JCL(".jcl");

    private final List<String> extensions;

    AssetKind(String... extensions) {
        this.extensions = List.of(extensions);
    }

    /** この種別が名乗る拡張子(先頭のドットを含む小文字)。 */
    public List<String> extensions() {
        return extensions;
    }

    /**
     * 拡張子から種別を引く。先頭のドットの有無と大小は問わない。
     * どの種別にも属さない拡張子には null を返す。
     */
    public static AssetKind ofExtension(String extension) {
        if (extension == null || extension.isEmpty()) {
            return null;
        }
        String normalized = extension.startsWith(".")
                ? extension.toLowerCase(Locale.ROOT)
                : "." + extension.toLowerCase(Locale.ROOT);
        for (AssetKind kind : values()) {
            if (kind.extensions.contains(normalized)) {
                return kind;
            }
        }
        return null;
    }

    /**
     * ファイル名の拡張子から種別を引く。拡張子を持たない名前と、どの種別にも属さない拡張子には
     * null を返す。パスを渡しても末尾の名前だけを見る。
     */
    public static AssetKind ofFileName(String fileName) {
        return ofExtension(extensionOf(fileName));
    }

    /**
     * ファイル名の拡張子(先頭のドットを含む小文字)。拡張子を持たない名前には空文字を返す。
     * 先頭がドットの名前(.gitignore など)は拡張子を持たないものとして扱う。
     */
    public static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        String name = fileName.substring(Math.max(fileName.lastIndexOf('/'),
                fileName.lastIndexOf('\\')) + 1);
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }
}
