/**
 * 逐語対訳ペインで表示する生成物(Python・Java)の構文着色。monaco が同梱する Monarch 文法を
 * ローカルから静的に取り込んで登録し、CDN も動的読込も使わない。
 *
 * 取り込み経路は monaco-editor 0.56 の exports 写像("./*" → "./esm/vs/*.js")に従う。
 * 言語サービス(補完・診断)は使わないため、文法と言語構成の登録だけを行う。
 */

import type { languages as monacoLanguages } from "monaco-editor/editor/editor.api";
import { conf as javaConf, language as javaLanguage } from "monaco-editor/languages/definitions/java/java";
import { conf as pythonConf, language as pythonLanguage } from "monaco-editor/languages/definitions/python/python";
import type { TranspileLanguage } from "../../../shared/engine-api";

/** 文法と言語構成の登録に用いる monaco の最小の面。 */
export interface GeneratedLanguageTarget {
  languages: {
    register(language: { id: string }): void;
    setMonarchTokensProvider(languageId: string, definition: monacoLanguages.IMonarchLanguage): void;
    setLanguageConfiguration(
      languageId: string,
      configuration: monacoLanguages.LanguageConfiguration,
    ): void;
  };
}

/** 生成言語に対応する monaco の言語 ID。 */
export const GENERATED_LANGUAGE_ID: Record<TranspileLanguage, string> = {
  python: "python",
  java: "java",
};

let registered = false;

/** 生成物の言語(Python・Java)を monaco へ登録する。登録は1度だけ行う。 */
export function registerGeneratedLanguages(monaco: GeneratedLanguageTarget): void {
  if (registered) {
    return;
  }
  monaco.languages.register({ id: GENERATED_LANGUAGE_ID.python });
  monaco.languages.setMonarchTokensProvider(GENERATED_LANGUAGE_ID.python, pythonLanguage);
  monaco.languages.setLanguageConfiguration(GENERATED_LANGUAGE_ID.python, pythonConf);
  monaco.languages.register({ id: GENERATED_LANGUAGE_ID.java });
  monaco.languages.setMonarchTokensProvider(GENERATED_LANGUAGE_ID.java, javaLanguage);
  monaco.languages.setLanguageConfiguration(GENERATED_LANGUAGE_ID.java, javaConf);
  registered = true;
}
