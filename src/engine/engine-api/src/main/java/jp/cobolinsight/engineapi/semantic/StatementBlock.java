package jp.cobolinsight.engineapi.semantic;

import java.util.List;
import java.util.Objects;

/** 複合文の1分岐・本体。label は THEN・ELSE・WHEN句の内容・空文字列(反復本体)など。 */
public record StatementBlock(String label, List<Statement> statements) {

    public StatementBlock {
        Objects.requireNonNull(label, "label");
        statements = List.copyOf(statements);
    }
}
