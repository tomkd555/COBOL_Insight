package jp.cobolinsight.core.callgraph;

/** 呼出関係グラフのノード11種別。 */
public enum NodeKind {
    /** JCLのJOB単位。 */
    JOB,
    /** JCLのEXECステップ単位。 */
    STEP,
    /** COBOLプログラム(PROGRAM-ID)。 */
    PROGRAM,
    /** COBOLの段落・節。 */
    PARAGRAPH,
    /** DD文が参照するデータセット。 */
    DATASET,
    /** 埋め込みSQLが参照するDb2の表・ビュー。 */
    DB2_TABLE,
    /** 定数伝播で解決できない動的CALL先(指定変数名を属性に保持)。 */
    UNRESOLVED,
    /** DFSORT・IDCAMS・IEBGENER、およびPL/I・アセンブラの外部リーフ(種別タグを属性に保持)。 */
    EXTERNAL_UTILITY,
    /** CICSトランザクション(トランザクションID)。 */
    TRANSACTION,
    /** BMSのマップセット・マップ。 */
    BMS_MAP,
    /**
     * 復号・構文解析に失敗して呼出関係を読み取れない資産(相対パスと失敗理由を属性に保持)。
     * 呼出関係が不明なため辺を持たない孤立ノードとして置き、図が全体を表すという誤読を防ぐ。
     */
    UNANALYZABLE
}
