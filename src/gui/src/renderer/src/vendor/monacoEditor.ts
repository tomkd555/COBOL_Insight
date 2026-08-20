/**
 * ソースビューア・diff で使う Monaco エディタ。vs のソースをローカルへバンドルし、CDN は参照しない。
 *
 * Worker は Vite の `?worker&inline` で取り込み、生成コードを Blob(URL.createObjectURL)から起こす。
 * renderer は本番で file:// から読み込まれ、file:// のスクリプト URL からは Worker を起こせないため、
 * 別ファイルのままでは起動できない。この方式に伴い index.html の CSP は worker-src へ
 * 'self' blob: を許す(外部オリジンは許さない)。
 *
 * 言語サービス用の専用 Worker(json/css/html/typescript)は使わない。COBOL・Python・Java の
 * 構文着色は Monarch(構文のみ)で行い、基本 Worker 1つで足りる。
 *
 * 取り込み経路は monaco-editor 0.56 の exports 写像("./*" → "./esm/vs/*.js")に従う。
 * すなわち editor.api は "monaco-editor/editor/editor.api"、Worker は
 * "monaco-editor/editor/editor.worker" である。パッケージ既定の入口("monaco-editor")は
 * 全言語の登録を伴うため使わない。
 */

import * as monaco from "monaco-editor/editor/editor.api";
import EditorWorker from "monaco-editor/editor/editor.worker?worker&inline";
// アイコン字形(codicon)の @font-face と codicon.ttf を同梱する。editor.api の取り込みには
// 含まれず、これを入れないと検索欄などの記号が字形として出ない。フォントは相対パスで
// 参照されるため file:// からも読める。
import "monaco-editor/features/codicon/register";

/**
 * Monaco が Worker 生成時に参照する global。ciMonaco は実描画 smoke(smoke/render.cjs)が
 * 本文へ打鍵するための口である。面の実体は Monaco が持ち、DOM からは辿れない。
 */
interface MonacoWorkerHost {
  MonacoEnvironment: monaco.Environment;
  ciMonaco: typeof monaco;
}

let configured = false;

/** Worker 生成を配線した Monaco の API を返す。配線は1度だけ行う。 */
export function monacoEditor(): typeof monaco {
  if (!configured) {
    const host = self as unknown as MonacoWorkerHost;
    host.MonacoEnvironment = { getWorker: () => new EditorWorker() };
    host.ciMonaco = monaco;
    configured = true;
  }
  return monaco;
}
