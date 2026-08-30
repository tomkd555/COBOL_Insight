package jp.cobolinsight.rules.syntax;

import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.CompoundStatement;
import jp.cobolinsight.core.semantic.GoToStatement;
import jp.cobolinsight.core.semantic.Procedure;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.semantic.Statement;
import jp.cobolinsight.core.semantic.StatementBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Statement-walking helper shared by the syntax rules. Visits every statement, including nested ones, depth-first. */
final class Statements {

    private Statements() {
    }

    /** Visits every statement in the program (including compound statements' nested statements) depth-first. */
    static void walk(CobolSemanticModel model, Consumer<Statement> visitor) {
        for (Procedure procedure : model.procedures()) {
            walk(procedure.statements(), visitor);
        }
    }

    static void walk(List<Statement> statements, Consumer<Statement> visitor) {
        for (Statement statement : statements) {
            visitor.accept(statement);
            if (statement instanceof CompoundStatement compound) {
                for (StatementBlock block : compound.blocks()) {
                    walk(block.statements(), visitor);
                }
            }
        }
    }

    /** The text a single statement holds itself (excluding the text of its nested statements). */
    static List<String> ownTexts(Statement statement) {
        List<String> texts = new ArrayList<>();
        if (statement instanceof SimpleStatement simple) {
            texts.add(simple.text());
        } else if (statement instanceof CompoundStatement compound) {
            texts.add(compound.conditionText());
            for (StatementBlock block : compound.blocks()) {
                texts.add(block.label());
            }
        } else if (statement instanceof GoToStatement goTo) {
            texts.addAll(goTo.targets());
            goTo.dependingOn().ifPresent(texts::add);
        }
        return texts;
    }
}
