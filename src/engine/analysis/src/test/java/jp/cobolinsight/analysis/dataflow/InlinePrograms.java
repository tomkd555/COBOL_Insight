package jp.cobolinsight.analysis.dataflow;

import jp.cobolinsight.frontend.cobol.Che4zCobolParser;
import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.cfg.CfgNodeKind;
import jp.cobolinsight.core.cfg.ControlFlowGraph;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.CompoundStatement;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.semantic.Statement;
import jp.cobolinsight.core.source.DecodedSource;
import jp.cobolinsight.core.source.EncodingInfo;
import jp.cobolinsight.core.spi.ParseOutcome;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Test helper that parses a fixed-format COBOL source string with the real parser and converts it to a CFG. */
final class InlinePrograms {

    private InlinePrograms() {
    }

    /** Joins each fixed-format line with a newline (column position is given by the leading spaces of each line). */
    static String source(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    static CobolSemanticModel parse(String source) {
        ParseOutcome<CobolSemanticModel> outcome =
                new Che4zCobolParser().parse(decode(source), List.of());
        return outcome.value().orElseThrow(() -> new AssertionError(
                "パース失敗: " + outcome.failureFinding().orElse(null)));
    }

    static ControlFlowGraph cfg(String source) {
        return CfgBuilder.build(parse(source));
    }

    /** Returns the STATEMENT nodes whose statement text contains substring, in definition order. */
    static List<CfgNode> nodesContaining(ControlFlowGraph cfg, String substring) {
        List<CfgNode> result = new ArrayList<>();
        for (CfgNode node : cfg.nodes()) {
            if (node.kind() != CfgNodeKind.STATEMENT) {
                continue;
            }
            if (text(node.statement().orElseThrow()).contains(substring)) {
                result.add(node);
            }
        }
        return result;
    }

    /** The single STATEMENT node whose statement text contains substring. */
    static CfgNode nodeContaining(ControlFlowGraph cfg, String substring) {
        List<CfgNode> matches = nodesContaining(cfg, substring);
        if (matches.size() != 1) {
            throw new AssertionError("'" + substring + "' を含む文ノードが1つでない: " + matches.size());
        }
        return matches.get(0);
    }

    private static String text(Statement statement) {
        if (statement instanceof SimpleStatement simple) {
            return simple.text();
        }
        if (statement instanceof CompoundStatement compound) {
            return compound.conditionText();
        }
        return "";
    }

    /** Builds a DecodedSource with a byte-offset table for each character position. Always UTF-8. */
    private static DecodedSource decode(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int[] offsets = new int[text.length()];
        int byteOffset = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int charCount = Character.charCount(cp);
            for (int j = 0; j < charCount; j++) {
                offsets[i + j] = byteOffset;
            }
            byteOffset += new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            i += charCount;
        }
        return new DecodedSource("inline.cbl", text, bytes, offsets,
                new EncodingInfo("UTF-8", 1.0, false, false));
    }
}
