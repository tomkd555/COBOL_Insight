package jp.cobolinsight.transpile.proc;

import jp.cobolinsight.transpile.emit.Identifiers;
import jp.cobolinsight.transpile.emit.LineTrackingEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Python procedure-division dialect rendering. Emits a class with flat variables and paragraph methods. */
public final class PythonProcedureDialect implements ProcedureDialect {

    @Override
    public String ref(String fieldName) {
        return "self." + fieldName;
    }

    @Override
    public String subscript(String indexExpr) {
        return "[" + indexExpr + " - 1]";
    }

    @Override
    public String stringLiteral(String content) {
        return "\"" + content.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Override
    public String comparison(String left, RelOp op, String right, boolean stringCompare) {
        return left + " " + symbol(op) + " " + right;
    }

    private static String symbol(RelOp op) {
        return switch (op) {
            case EQ -> "==";
            case NE -> "!=";
            case GT -> ">";
            case LT -> "<";
            case GE -> ">=";
            case LE -> "<=";
        };
    }

    @Override
    public String logicalAnd(String left, String right) {
        return left + " and " + right;
    }

    @Override
    public String logicalOr(String left, String right) {
        return left + " or " + right;
    }

    @Override
    public String negate(String cond) {
        return "not (" + cond + ")";
    }

    @Override
    public String trueLiteral() {
        return "True";
    }

    /** Words for COBOL class conditions and sign conditions written postfix. Targets rewritten into prefix predicate-call notation. */
    private static final Set<String> POSTFIX_CONDITIONS = Set.of(
            "NUMERIC", "ALPHABETIC", "ALPHABETIC-LOWER", "ALPHABETIC-UPPER",
            "POSITIVE", "NEGATIVE", "ZERO");

    /**
     * Renders a condition that cannot be translated literally, from the source text. Normalizes
     * COBOL's relational and logical operators into Python notation
     * (= &rarr; ==, &lt;&gt; &rarr; !=, AND &rarr; and, OR &rarr; or; a relational operator
     * following NOT becomes its negated operator, e.g. NOT = &rarr; != and NOT &gt; &rarr; &lt;=),
     * while leaving operand raw names untouched. Python's grammar accepts undeclared names, so the
     * original condition reads naturally, as in {@code while not (SQLCODE == 100):}. Class
     * conditions and sign conditions written postfix have no equivalent Python operator, so they
     * are rewritten into prefix predicate-call notation that keeps the COBOL word
     * ({@code NOT NUMERIC} &rarr; {@code not NUMERIC(項目)}).
     */
    @Override
    public String rawCondition(String cobolConditionText) {
        List<String> tokens =
                foldPostfixConditions(OperandParser.tokenizeCondition(cobolConditionText));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            String rendered;
            if (token.equalsIgnoreCase("NOT") && i + 1 < tokens.size()
                    && negatedRelation(tokens.get(i + 1)) != null) {
                rendered = negatedRelation(tokens.get(i + 1));
                i++;
            } else if (token.equalsIgnoreCase("AND")) {
                rendered = "and";
            } else if (token.equalsIgnoreCase("OR")) {
                rendered = "or";
            } else {
                rendered = switch (token) {
                    case "=" -> "==";
                    case "<>" -> "!=";
                    default -> token;
                };
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(rendered);
        }
        return sb.toString();
    }

    /**
     * Rewrites a postfix condition like "operand [IS] [NOT] NUMERIC" into the sequence
     * "not NUMERIC(operand)". Tokens that do not match pass through unchanged.
     */
    private static List<String> foldPostfixConditions(List<String> tokens) {
        List<String> folded = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            String operand = tokens.get(i);
            int keyword = i + 1;
            if (keyword < tokens.size() && tokens.get(keyword).equalsIgnoreCase("IS")) {
                keyword++;
            }
            boolean negated = keyword < tokens.size() && tokens.get(keyword).equalsIgnoreCase("NOT");
            if (negated) {
                keyword++;
            }
            if (isOperand(operand) && keyword < tokens.size() && isPostfixCondition(tokens.get(keyword))) {
                if (negated) {
                    folded.add("not");
                }
                folded.add(tokens.get(keyword) + "(" + operand + ")");
                i = keyword;
                continue;
            }
            folded.add(operand);
        }
        return folded;
    }

    private static boolean isPostfixCondition(String token) {
        return POSTFIX_CONDITIONS.contains(token.toUpperCase(Locale.ROOT));
    }

    /** Whether the token can stand as an operand (operators, logical words, and class-condition words are not operands). */
    private static boolean isOperand(String token) {
        return !token.isEmpty() && Character.isLetterOrDigit(token.charAt(0))
                && !token.equalsIgnoreCase("AND") && !token.equalsIgnoreCase("OR")
                && !token.equalsIgnoreCase("NOT") && !token.equalsIgnoreCase("IS")
                && !isPostfixCondition(token);
    }

    /** Maps a relational operator following COBOL's NOT to its negated Python operator. Returns null if it is not a relational operator. */
    private static String negatedRelation(String op) {
        return switch (op) {
            case "=" -> "!=";
            case "<>" -> "==";
            case ">" -> "<=";
            case "<" -> ">=";
            case ">=" -> "<";
            case "<=" -> ">";
            default -> null;
        };
    }

    @Override
    public String programFileName(String programId) {
        return Identifiers.sanitize(programId) + "_program.py";
    }

