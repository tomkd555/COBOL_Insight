/**
 * テストが使う資産一覧と指摘の見本。engine が実際に書く値域へそろえてある。
 * NODE.type は BMS/PROGRAM/COPYBOOK/JCL、codepage は CodePage.charsetName
 * (UTF-8 / windows-31j / x-IBM930 / x-IBM939)である。AssetInventoryItem.findingCount は scan 由来
 * (復号・構文解析の失敗)の件数であり、ルールの指摘の件数ではない。
 */

import type { AssetInventoryItem, SarifFinding } from "../../../../shared/engine-api";

export const SAMPLE_INVENTORY: readonly AssetInventoryItem[] = [
  { id: 1, path: "bms/SYKMAP1.bms", name: "SYKMAP1.bms", type: "BMS", codepage: "UTF-8", byteSize: 1140, findingCount: 0 },
  { id: 2, path: "cobol/SYK001.cbl", name: "SYK001.cbl", type: "PROGRAM", codepage: "windows-31j", byteSize: 4200, findingCount: 0 },
  // 復号は成功したが構文解析に失敗した資産。NODE は PROGRAM として登録され、
  // parse-failure の finding が 1 件記録される。
  { id: 3, path: "cobol/SYK002.cbl", name: "SYK002.cbl", type: "PROGRAM", codepage: "windows-31j", byteSize: 3800, findingCount: 1 },
  // 復号に失敗した資産。codepage は null、NODE 未登録(UNKNOWN)で decode-failure が 1 件記録される。
  { id: 4, path: "cobol/SYKENC1.cbl", name: "SYKENC1.cbl", type: "UNKNOWN", codepage: null, byteSize: 512, findingCount: 1 },
  { id: 5, path: "copybook/SYKCPY1.cpy", name: "SYKCPY1.cpy", type: "COPYBOOK", codepage: "UTF-8", byteSize: 640, findingCount: 0 },
  { id: 6, path: "jcl/SYKD010.jcl", name: "SYKD010.jcl", type: "JCL", codepage: "UTF-8", byteSize: 900, findingCount: 0 },
];

/** 指摘一覧(lint)用。高/中/低/警告 と R004/R017/R018 の diff ありルールを含む。 */
export const SAMPLE_FINDINGS: readonly SarifFinding[] = [
  { ruleId: "R008", level: "warning", message: "PERFORM文が単一の段落名 1000-初期化処理 のみを指定し、THRU句で終端を明示していない。", file: "cobol/SYK001.cbl", startLine: 73, startColumn: 12 },
  { ruleId: "R017", level: "error", message: "READ ORDIN の実行後、FILE STATUS 変数 WS-ORDIN-STATUS を検査していない。", file: "cobol/SYK001.cbl", startLine: 85, startColumn: 1 },
  { ruleId: "R005", level: "error", message: "表 ORD1-金額 の添字が OCCURS 上限 10 を超え得る。範囲外参照になる。", file: "cobol/SYK001.cbl", startLine: 114, startColumn: 1 },
  { ruleId: "R001", level: "error", message: "未初期化の可能性がある WS-検証金額 を、値を設定する前に参照している。", file: "cobol/SYK001.cbl", startLine: 121, startColumn: 1 },
  { ruleId: "R009", level: "warning", message: "GO TO 文が構造化された呼出関係から外れる制御移動を行っている。", file: "cobol/SYK002.cbl", startLine: 124, startColumn: 1 },
  { ruleId: "R002", level: "note", message: "データ項目 WS-旧チェック方式件数 は PROCEDURE DIVISION から参照されていない。", file: "cobol/SYK003.cbl", startLine: 20, startColumn: 12 },
  { ruleId: "R011", level: "warning", message: "段落 9999-未使用処理 はどの PERFORM・GO TO からも参照されない。", file: "cobol/SYK004.cbl", startLine: 47, startColumn: 1 },
  { ruleId: "R018", level: "error", message: "EXEC SQL UPDATE SYKDB.ZAIKOM の実行後、SQLCODE・SQLSTATE を検査していない。", file: "cobol/SYK006.cbl", startLine: 119, startColumn: 1 },
  { ruleId: "R004", level: "error", message: "COMPUTE 文に ON SIZE ERROR 句が無く、けたあふれが検知されない。", file: "cobol/SYK007.cbl", startLine: 79, startColumn: 1 },
  { ruleId: "R022", level: "warning", message: "CICS 参加プログラム SYK009 は EXEC CICS RETURN を持たずに終端する。", file: "cobol/SYK009.cbl", startLine: 21, startColumn: 1 },
];

/** SQL指摘(sql-lint)用。S001〜S006 を扱う。 */
export const SAMPLE_SQL_FINDINGS: readonly SarifFinding[] = [
  { ruleId: "S001", level: "warning", message: "SELECT * はテーブル構造変更の影響を受けやすく、不要な列の転送で I/O を増大させる。必要な列のみを明示する。", file: "cobol/SYK006.cbl", startLine: 145, startColumn: 1 },
  { ruleId: "S004", level: "warning", message: "更新を伴わないカーソルに FOR READ ONLY が指定されていない。ロック競合の原因になる。", file: "cobol/SYK006.cbl", startLine: 145, startColumn: 1 },
  { ruleId: "S002", level: "error", message: "WHERE 句の述語がインデックスを活用できない非 SARGable な形になっている。", file: "cobol/SYK007.cbl", startLine: 84, startColumn: 1 },
  { ruleId: "S003", level: "error", message: "インデックス列 商品コード へ CAST を適用しており、インデックスが使われない。", file: "cobol/SYK007.cbl", startLine: 84, startColumn: 1 },
  { ruleId: "S005", level: "note", message: "取得行数が限られる用途では FETCH FIRST n ROWS ONLY によるフェッチ件数制限を検討する。", file: "cobol/SYK006.cbl", startLine: 96, startColumn: 1 },
  { ruleId: "S006", level: "note", message: "OPTIMIZE FOR n ROWS の付与により少件数取得に適したアクセスパスが選ばれやすくなる。", file: "cobol/SYK006.cbl", startLine: 145, startColumn: 1 },
];
