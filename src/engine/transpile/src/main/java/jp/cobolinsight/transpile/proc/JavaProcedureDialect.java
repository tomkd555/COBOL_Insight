package jp.cobolinsight.transpile.proc;

import jp.cobolinsight.transpile.emit.Identifiers;
import jp.cobolinsight.transpile.emit.LineTrackingEmitter;

import java.util.ArrayList;
import java.util.List;

/** Java の手続き対訳レンダリング。フラット変数を持つクラスと段落メソッドを出力する。 */
public final class JavaProcedureDialect implements ProcedureDialect {

    @Override
    public String ref(String fieldName) {
        return fieldName;
    }

    @Override
    public String subscript(String indexExpr) {
        return "[(int) (" + indexExpr + " - 1)]";
    }

    @Override
    public String stringLiteral(String content) {
        return "\"" + content.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Override
    public String comparison(String left, RelOp op, String right, boolean stringCompare) {
        if (stringCompare) {
            return switch (op) {
                case EQ -> left + ".equals(" + right + ")";
                case NE -> "!" + left + ".equals(" + right + ")";
                case GT -> left + ".compareTo(" + right + ") > 0";
                case LT -> left + ".compareTo(" + right + ") < 0";
                case GE -> left + ".compareTo(" + right + ") >= 0";
                case LE -> left + ".compareTo(" + right + ") <= 0";
            };
        }
        return left + " " + numericSymbol(op) + " " + right;
    }

    private static String numericSymbol(RelOp op) {
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
        return left + " && " + right;
    }

    @Override
    public String logicalOr(String left, String right) {
        return left + " || " + right;
    }

    @Override
    public String negate(String cond) {
        return "!(" + cond + ")";
    }

    @Override
    public String trueLiteral() {
        // 直訳できない条件の代替。定数 true を while に置くと本体が到達不能になり javac が失敗する。
        // Boolean.TRUE は定数式ではないため到達性解析を通り、値は真のままである。
        return "Boolean.TRUE";
    }

    /**
     * 直訳できない条件を原文から描画する。COBOL の生の被演算子名(未宣言項目・特殊レジスタ)を
     * ガードの実行式へ置くと javac がシンボル解決に失敗するため、コンパイル可能な {@link #trueLiteral()} を
     * 置き、原文の条件はその場のブロックコメントで逐語に残す(恒真値へ黙って落とさない)。
     */
    @Override
    public String rawCondition(String cobolConditionText) {
        return trueLiteral() + " /* " + cobolConditionText.replace("*/", "* /") + " */";
    }

    @Override
    public String programFileName(String programId) {
        return Identifiers.sanitize(programId) + "Program.java";
    }

    @Override
    public void emitProgramPrologue(LineTrackingEmitter out, String programId,
            ProgramSymbols symbols) {
        out.emit("package cobolinsight.generated;");
        out.blank();
        out.emit("/**");
        out.emit(" * " + programId + " の手続き部を逐語対訳した自動生成コード(非最適化・逐語優先)。");
        out.emit(" * データ項目はフラットな変数として扱い、REDEFINES の別名共有と OCCURS の添字は簡約する。");
        out.emit(" * 逐語対訳であり、演算や桁詰めの最適化は行わない。桁数・小数スケール・固定長の空白詰めは");
        out.emit(" * フラット変数では再現せず、原文の PICTURE 句とレコードクラスのバイト列アクセサを正とする。");
        out.emit(" */");
        out.emit("public final class " + Identifiers.sanitize(programId) + "Program {");
        out.indent();
        out.blank();
        for (ProgramSymbols.DataSymbol symbol : symbols.declarations()) {
            out.emit(declaration(symbol));
        }
        out.blank();
        out.emit("void call_program(String name, Object... args) {");
        out.indent();
        out.emit("// CALL のスタブ。プログラム間の実呼出・値渡しは模擬しない。");
        out.dedent();
        out.emit("}");
    }

    private static String declaration(ProgramSymbols.DataSymbol symbol) {
        String base = symbol.isString() ? "String" : "long";
        String comment = symbol.valueClause().map(v -> "  // VALUE " + v.trim()).orElse("");
        List<Integer> counts = symbol.occursCounts();
        if (counts.isEmpty()) {
            String init = symbol.isString() ? " = \"\"" : "";
            return base + " " + symbol.fieldName() + init + ";" + comment;
        }
        StringBuilder type = new StringBuilder(base);
        StringBuilder dims = new StringBuilder();
        for (int count : counts) {
            type.append("[]");
            dims.append("[").append(count).append("]");
        }
        return type + " " + symbol.fieldName() + " = new " + base + dims + ";" + comment;
    }

    @Override
    public void emitProgramEpilogue(LineTrackingEmitter out) {
        out.dedent();
        out.emit("}");
    }

    @Override
    public void openMethod(LineTrackingEmitter out, String methodName) {
        out.blank();
        out.emit("void " + methodName + "() {");
        out.indent();
    }

    @Override
    public void closeMethod(LineTrackingEmitter out) {
        out.dedent();
        out.emit("}");
    }

    @Override
    public void openIf(LineTrackingEmitter out, String cond) {
        out.emit("if (" + cond + ") {");
        out.indent();
    }

    @Override
    public void openElseIf(LineTrackingEmitter out, String cond) {
        out.dedent();
        out.emit("} else if (" + cond + ") {");
        out.indent();
    }

    @Override
    public void openElse(LineTrackingEmitter out) {
        out.dedent();
        out.emit("} else {");
        out.indent();
    }

    @Override
    public void closeBranch(LineTrackingEmitter out) {
        out.dedent();
        out.emit("}");
    }

    @Override
    public void openWhile(LineTrackingEmitter out, String cond) {
        out.emit("while (" + cond + ") {");
        out.indent();
    }

    @Override
    public void closeWhile(LineTrackingEmitter out) {
        out.dedent();
        out.emit("}");
    }

    @Override
    public void emitTimesLoop(LineTrackingEmitter out, String methodName, String count) {
        out.emit("for (int _i = 0; _i < " + count + "; _i++) {");
        out.indent();
        out.emit(methodName + "();");
        out.dedent();
        out.emit("}");
    }

    @Override
    public void emitAssign(LineTrackingEmitter out, String target, String value) {
        out.emit(target + " = " + value + ";");
    }

    @Override
    public void emitInvoke(LineTrackingEmitter out, String methodName) {
        out.emit(methodName + "();");
    }

    @Override
    public void emitDisplay(LineTrackingEmitter out, List<DisplayPart> parts) {
        if (parts.isEmpty()) {
            out.emit("System.out.println();");
            return;
        }
        List<String> rendered = new ArrayList<>();
        for (DisplayPart part : parts) {
            // 数値項目は long として宣言される。先頭が文字列でないと + が連結ではなく加算になり、
            // 複数の値の合計を1つ印字してしまう。先頭だけ String.valueOf で包めば連結になる。
            rendered.add(rendered.isEmpty() && !part.isString()
                    ? "String.valueOf(" + part.text() + ")" : part.text());
        }
        out.emit("System.out.println(" + String.join(" + ", rendered) + ");");
    }

    @Override
    public void emitCallProgram(LineTrackingEmitter out, String target, List<String> argNames,
            String note) {
        out.emit("// CALL " + target + usingClause(argNames) + " — " + note);
        List<String> args = new ArrayList<>();
        args.add("\"" + target + "\"");
        for (String name : argNames) {
            args.add("\"" + name + "\"");
        }
        out.emit("call_program(" + String.join(", ", args) + ");");
    }

    private static String usingClause(List<String> argNames) {
        return argNames.isEmpty() ? "" : " USING " + String.join(", ", argNames);
    }

    @Override
    public void emitReturn(LineTrackingEmitter out, String verb) {
        // COBOL では GOBACK/STOP RUN 以降の文が同段落に残る場合がある。無条件 return は後続を
        // 到達不能にして Java のコンパイルを妨げるため、if (true) で包んで到達性解析を通す。
        out.emit("if (true) return; // " + verb);
    }

    @Override
    public void emitNoOp(LineTrackingEmitter out, String verb) {
        out.emit("; // " + verb);
    }

    @Override
    public void emitBlockFiller(LineTrackingEmitter out) {
        // Java は空ブロック・コメントのみのブロックが有効なため詰め物は要らない。
    }

    @Override
    public void emitComment(LineTrackingEmitter out, String text) {
        out.emit("// " + text);
    }

    @Override
    public void emitUntranslated(LineTrackingEmitter out, List<String> cobolLines, String note) {
        out.emit("// [直訳不能: " + note + "]");
        for (String line : cobolLines) {
            out.emit("// " + line);
        }
    }

    @Override
    public void emitEmbeddedStub(LineTrackingEmitter out, String command, List<String> cobolLines,
            String note) {
        out.emit("// [直訳不能: " + note + "]");
        for (String line : cobolLines) {
            out.emit("// " + line);
        }
        // if (true) で包み、後続文を到達可能に保つ(EXEC ブロックは連続して現れる)。
        out.emit("if (true) throw new UnsupportedOperationException("
                + stringLiteral(command + " は直訳不能") + ");");
    }
}