    @Override
    public void emitProgramPrologue(LineTrackingEmitter out, String programId,
            ProgramSymbols symbols) {
        out.emit("\"\"\"" + programId + " の手続き部を逐語対訳した自動生成コード(非最適化・逐語優先)。");
        out.emit("データ項目はフラットな変数として扱い、REDEFINES の別名共有と OCCURS の添字は簡約する。");
        out.emit("逐語対訳であり、演算や桁詰めの最適化は行わない。桁数・小数スケール・固定長の空白詰めは");
        out.emit("フラット変数では再現せず、原文の PICTURE 句とレコードクラスのバイト列アクセサを正とする。\"\"\"");
        out.blank();
        out.blank();
        out.emit("class " + Identifiers.sanitize(programId) + "Program:");
        out.indent();
        out.blank();
        out.emit("def __init__(self):");
        out.indent();
        if (symbols.declarations().isEmpty()) {
            out.emit("pass");
        }
        for (ProgramSymbols.DataSymbol symbol : symbols.declarations()) {
            out.emit("self." + symbol.fieldName() + " = " + initValue(symbol) + valueComment(symbol));
        }
        out.dedent();
        out.blank();
        out.emit("def call_program(self, name, *args):");
        out.indent();
        out.emit("\"\"\"CALL のスタブ。プログラム間の実呼出・値渡しは模擬しない。\"\"\"");
        out.emit("pass");
        out.dedent();
    }

    private static String initValue(ProgramSymbols.DataSymbol symbol) {
        String element = symbol.isString() ? "\"\"" : "0";
        String value = element;
        List<Integer> counts = symbol.occursCounts();
        for (int i = counts.size() - 1; i >= 0; i--) {
            value = "[" + value + "] * " + counts.get(i);
        }
        return value;
    }

    private static String valueComment(ProgramSymbols.DataSymbol symbol) {
        return symbol.valueClause().map(v -> "  # VALUE " + v.trim()).orElse("");
    }

    @Override
    public void emitProgramEpilogue(LineTrackingEmitter out) {
        // Python represents blocks by indentation, so there is no explicit close.
    }

    @Override
    public void openMethod(LineTrackingEmitter out, String methodName) {
        out.blank();
        out.emit("def " + methodName + "(self):");
        out.indent();
    }

    @Override
    public void closeMethod(LineTrackingEmitter out) {
        out.dedent();
    }

    @Override
    public void openIf(LineTrackingEmitter out, String cond) {
        out.emit("if " + cond + ":");
        out.indent();
    }

    @Override
    public void openElseIf(LineTrackingEmitter out, String cond) {
        out.dedent();
        out.emit("elif " + cond + ":");
        out.indent();
    }

    @Override
    public void openElse(LineTrackingEmitter out) {
        out.dedent();
        out.emit("else:");
        out.indent();
    }

    @Override
    public void closeBranch(LineTrackingEmitter out) {
        out.dedent();
    }

    @Override
    public void openWhile(LineTrackingEmitter out, String cond) {
        out.emit("while " + cond + ":");
        out.indent();
    }

    @Override
    public void closeWhile(LineTrackingEmitter out) {
        out.dedent();
    }

    @Override
    public void emitTimesLoop(LineTrackingEmitter out, String methodName, String count) {
        out.emit("for _ in range(" + count + "):");
        out.indent();
        out.emit("self." + methodName + "()");
        out.dedent();
    }

    @Override
    public void emitAssign(LineTrackingEmitter out, String target, String value) {
        out.emit(target + " = " + value);
    }

    @Override
    public void emitInvoke(LineTrackingEmitter out, String methodName) {
        out.emit("self." + methodName + "()");
    }

    @Override
    public void emitDisplay(LineTrackingEmitter out, List<DisplayPart> parts) {
        if (parts.isEmpty()) {
            out.emit("print()");
            return;
        }
        List<String> rendered = new ArrayList<>();
        for (DisplayPart part : parts) {
            rendered.add(part.isString() ? part.text() : "str(" + part.text() + ")");
        }
        out.emit("print(" + String.join(" + ", rendered) + ")");
    }

    @Override
    public void emitCallProgram(LineTrackingEmitter out, String target, List<String> argNames,
            String note) {
        out.emit("# CALL " + target + usingClause(argNames) + " — " + note);
        List<String> args = new ArrayList<>();
        args.add("\"" + target + "\"");
        for (String name : argNames) {
            args.add("\"" + name + "\"");
        }
        out.emit("self.call_program(" + String.join(", ", args) + ")");
    }

    private static String usingClause(List<String> argNames) {
        return argNames.isEmpty() ? "" : " USING " + String.join(", ", argNames);
    }

    @Override
    public void emitReturn(LineTrackingEmitter out, String verb) {
        out.emit("return  # " + verb);
    }

    @Override
    public void emitNoOp(LineTrackingEmitter out, String verb) {
        out.emit("pass  # " + verb);
    }

    @Override
    public void emitBlockFiller(LineTrackingEmitter out) {
        out.emit("pass");
    }

    @Override
    public void emitComment(LineTrackingEmitter out, String text) {
        out.emit("# " + text);
    }

    @Override
    public void emitUntranslated(LineTrackingEmitter out, List<String> cobolLines, String note) {
        out.emit("# [直訳不能: " + note + "]");
        for (String line : cobolLines) {
            out.emit("# " + line);
        }
    }

    @Override
    public void emitEmbeddedStub(LineTrackingEmitter out, String command, List<String> cobolLines,
            String note) {
        out.emit("# [直訳不能: " + note + "]");
        for (String line : cobolLines) {
            out.emit("# " + line);
        }
        out.emit("raise NotImplementedError(" + stringLiteral(command + " は直訳不能") + ")");
    }
}
