/**
 * Registering the mainframe languages and the editor theme with Monaco. Registration happens once
 * per renderer: Monaco treats a second `register` of the same id as a duplicate language.
 *
 * Only the grammars and the theme are registered. No language service (completion, diagnostics) is
 * involved, which is why the editor needs Monaco's one base worker and none of the per-language
 * ones.
 *
 * TODO: Python and Java, for the translate side-by-side pane. Monaco ships both grammars
 * (monaco-editor/languages/definitions/{python,java}) but they carry no type declarations, so
 * registering them needs an ambient declaration; there is no consumer for them until that pane
 * exists.
 */

import {
  COBOL_INSIGHT_THEME,
  LANGUAGE_ID,
  bmsLanguage,
  cobolInsightTheme,
  cobolLanguage,
  jclLanguage,
  type MonacoThemeData,
  type MonarchLanguage,
} from "./monarch";

/** The smallest part of the Monaco API registration needs; the real API satisfies it. */
export interface LanguageRegistrationTarget {
  languages: {
    register(language: { id: string }): void;
    setMonarchTokensProvider(languageId: string, definition: MonarchLanguage): void;
  };
  editor: {
    defineTheme(themeName: string, theme: MonacoThemeData): void;
  };
}

let registered = false;

/** Registers the COBOL, JCL and BMS grammars and the theme. Repeated calls do nothing. */
export function registerLanguages(monaco: LanguageRegistrationTarget): void {
  if (registered) {
    return;
  }
  const definitions: readonly [string, MonarchLanguage][] = [
    [LANGUAGE_ID.cobol, cobolLanguage()],
    [LANGUAGE_ID.jcl, jclLanguage()],
    [LANGUAGE_ID.bms, bmsLanguage()],
  ];
  for (const [id, definition] of definitions) {
    monaco.languages.register({ id });
    monaco.languages.setMonarchTokensProvider(id, definition);
  }
  monaco.editor.defineTheme(COBOL_INSIGHT_THEME, cobolInsightTheme());
  registered = true;
}
