package cobolinsight.generated;

/**
 * SYK005 の手続き部を逐語対訳した自動生成コード(非最適化・逐語優先)。
 * データ項目はフラットな変数として扱い、REDEFINES の別名共有と OCCURS の添字は簡約する。
 */
public final class SYK005Program {

    String WS_編集メッセージ = "";
    String LK_メッセージ区分 = "";
    String LK_メッセージ内容 = "";

    void call_program(String name, Object... args) {
        // CALL のスタブ。プログラム間の実呼出・値渡しは模擬しない。
    }

    void _0000_メイン処理() {
        if (LK_メッセージ区分.equals("E1")) {
            _1000_エラーメッセージ編集();
        } else if (LK_メッセージ区分.equals("W1")) {
            _2000_警告メッセージ編集();
        } else {
            _3000_情報メッセージ編集();
        }
        if (true) return; // GOBACK
    }

    void _1000_エラーメッセージ編集() {
        // [直訳不能: 文字列操作(STRING/INSPECT 等)は逐語コメントのみ]
        // STRING 'ERROR : ' LK-メッセージ内容 DELIMITED BY SIZE
        // INTO WS-編集メッセージ
        // END-STRING
        System.out.println(WS_編集メッセージ);
        if (true) return; // GOBACK
        System.out.println("このメッセージは出力されない");
    }

    void _2000_警告メッセージ編集() {
        // [直訳不能: 文字列操作(STRING/INSPECT 等)は逐語コメントのみ]
        // STRING 'WARN  : ' LK-メッセージ内容 DELIMITED BY SIZE
        // INTO WS-編集メッセージ
        // END-STRING
        System.out.println(WS_編集メッセージ);
    }

    void _3000_情報メッセージ編集() {
        // [直訳不能: 文字列操作(STRING/INSPECT 等)は逐語コメントのみ]
        // STRING 'INFO  : ' LK-メッセージ内容 DELIMITED BY SIZE
        // INTO WS-編集メッセージ
        // END-STRING
        System.out.println(WS_編集メッセージ);
    }
}
