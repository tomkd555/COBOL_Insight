package jp.cobolinsight.cobolfrontend;

import org.eclipse.lsp.cobol.common.model.Locality;
import org.eclipse.lsp4j.Range;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * URI ごとの原ソーステキストへのアクセス。主ファイルは DecodedSource のテキストを、
 * コピーブックはファイルから UTF-8 で読み込んでキャッシュする。
 */
final class SourceTexts {

    private final String mainUri;
    private final Map<String, List<String>> linesByUri = new HashMap<>();

    SourceTexts(String mainUri, String mainText) {
        this.mainUri = mainUri;
        linesByUri.put(mainUri, splitLines(mainText));
    }

    /** Locality の範囲の原文を返す。取得できない場合は空文字列。 */
    String textOf(Locality locality) {
        if (locality == null || locality.getUri() == null || locality.getRange() == null) {
            return "";
        }
        return extract(locality.getUri(), locality.getRange());
    }

    String extract(String uri, Range range) {
        List<String> lines = linesOf(uri);
        if (lines == null) {
            return "";
        }
        int startLine = range.getStart().getLine();
        int endLine = Math.min(range.getEnd().getLine(), lines.size() - 1);
        if (startLine < 0 || startLine >= lines.size()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = startLine; i <= endLine; i++) {
            String line = lines.get(i);
            int from = i == startLine ? Math.min(range.getStart().getCharacter(), line.length()) : 0;
            int to = i == endLine ? Math.min(range.getEnd().getCharacter(), line.length())
                    : line.length();
            if (i > startLine) {
                sb.append('\n');
            }
            sb.append(line, Math.min(from, to), to);
        }
        return sb.toString();
    }

    private List<String> linesOf(String uri) {
        return linesByUri.computeIfAbsent(uri, key -> {
            if (UriPaths.isImplicit(key)) {
                return null;
            }
            try {
                return splitLines(Files.readString(Paths.get(new java.net.URI(key)),
                        StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException | java.net.URISyntaxException e) {
                return null;
            }
        });
    }

    private static List<String> splitLines(String text) {
        return List.of(text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1));
    }

    String mainUri() {
        return mainUri;
    }
}
