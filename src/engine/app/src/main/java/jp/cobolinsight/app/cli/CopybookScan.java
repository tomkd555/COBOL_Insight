package jp.cobolinsight.app.cli;

import jp.cobolinsight.core.source.AssetKind;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * コピー句探索パス直下からコピー句を集める。translate と fix が原本と併せて写す対象を得るために
 * 使う。
 *
 * <p>入力フォルダの走査({@link SourceDiscovery})とは<b>別の関心</b>である。あちらは資産の
 * 置き場所を問わず内容から種別を逆算するのに対し、ここは COPY 文の解決先を集める層であり、
 * 拡張子で拾う。COBOL の COPY 解決は「コピー句名＋拡張子」でファイル名を組み立てるため、
 * 拡張子の一致がそのまま解決の可否になるからである。受け付ける拡張子は
 * {@link AssetKind#COPYBOOK} が名乗るものすべてとする。
 */
final class CopybookScan {

    private CopybookScan() {
    }

    /**
     * 探索パス直下のコピー句をファイル名の昇順で返す。ディレクトリでない場合は空を返す。
     * 探索パスは入力フォルダの外を指せるため、再帰はせず直下だけを見る(COPY 解決も同じ)。
     */
    static List<Path> collect(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(dir)) {
            return children.filter(Files::isRegularFile)
                    .filter(p -> AssetKind.ofFileName(p.getFileName().toString())
                            == AssetKind.COPYBOOK)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
