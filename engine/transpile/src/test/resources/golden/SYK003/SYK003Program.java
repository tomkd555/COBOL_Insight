package cobolinsight.generated;

/**
 * SYK003 の手続き部を逐語対訳した自動生成コード(非最適化・逐語優先)。
 * データ項目はフラットな変数として扱い、REDEFINES の別名共有と OCCURS の添字は簡約する。
 * 逐語対訳であり、演算や桁詰めの最適化は行わない。桁数・小数スケール・固定長の空白詰めは
 * フラット変数では再現せず、原文の PICTURE 句とレコードクラスのバイト列アクセサを正とする。
 */
public final class SYK003Program {

    long WS_I;
    long WS_合計;  // VALUE ZERO
    long WS_旧チェック方式件数;  // VALUE ZERO
    String SYK1_受注番号 = "";
    long SYK1_受注日;
    long SYK1_受注日_年;
    long SYK1_受注日_月;
    long SYK1_受注日_日;
    String SYK1_得意先コード = "";
    long SYK1_受注金額合計;
    long SYK1_明細件数;
    String[] SYK1_商品コード = new String[10];
    long[] SYK1_数量 = new long[10];
    long[] SYK1_単価 = new long[10];
    long[] SYK1_金額 = new long[10];
    String SYK1_処理区分 = "";
    String LK_チェック結果 = "";

    void call_program(String name, Object... args) {
        // CALL のスタブ。プログラム間の実呼出・値渡しは模擬しない。
    }

    void _0000_メイン処理() {
        WS_合計 = 0;
        WS_I = 1;
        while (!(WS_I > SYK1_明細件数)) {
            WS_合計 = WS_合計 + SYK1_金額[(int) (WS_I - 1)];
            WS_I = WS_I + 1;
        }
        if (WS_合計 == SYK1_受注金額合計) {
            LK_チェック結果 = "1";
        } else {
            LK_チェック結果 = "0";
        }
        if (true) return; // GOBACK
    }
}
