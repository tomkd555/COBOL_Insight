package jp.cobolinsight.dataflow;

import jp.cobolinsight.cobolfrontend.Che4zCobolParser;
import jp.cobolinsight.engineapi.cfg.CfgNode;
import jp.cobolinsight.engineapi.cfg.CfgNodeKind;
import jp.cobolinsight.engineapi.cfg.ControlFlowGraph;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.semantic.CompoundStatement;
import jp.cobolinsight.engineapi.semantic.SimpleStatement;
import jp.cobolinsight.engineapi.semantic.Statement;
import jp.cobolinsight.engineapi.source.DecodedSource;
import jp.cobolinsight.engineapi.source.EncodingInfo;
import jp.cobolinsight.engineapi.spi.ParseOutcome;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** テスト用に、固定形式のCOBOLソース文字列を実パーサーで解析しCFGへ変換する補助。 */
final class InlinePrograms {

    private InlinePrograms() {
    }

    /** 固定形式の各行を改行で連結する(列位置は各行の先頭空白で指定する)。 */
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

    /** 文テキストに substring を含むSTATEMENTノードを定義順に返す。 */
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

    /** 文テキストに substring を含む唯一のSTATEMENTノード。 */
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

    /** 文字位置ごとのバイトオフセット表を作って DecodedSource を組む。UTF-8 固定である。 */
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
