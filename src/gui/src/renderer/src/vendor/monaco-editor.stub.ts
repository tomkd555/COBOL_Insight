/**
 * Vitest 専用の monaco-editor スタブ。vitest.config.ts の alias が monaco-editor の全サブパスを
 * この1本へ差し替える。
 *
 * 差し替える理由は2つある。第1に Monaco は Web Worker と実 DOM の寸法計測を要し jsdom では動かない
 * ため、単体テストは vendor/monacoEditor をモックして動かす。第2に本体は約 10MB の ESM で、
 * Worker を base64 で埋め込む取り込み(?worker&inline)はテスト実行時に別のバンドルを組むため、
 * 実体を読み込むと画面を描くだけのテストまで極端に遅くなる。
 *
 * したがってスタブは「読み込めるが使えない」形にし、実際に呼ばれた場合は理由の判る例外を投げる。
 * 型はテストではなく tsc が本物の型定義から解決するため、ここでは実行時の形だけをそろえる。
 */

function unavailable(name: string): never {
  throw new Error(`Monaco の ${name} は Vitest では使えない。vendor/monacoEditor をモックする。`);
}

/** monaco-editor/editor/editor.worker?worker&inline の既定輸出(Worker の構築子)の代わり。 */
export default class StubWorker {
  constructor() {
    unavailable("Worker");
  }
}

/** monaco.editor 名前空間の代わり。 */
export const editor = {
  create: () => unavailable("editor.create"),
  createDiffEditor: () => unavailable("editor.createDiffEditor"),
  defineTheme: () => undefined,
  setTheme: () => undefined,
  setModelLanguage: () => undefined,
};

/** monaco.languages 名前空間の代わり。 */
export const languages = {
  register: () => undefined,
  setMonarchTokensProvider: () => undefined,
  setLanguageConfiguration: () => undefined,
};

/** 生成物の言語定義(languages/definitions 配下の python.js・java.js)の輸出の代わり。 */
export const conf = {};
export const language = { tokenizer: { root: [] } };
