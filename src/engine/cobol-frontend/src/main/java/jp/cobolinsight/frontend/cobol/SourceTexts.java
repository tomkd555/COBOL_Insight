package jp.cobolinsight.frontend.cobol;

import jp.cobolinsight.core.encoding.EncodingCharsetProvider;
import jp.cobolinsight.core.source.FixedFormatColumns;
import jp.cobolinsight.core.spi.CharsetProvider;
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
 * Access to the original source text per URI. The main file uses DecodedSource's text; copybooks
 * are read from their files and cached. A copybook's character encoding is determined by the same
 * auto-detection used for the main file. Since Japanese assets can also appear in Shift_JIS or
 * EBCDIC, encoding is not fixed to UTF-8.
 */
final class SourceTexts {

    private static final CharsetProvider CHARSET_PROVIDER = new EncodingCharsetProvider();

    private final String mainUri;
    private final Map<String, List<String>> linesByUri = new HashMap<>();

    SourceTexts(String mainUri, String mainText) {
        this.mainUri = mainUri;
        linesByUri.put(mainUri, splitLines(mainText));
    }

    /** Returns the original text for a Locality's range. Empty string if it cannot be obtained. */
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

    /**
     * The fixed-format area boundaries converted to 0-based indices. The canonical column numbers
     * live in {@link jp.cobolinsight.core.source.FixedFormatColumns}; this only handles the
     * conversion of the origin. Written this way so the same physical quantity is not kept under
     * multiple origins.
     */
    private static final int AREA_A_START = FixedFormatColumns.INDICATOR_COLUMN - 1;
    private static final int IDENTIFICATION_START = FixedFormatColumns.IDENTIFICATION_START - 1;

    /**
     * Replaces the sequence-number area and the identification area with spaces. When extracting
     * original text that spans multiple lines, every column of the second line onward falls
     * within the range, so leaving these areas in would mix the sequence number and comment text
     * into the body. The length is left unchanged so column positions are preserved.
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
