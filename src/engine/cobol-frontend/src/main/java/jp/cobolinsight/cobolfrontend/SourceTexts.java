package jp.cobolinsight.cobolfrontend;

import jp.cobolinsight.encoding.EncodingCharsetProvider;
import jp.cobolinsight.engineapi.spi.CharsetProvider;
import org.eclipse.lsp.cobol.common.model.Locality;
import org.eclipse.lsp4j.Range;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * URI ごとの原ソーステキストへのアクセス。主ファイルは DecodedSource のテキストを、
 * コピーブックはファイルから読み込んでキャッシュする。コピーブックの文字コードは本体と同じ
 * 自動判別で決める。日本語資産は Shift_JIS・EBCDIC も現れるため、UTF-8 に固定しない。
 */
final class SourceTexts {

    private static final CharsetProvider CHARSET_PROVIDER = new EncodingCharsetProvider();

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
            String line = maskFixedFormatAreas(lines.get(i));
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
                Path path = Paths.get(new java.net.URI(key));
                return splitLines(CHARSET_PROVIDER
                        .decode(path.toString(), Files.readAllBytes(path)).text());
            } catch (IOException | RuntimeException | java.net.URISyntaxException e) {
                return null;
            }
        });
    }

    /** 固定形式の領域境界。一連番号領域は1〜6桁、本文は7〜72桁、73桁以降は識別領域である。 */
    private static final int AREA_A_START = 6;
    private static final int IDENTIFICATION_START = 72;

    /**
     * 一連番号領域と識別領域を空白へ置き換える。複数行にまたがる原文の取り出しでは2行目以降の
     * 全桁が範囲に入るため、これらを残すと一連番号と注釈が本文へ混ざる。桁位置を保つため長さは変えない。
     */
    private static String maskFixedFormatAreas(String line) {
        if (line.length() <= AREA_A_START) {
            return line;
        }
        StringBuilder sb = new StringBuilder(line);
        for (int i = 0; i < AREA_A_START; i++) {
            sb.setCharAt(i, ' ');
        }
        for (int i = IDENTIFICATION_START; i < sb.length(); i++) {
            sb.setCharAt(i, ' ');
        }
        return sb.toString();
    }

    private static List<String> splitLines(String text) {
        return List.of(text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1));
    }

    String mainUri() {
        return mainUri;
    }
}
