package jp.cobolinsight.core.semantic;

import java.util.List;
import java.util.function.Consumer;

/** The one walk over a statement tree that the model's readers share. */
public final class Statements {

    private Statements() {
    }

    /** Pre-order, in source order: each statement, then the statements of its blocks. */
    public static void walk(List<Statement> statements, Consumer<Statement> visitor) {
        for (Statement statement : statements) {
            visitor.accept(statement);
            if (statement instanceof CompoundStatement compound) {
                for (StatementBlock block : compound.blocks()) {
                    walk(block.statements(), visitor);
                }
            }
        }
    }
}
