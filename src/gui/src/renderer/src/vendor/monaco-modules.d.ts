/**
 * monaco が同梱する Python・Java の Monarch 文法(esm/vs/languages/definitions 配下)は型定義を
 * 持たないため、モジュールとして宣言する。逐語対訳ペインの着色にはこの2つだけを使い、
 * 全言語をまとめて登録する basic-languages/monaco.contribution は使わない(その入口は言語ごとの
 * 動的 import で分割読込を行い、file:// から読み込む renderer では取得できない)。
 */
declare module "monaco-editor/languages/definitions/python/python" {
  import type { languages } from "monaco-editor/editor/editor.api";
  export const conf: languages.LanguageConfiguration;
  export const language: languages.IMonarchLanguage;
}

declare module "monaco-editor/languages/definitions/java/java" {
  import type { languages } from "monaco-editor/editor/editor.api";
  export const conf: languages.LanguageConfiguration;
  export const language: languages.IMonarchLanguage;
}
