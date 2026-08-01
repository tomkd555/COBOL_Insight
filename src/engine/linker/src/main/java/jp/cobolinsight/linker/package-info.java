/**
 * 呼出関係グラフの統合を行う。EXEC PGM=とPROGRAM-IDの対応、静的CALL解決、
 * 動的CALLの定数伝播解決、未解決ノード・外部ユーティリティノードの型付け、
 * EXEC CICSのトランザクション遷移辺とマップ参照辺の追加、トランザクションIDからプログラムへの解決を行う。
 */
package jp.cobolinsight.linker;
