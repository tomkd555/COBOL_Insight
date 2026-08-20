/**
 * 資産の本文を main の readSourceText から読む取り回し。純ロジック(本文の解釈・桁の判定・指摘の
 * 行への割り当て)は viewerModel が持ち、ここは非同期の取り回しだけを担う。
 *
 * ソースビューア・指摘一覧・SQL指摘の3画面が同じ形で本文を必要とするため、読み取りの手順を1本に
 * まとめる。
 */

import { useEffect, useState } from "react";
import { toDocument, type DocumentState } from "./viewerModel";

/** 読み取っていない状態。参照を固定して、状態の同一性で余分な再描画を起こさない。 */
const IDLE: DocumentState = { status: "idle" };

/** 例外・非 Error 値から表示用の文言を取り出す。 */
function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/**
 * 資産1件の本文を読む。読み直しの合図は資産のパスと文字コードだけであり、行番号は含めない。
 * 同じ資産の中で行を送るあいだは読み直しが起きないため、連打を抑えるための遅延(時間のしきい値)を
 * 持たせる必要がない。別の資産の指摘へ続けて移ったときは読み込みが連続するが、効果の後始末で
 * 古い応答を捨てるため、最後に要求した資産の本文だけが画面へ載る。
 *
 * @param enabled 読んでよいか。解析前・資産フォルダ未確定のときは偽にして読まない。
 * @param inputDir 資産フォルダ。null は未確定。
 * @param path 資産の相対パス。空文字は未選択。
 * @param codepage 復号に使う文字コード。null は main の判定に委ねる。
 * @param reloadKey 読み直しの合図。値を変えるたびに同じ資産をもう一度読む(書き戻しの後に、
 *   engine が整えた原本の姿を画面へ戻すために使う)。
 */
export function useSourceDocument(
  enabled: boolean,
  inputDir: string | null,
  path: string,
  codepage: string | null,
  reloadKey: number = 0,
): DocumentState {
  const [document, setDocument] = useState<DocumentState>(IDLE);

  useEffect(() => {
    if (!enabled || inputDir === null || path === "") {
      setDocument(IDLE);
      return;
    }
    let current = true;
    setDocument({ status: "loading" });
    window.cobolInsight
      .readSourceText({ inputDir, path, codepage })
      .then((result) => {
        if (current) setDocument(toDocument(result));
      })
      .catch((error: unknown) => {
        if (current) setDocument({ status: "error", message: messageOf(error) });
      });
    return () => {
      current = false;
    };
  }, [enabled, inputDir, path, codepage, reloadKey]);

  return document;
}
