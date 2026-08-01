package jp.cobolinsight.bmsfrontend;

import jp.cobolinsight.bmsfrontend.grammar.BmsMapLexer;
import jp.cobolinsight.bmsfrontend.grammar.BmsMapParser;
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

/** BMS マップ定義ソースの解析の入口。構文エラーは例外にせず {@link BmsParseResult#errors()} へ集める。 */
public final class BmsSourceParser {

    public BmsParseResult parse(String sourceText) {
        List<BmsParseError> errors = new ArrayList<>();
        BmsMapLexer lexer = new BmsMapLexer(CharStreams.fromString(sourceText));
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
