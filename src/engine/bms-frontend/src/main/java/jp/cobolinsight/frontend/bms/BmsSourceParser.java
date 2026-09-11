package jp.cobolinsight.frontend.bms;

import jp.cobolinsight.frontend.bms.grammar.BmsMapLexer;
import jp.cobolinsight.frontend.bms.grammar.BmsMapParser;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** The entry point for parsing BMS map definition source. Syntax errors are collected into {@link BmsParseResult#errors()} rather than thrown as exceptions. */
public final class BmsSourceParser {

    /**
     * An assembler listing instruction on a line of its own: PRINT NOGEN, TITLE '...', SPACE 2,
     * EJECT. A map source puts them before and between the macros; they carry no map content.
     * Blanked before lexing so line numbers stay intact.
     */
    private static final Pattern ASSEMBLER_INSTRUCTION =
            Pattern.compile("(?m)^[ \\t]+(?:PRINT|TITLE|SPACE|EJECT)(?:[ \\t][^\\r\\n]*)?$");

    public BmsParseResult parse(String sourceText) {
        List<BmsParseError> errors = new ArrayList<>();
        String lexable = ASSEMBLER_INSTRUCTION.matcher(sourceText).replaceAll("");
        BmsMapLexer lexer = new BmsMapLexer(CharStreams.fromString(lexable));
        lexer.removeErrorListeners();
        lexer.addErrorListener(collectingListener(errors));

        BmsMapParser parser = new BmsMapParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(collectingListener(errors));

        BmsMapParser.MapFileContext tree = parser.mapFile();
        return new BmsModelBuilder(errors).build(tree);
    }

    public BmsParseResult parseFile(Path path) throws IOException {
        return parse(Files.readString(path, StandardCharsets.UTF_8));
    }

    private static BaseErrorListener collectingListener(List<BmsParseError> errors) {
        return new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                    int line, int charPositionInLine, String msg,
                                    RecognitionException e) {
                errors.add(new BmsParseError(line, charPositionInLine, msg));
            }
        };
    }
}
