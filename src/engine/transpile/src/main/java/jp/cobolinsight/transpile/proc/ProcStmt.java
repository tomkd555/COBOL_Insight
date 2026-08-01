package jp.cobolinsight.transpile.proc;

import jp.cobolinsight.engineapi.source.SourceRange;

import java.util.List;

/**
 * 手続き文の中間表現。1 COBOL 文を言語非依存の構造へ写す。各文は由来する COBOL の {@link SourceRange} と
 * 注記(直訳不能・簡約の説明。空なら 1:1 の逐語対訳)を持ち、{@link ProcedureRenderer} が行対応へ反映する。
 */
public sealed interface ProcStmt {

    SourceRange range();

    /** この文全体の注記(空なら注記なし)。 */
    String note();

    /** 代入(MOVE 送信→受信・ADD/COMPUTE の受信=式)。 */
    record Assign(PExpr.Ref target, PExpr value, SourceRange range, String note) implements ProcStmt {
    }

    /** 分岐(IF→arms 1件+else、EVALUATE→arms n件+other)。elseBody が空なら else なし。 */
    record Branch(List<Arm> arms, List<ProcStmt> elseBody, SourceRange range, String note)
            implements ProcStmt {
        public Branch {
            arms = List.copyOf(arms);
            elseBody = List.copyOf(elseBody);
        }
    }

    /** 分岐の1本の腕(条件と本体)。 */
    record Arm(PCond cond, List<ProcStmt> body) {
        public Arm {
            body = List.copyOf(body);
        }
    }

    /**
     * 反復(PERFORM UNTIL・inline/out-of-line、PERFORM VARYING)。until を満たさない間くり返す。
     * varyingVar/varyingInit/varyingStep は VARYING 句を復元できたときのみ非 null。
     */
    record Loop(PCond until, List<ProcStmt> body, PExpr.Ref varyingVar, PExpr varyingInit,
            PExpr varyingStep, SourceRange range, String note) implements ProcStmt {
        public Loop {
            body = List.copyOf(body);
        }
    }

    /** 段落・節への PERFORM(関数/メソッド呼出)。 */
    record PerformCall(String methodName, SourceRange range, String note) implements ProcStmt {
    }

    /** PERFORM 段落 n TIMES(回数反復)。 */
    record PerformTimes(String methodName, PExpr count, SourceRange range, String note)
            implements ProcStmt {
    }

    /** PERFORM 段落 THRU 段落(範囲の順次呼出)。 */
    record PerformThru(List<String> methodNames, SourceRange range, String note) implements ProcStmt {
        public PerformThru {
            methodNames = List.copyOf(methodNames);
        }
    }

    /** DISPLAY(標準出力への連結出力)。 */
    record Display(List<PExpr> operands, SourceRange range, String note) implements ProcStmt {
        public Display {
            operands = List.copyOf(operands);
        }
    }

    /** CALL(プログラム呼出)。argDescriptors は各引数の記述(受け渡し様式を含む)。 */
    record CallProgram(String target, List<String> argDescriptors, SourceRange range, String note)
            implements ProcStmt {
        public CallProgram {
            argDescriptors = List.copyOf(argDescriptors);
        }
    }

    /** STOP RUN・GOBACK・EXIT PROGRAM(呼出元へ復帰)。verb は原動詞句。 */
    record Return(String verb, SourceRange range, String note) implements ProcStmt {
    }

    /** CONTINUE・EXIT(無処理)。 */
    record NoOp(String verb, SourceRange range, String note) implements ProcStmt {
    }

    /** 直訳不能・未対応の文(ファイル I/O・STRING 等)。原文行をコメント化し注記で可視化する。 */
    record Untranslated(List<String> cobolTextLines, SourceRange range, String note)
            implements ProcStmt {
        public Untranslated {
            cobolTextLines = List.copyOf(cobolTextLines);
        }
    }

    /**
     * EXEC CICS / EXEC SQL の直訳不能ブロック。原文をコメント保存した上で、実行時に到達すると例外を投げる
     * 注記スタブ(Python は NotImplementedError、Java は UnsupportedOperationException)として出力する。
     * command は例外文言に使う命令名(例「EXEC CICS RECEIVE MAP」)、note はオペランドを含む注記。
     * 複数の COBOL 行を1つのスタブへ畳むため、行対応は N:1(MANY_TO_ONE)になる。
     */
    record EmbeddedStub(String command, List<String> cobolTextLines, SourceRange range, String note)
            implements ProcStmt {
        public EmbeddedStub {
            cobolTextLines = List.copyOf(cobolTextLines);
        }
    }
}
