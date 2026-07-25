/**
 * 検出ルールのカタログ。バグ検出 31 件(R001〜R031)と SQL 最適化助言 6 件(S001〜S006)の
 * 静的メタ情報(名称・カテゴリ・重大度・修正案の有無)を単一の正として持つ。
 *
 * SARIF の level(error/warning/note)は 3 段だが、画面表示の重大度は 4 段(高/中/低/警告)で
 * ルール固有の属性である。したがって重大度は SARIF の level ではなく、このカタログを引いて決める。
 * 修正案 diff の有無(hasFix)は engine の FixProducer 実装と一致させ、R004(OnSizeErrorMissingRule)・
 * R017(FileStatusUncheckedRule)・R018(SqlCodeUncheckedRule)の 3 件だけを true とする。
 * R021(CicsResponseUncheckedRule)は FixProducer を実装しないため修正案を生成できない。
 */

import { SEVERITY_BY_LABEL, type Severity } from "../components/severity";

export interface RuleInfo {
  /** ルール ID(R001〜R031 / S001〜S006)。 */
  readonly id: string;
  /** ルール名称(画面の一覧・フィルタで表示)。 */
  readonly name: string;
  /** カテゴリ(設定画面のグループ化に用いる)。 */
  readonly category: string;
  /** 画面表示の重大度(高/中/低/警告)。 */
  readonly severity: Severity;
  /** 修正案 diff を生成できるルールか(R004/R017/R018 のみ true)。 */
  readonly hasFix: boolean;
}

/** [id, 名称, カテゴリ, 重大度ラベル, 修正案あり?] の表。 */
const RULE_TABLE: readonly [string, string, string, string, boolean][] = [
  ["R001", "未初期化変数の参照", "データフロー", "高", false],
  ["R002", "未使用データ項目", "データフロー", "低", false],
  ["R003", "MOVEによる桁落ち・切り捨て", "データ移動", "高", false],
  ["R004", "ON SIZE ERROR句の欠如", "例外処理", "高", true],
  ["R005", "添字・指標のOCCURS範囲外アクセス", "添字・指標", "高", false],
  ["R006", "添字への二進項目未使用", "添字・指標", "低", false],
  ["R007", "PERFORM THRUの範囲不整合", "制御フロー", "高", false],
  ["R008", "PERFORM単独段落名の直接指定", "制御フロー", "中", false],
  ["R009", "GO TO文による構造化フローからの逸脱", "制御フロー", "警告", false],
  ["R010", "ALTER文による遷移先の動的変更", "制御フロー", "高", false],
  ["R011", "到達不能コード", "制御フロー", "中", false],
  ["R012", "終了条件が更新されないPERFORM UNTILループ", "制御フロー", "高", false],
  ["R013", "EVALUATE文のWHEN OTHER欠如", "制御フロー", "中", false],
  ["R014", "セクション末尾のEXIT文欠如によるフォールスルー", "制御フロー", "中", false],
  ["R015", "REDEFINESによる項目長・境界の不一致", "データ定義", "高", false],
  ["R016", "STRING/UNSTRING文の受信領域あふれ", "データ移動", "高", false],
  ["R017", "ファイル状態(FILE STATUS)未検査", "例外処理", "高", true],
  ["R018", "SQLCODE/SQLSTATE未検査", "例外処理", "高", true],
  ["R019", "SQLカーソルのCLOSE漏れ", "SQL", "中", false],
  ["R020", "動的SQL文への外部入力の未検証組み込み", "SQL", "高", false],
  ["R021", "CICS応答コード(RESP/RESP2)未検査", "例外処理", "高", false],
  ["R022", "CICS RETURN文欠如による疑似会話の途絶", "制御フロー", "中", false],
  ["R023", "パラグラフ・セクション名の重複", "制御フロー", "中", false],
  ["R024", "COPY REPLACINGによる置換漏れ", "データ定義", "中", false],
  ["R025", "二項演算子の両辺が同一の式", "データフロー", "警告", false],
  ["R026", "ハードコードされたパスワード・認証情報", "セキュリティ", "高", false],
  ["R027", "機密データ項目のマスキングなし出力", "セキュリティ", "中", false],
  ["R028", "符号なし前提の数値項目への負値算出", "データ移動", "中", false],
  ["R029", "呼び出し先プログラムの戻りコード(RETURN-CODE)未検査", "制御フロー", "中", false],
  ["R030", "JCLステップ間の条件コード(COND)未検査", "JCL制御", "中", false],
  ["R031", "存在しないBMSマップ・フィールドの参照", "CICS", "高", false],
  ["S001", "SELECT * の回避", "可読性・保守性", "中", false],
  ["S002", "非SARGableな述語の検出", "性能", "高", false],
  ["S003", "インデックス列への関数適用の検出", "性能", "高", false],
  ["S004", "カーソルの適切な宣言・後始末の確認", "性能・信頼性", "中", false],
  ["S005", "FETCH FIRST句によるフェッチ件数制限の検討", "性能", "低", false],
  ["S006", "OPTIMIZE FOR句によるアクセスパス最適化の検討", "性能", "低", false],
];

export const RULE_CATALOG: Readonly<Record<string, RuleInfo>> = Object.fromEntries(
  RULE_TABLE.map(([id, name, category, sevLabel, hasFix]) => [
    id,
    { id, name, category, severity: SEVERITY_BY_LABEL[sevLabel], hasFix },
  ]),
);

/**
 * engine が検出ルール以外に出す解析エラーの ID と名称。lint・scan は構文解析の失敗
 * (Finding.PARSE_FAILURE_RULE_ID)と復号の失敗(LintRunner・ScanRunner の decode-failure)を
 * 別の ID で記録するため、名称も区別する。
 */
const ANALYSIS_ERROR_NAMES: Readonly<Record<string, string>> = {
  "parse-failure": "構文解析失敗",
  "decode-failure": "文字コードの復号失敗",
};

/**
 * カタログにない ID へのフォールバック。design:1065 の既定に倣い重大度は高とし、名称は
 * 解析エラーの ID だけをその内容で名付ける。engine が出さない ID を「構文解析失敗」と
 * 名乗らせない。
 */
function fallbackRule(id: string): RuleInfo {
  return {
    id,
    name: ANALYSIS_ERROR_NAMES[id] ?? "未登録のルール",
    category: "解析エラー",
    severity: "high",
    hasFix: false,
  };
}

/** ルール ID からメタ情報を引く。未知の ID はフォールバックを返す(design ruleOf)。 */
export function ruleOf(id: string): RuleInfo {
  return RULE_CATALOG[id] ?? fallbackRule(id);
}
