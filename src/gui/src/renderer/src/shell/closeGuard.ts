/**
 * 未保存のタブを閉じる前の確認。閉じる操作はタブ帯とキー操作の2箇所にあり、どちらからでも
 * 同じ問いを出すために判定をここへ1つ置く。
 */

/** 確認の問い。閉じると編集が失われることを述べ、はいで破棄する。 */
export const DISCARD_PROMPT = "保存していない変更があります。破棄して閉じますか。";

/** 閉じてよいか。編集を抱えていないタブは問わずに閉じる。 */
export function confirmClose(dirty: boolean): boolean {
  return !dirty || window.confirm(DISCARD_PROMPT);
}
