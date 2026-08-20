/**
 * 文字コードの語彙。engine の charset 名(CodePage.charsetName)と、画面が示す表記・手動指定の
 * 選択肢を対応づける。
 */

/** 手動選択肢 → scan へ渡す charset コード。自動判定・推定は上書きしないため持たない。 */
const MANUAL_CHARSET: Readonly<Record<string, string>> = {
  "手動: Shift_JIS": "Shift_JIS",
  "手動: UTF-8": "UTF-8",
  "手動: EBCDIC CP930": "CP930",
  "手動: EBCDIC CP939": "CP939",
};

/**
 * 手動指定の選択肢。既定の文字コードもこの語彙から選ぶ。自動判定・推定は engine の判定結果を
 * 表す語であり、人が選ぶ既定値にはならない。
 */
export const MANUAL_ENCODING_OPTIONS: readonly string[] = Object.keys(MANUAL_CHARSET);

/**
 * engine の charset 名から利用者向けの表記を引く対応表。UTF-8 は engine の値がそのまま利用者向けの
 * 表記であるため、この表には持たない。
 */
const CODEPAGE_LABELS: Readonly<Record<string, string>> = {
  "WINDOWS-31J": "Shift_JIS",
  "X-IBM930": "EBCDIC CP930",
  "X-IBM939": "EBCDIC CP939",
};

/** engine の charset 名の別名を大文字へそろえる(SOURCE.codepage は charsetName だが別名も受ける)。 */
function normalizeCodepage(codepage: string): string {
  const upper = codepage.trim().toUpperCase().replace(/[_\s]/g, "-");
  if (upper === "SHIFT-JIS" || upper === "SJIS" || upper === "MS932" || upper === "CP932") {
    return "WINDOWS-31J";
  }
  if (upper === "IBM930" || upper === "CP930") return "X-IBM930";
  if (upper === "IBM939" || upper === "CP939") return "X-IBM939";
  return upper;
}

/** engine の charset 名を利用者向けの表記へ言い換える。対応表に無い値はそのまま返す。 */
export function codepageLabel(codepage: string | null): string {
  if (codepage === null) {
    return "未判定";
  }
  return CODEPAGE_LABELS[normalizeCodepage(codepage)] ?? codepage;
}

/** 手動指定の選択肢から charset を引く。手動指定でない語は null。 */
export function charsetOf(selection: string): string | null {
  return MANUAL_CHARSET[selection] ?? null;
}
