package cobolinsight.generated;

/**
 * SYK004 の手続き部を逐語対訳した自動生成コード(非最適化・逐語優先)。
 * データ項目はフラットな変数として扱い、REDEFINES の別名共有と OCCURS の添字は簡約する。
 */
public final class SYK004Program {

    long WS_在庫残数;
    long WS_引当数量;
    String WS_判定区分 = "";
    String LK_商品コード = "";
    long LK_要求数量;
    String LK_引当可否 = "";

    void call_program(String name, Object... args) {
        // CALL のスタブ。プログラム間の実呼出・値渡しは模擬しない。
    }

    void _0000_メイン処理() {
        _1000_在庫確認();
        _2000_引当判定();
        if (true) return; // GOBACK
    }

    void _1000_在庫確認() {
        if (!LK_商品コード.equals(" ")) {
            WS_在庫残数 = 999;
        }
    }

    void _2000_引当判定() {
        if (WS_在庫残数 >= LK_要求数量) {
            LK_引当可否 = "1";
        } else {
            LK_引当可否 = "0";
        }
    }

    void _9999_未使用処理() {
        System.out.println("在庫引当判定：旧ロジック（廃止済み）");
        WS_引当数量 = 0;
    }
}
