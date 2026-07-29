package jp.cobolinsight.transpile.emit;

import jp.cobolinsight.engineapi.transpile.GeneratedFile;
import jp.cobolinsight.engineapi.transpile.TargetLanguage;

import java.util.Optional;

/**
 * 対象言語ごとのレンダリング責務。走査(どの項目をどの順で出すか)は {@link RecordClassGenerator} が担い、
 * 本インターフェースは各要素の言語別の字面(クラス枠・アクセサ・述語・ランタイムヘルパ)を出力に書き込む。
 * 各 emit メソッドは {@link LineTrackingEmitter} へ行を追記するだけで、行対応の記録は呼び手の生成器が行う。
 */
public interface LanguageEmitter {

    static LanguageEmitter of(TargetLanguage language) {
        return switch (language) {
            case PYTHON -> new PythonEmitter();
            case JAVA -> new JavaEmitter();
        };
    }

    TargetLanguage language();

    /** インデント1レベル分の文字列。 */
    String indentUnit();

    /** レコードクラスを収める生成ファイル名(拡張子込み)。 */
    String recordFileName(String recordCobolName);

    /** ランタイムヘルパの生成ファイル名。 */
    String runtimeFileName();

    /** 入力非依存で常に同一のランタイムヘルパ(COMP-3/ゾーン10進/BINARY/英数字の encode/decode)。 */
    GeneratedFile runtimeLibrary();

    /** ファイル冒頭(モジュール説明・ランタイム取り込み)を出力する。行対応は付けない。 */
    void emitFileHeader(LineTrackingEmitter out, String programId, String recordCobolName);

    /** クラス定義の頭(宣言・総バイト長定数・コンストラクタ・バッファ参照)を出力する。 */
    void emitClassHeader(LineTrackingEmitter out, String className, String cobolName, int totalBytes);

    /** クラス定義の閉じ(必要な言語のみ)を出力する。 */
    void emitClassFooter(LineTrackingEmitter out);

    /** 集団項目(REDEFINES/OCCURS を含む)の構造を説明するコメント行を出力する。 */
    void emitGroupComment(LineTrackingEmitter out, String cobolName, int offset, int byteLength,
            Optional<Integer> occursCount, Optional<String> redefinesTarget);

    /** 基本項目の get/set アクセサを出力する。 */
    void emitAccessor(LineTrackingEmitter out, AccessorSpec spec);

    /** 88レベル条件名の述語メソッドを出力する。 */
    void emitConditionPredicate(LineTrackingEmitter out, ConditionSpec spec);
}
