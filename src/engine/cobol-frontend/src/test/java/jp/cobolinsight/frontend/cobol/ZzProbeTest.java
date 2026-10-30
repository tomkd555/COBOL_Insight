package jp.cobolinsight.frontend.cobol;

import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.semantic.Statements;
import jp.cobolinsight.core.source.DecodedSource;
import jp.cobolinsight.core.source.EncodingInfo;
import org.eclipse.lsp.cobol.common.model.tree.Node;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class ZzProbeTest {

    @Test
    void dump() throws Exception {
        Path dir = Files.createTempDirectory("probe");
        Files.writeString(dir.resolve("UPDKEI.cpy"), String.join("\n",
                "           EXEC SQL",
                "               UPDATE MYDB.KEIYAKU SET SHORI_KBN = '1'",
                "                WHERE KEIYAKU_NO = :WS-KEIYAKU-NO",
                "           END-EXEC",
                "           MOVE 'Y' TO WS-EOF-FLAG.",
                ""));
        String text = String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. FIX040D.",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "           EXEC SQL INCLUDE SQLCA END-EXEC.",
                "       01  WS-EOF-FLAG       PIC X(01) VALUE 'N'.",
                "       01  WS-KEIYAKU-NO     PIC X(10) VALUE SPACE.",
                "       PROCEDURE DIVISION.",
                "       MAIN-SECT SECTION.",
                "       M-START.",
                "           PERFORM WAIT-UNTIL-READY",
                "           PERFORM U-1 OF LOOP-SECT THRU U-3 UNTIL WS-EOF-FLAG = 'Y'",
                "           GOBACK.",
                "       WAIT-UNTIL-READY.",
                "           EXEC SQL UPDATE MYDB.SETTEI SET FLG = '1' END-EXEC.",
                "       LOOP-SECT SECTION.",
                "       U-1.",
                "           DISPLAY 'U1'.",
                "       U-2.",
                "           DISPLAY 'U2'.",
                "       U-3.",
                "           COPY UPDKEI.",
                "");
        Path file = dir.resolve("FIX040D.cbl");
        Files.writeString(file, text);
        StringBuilder out = new StringBuilder();
        var analysis = Che4zRuntime.instance().analyze(file.toUri().toString(), text,
                List.of(dir), new Che4zRuntime.CopybookCacheScope());
        analysis.result().getRootNode().getDepthFirstStream()
                .filter(x -> x.getClass().getSimpleName().equals("ProcedureDivisionBodyNode"))
                .forEach(p -> print(p, 0, out));
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        var model = new Che4zCobolParser().parse(new DecodedSource(file.toString(), text, bytes,
                new int[text.length()], new EncodingInfo("UTF-8", 1.0, false, false)),
                List.of(dir)).value().orElseThrow();
        for (var procedure : model.procedures()) {
            out.append("PROC ").append(procedure.name()).append('\n');
            Statements.walk(procedure.statements(), s -> out.append("   ")
                    .append(s instanceof SimpleStatement simple ? simple.verb() : s.getClass().getSimpleName())
                    .append(' ').append(Path.of(s.range().start().file()).getFileName())
                    .append(':').append(s.range().start().line()).append('\n'));
        }
        out.append("BLOCKS ").append(model.embeddedBlocks().size()).append('\n');
        Files.writeString(Path.of(System.getProperty("java.io.tmpdir"), "probe-out.txt"), out);
    }

    static void print(Node node, int depth, StringBuilder out) {
        var l = node.getLocality();
        String where = l == null ? "?" : Path.of(java.net.URI.create(l.getUri())).getFileName()
                + ":" + (l.getRange().getStart().getLine() + 1) + "-" + (l.getRange().getEnd().getLine() + 1);
        out.append("  ".repeat(depth)).append(node.getClass().getSimpleName()).append(' ').append(where).append('\n');
        for (Node child : node.getChildren()) {
            print(child, depth + 1, out);
        }
    }
}
