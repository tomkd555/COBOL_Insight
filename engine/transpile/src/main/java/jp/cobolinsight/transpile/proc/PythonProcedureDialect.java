package jp.cobolinsight.transpile.proc;

import jp.cobolinsight.transpile.emit.Identifiers;
import jp.cobolinsight.transpile.emit.LineTrackingEmitter;

import java.util.ArrayList;
import java.util.List;

/** Python の手続き対訳レンダリング。フラット変数を持つクラスと段落メソッドを出力する。 */
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

    /**
     * 直訳できない条件を原文から描画する。COBOL の関係演算子と論理演算子を Python の字面へ正規化し
     * (= → ==、&lt;&gt; → !=、AND → and、OR → or、NOT に続く関係演算子は否定した演算子 =&gt;
     * NOT = → !=・NOT &gt; → &lt;= 等)、被演算子の生名はそのまま残す。Python は未宣言名でも構文上は
     * 妥当で、{@code while not (SQLCODE == 100):} のように原文の条件が読める。
     */
    @Override
    public String rawCondition(String cobolConditionText) {
        List<String> tokens = OperandParser.tokenizeCondition(cobolConditionText);
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

    /** COBOL の NOT に続く関係演算子を、否定した Python 演算子へ写す。関係演算子でなければ null。 */
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
        out.emit("データ項目はフラットな変数として扱い、REDEFINES の別名共有と OCCURS の添字は簡約する。\"\"\"");
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
        // Python はブロックをインデントで表すため、明示的な閉じは無い。
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
