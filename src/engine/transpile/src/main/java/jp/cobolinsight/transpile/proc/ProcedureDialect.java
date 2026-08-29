package jp.cobolinsight.transpile.proc;

import jp.cobolinsight.core.transpile.TargetLanguage;
import jp.cobolinsight.transpile.emit.LineTrackingEmitter;

import java.util.List;

/**
 * 手続き対訳の言語別レンダリング責務。走査・行対応は {@link ProcedureRenderer} が担い、本インターフェースは
 * 各要素(クラス枠・フィールド宣言・制御構造の開閉・代入・呼出・出力・注記)の字面を出力へ書き込む。
 * 制御構造の開閉メソッドは {@link LineTrackingEmitter} のインデントを自身で増減する。
 */
public interface ProcedureDialect {

    static ProcedureDialect of(TargetLanguage language) {
        return switch (language) {
            case PYTHON -> new PythonProcedureDialect();
            case JAVA -> new JavaProcedureDialect();
        };
    }

    /** インデント1レベル分の文字列。 */
    default String indentUnit() {
        return "    ";
    }

    /** DISPLAY 1オペランドの描画結果(文字列型か否かを含む)。 */
    record DisplayPart(String text, boolean isString) {
    }

    // ---- 式・条件の字面 ----

    /** データ参照の字面(例: Python は self.NAME、Java は NAME)。 */
    String ref(String fieldName);

    /** 添字1次元の字面。COBOL の1始まり添字を0始まりへ補正する(Java は int へ縮小変換する)。 */
    String subscript(String indexExpr);

    /** 文字列リテラルの字面(引用・エスケープ込み)。 */
    String stringLiteral(String content);

    /** 関係比較の字面。stringCompare が真なら文字列比較の作法に従う。 */
    String comparison(String left, RelOp op, String right, boolean stringCompare);

    String logicalAnd(String left, String right);

    String logicalOr(String left, String right);

    String negate(String cond);

    String trueLiteral();

    /**
     * 直訳できない条件({@link PCond.Raw})を原文テキストから描画する。字面は言語ごとに異なるが、
     * いずれも COBOL の生の被演算子名(未宣言項目・特殊レジスタ)を保ち、恒真値へ黙って落とさない。
     */
    String rawCondition(String cobolConditionText);

    // ---- ファイル枠・宣言 ----

    /** 手続き対訳を収める生成ファイル名(拡張子込み)。 */
    String programFileName(String programId);

    /** ファイル冒頭とクラス頭、フィールド宣言、CALL スタブまでを出力する(行対応は付けない)。 */
    void emitProgramPrologue(LineTrackingEmitter out, String programId, ProgramSymbols symbols);

    /** クラスの閉じ(必要な言語のみ)を出力する。 */
    void emitProgramEpilogue(LineTrackingEmitter out);

    // ---- 段落・節(メソッド)----

    void openMethod(LineTrackingEmitter out, String methodName);

    void closeMethod(LineTrackingEmitter out);

    // ---- 分岐(IF/EVALUATE の if/elif/else 連鎖)----

    void openIf(LineTrackingEmitter out, String cond);

    void openElseIf(LineTrackingEmitter out, String cond);

    void openElse(LineTrackingEmitter out);

    void closeBranch(LineTrackingEmitter out);

    // ---- 反復 ----

    void openWhile(LineTrackingEmitter out, String cond);

    void closeWhile(LineTrackingEmitter out);

    void emitTimesLoop(LineTrackingEmitter out, String methodName, String count);

    // ---- 単文 ----

    void emitAssign(LineTrackingEmitter out, String target, String value);

    /** 段落 PERFORM(メソッド呼出)。 */
    void emitInvoke(LineTrackingEmitter out, String methodName);

    void emitDisplay(LineTrackingEmitter out, List<DisplayPart> parts);

    void emitCallProgram(LineTrackingEmitter out, String target, List<String> argNames, String note);

    void emitReturn(LineTrackingEmitter out, String verb);

    void emitNoOp(LineTrackingEmitter out, String verb);

    /** 実行文の無いブロックを言語上有効に保つ詰め物(Python は pass、Java は無し)。 */
    void emitBlockFiller(LineTrackingEmitter out);

    void emitComment(LineTrackingEmitter out, String text);

    /** 直訳不能な原文を注記付きコメント群として出力する。 */
    void emitUntranslated(LineTrackingEmitter out, List<String> cobolLines, String note);

    /**
     * EXEC CICS / EXEC SQL の直訳不能ブロックを注記スタブとして出力する。注記付きヘッダコメント・原文コメント群に
     * 続けて、実行時に到達すると例外を投げる1行(Python は raise、Java は throw)を出力する。command は例外文言。
     */
    void emitEmbeddedStub(LineTrackingEmitter out, String command, List<String> cobolLines,
            String note);
}
