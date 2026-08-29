/**
 * Registering the mainframe languages and the editor theme with Monaco. Registration happens once
 * per renderer: Monaco treats a second `register` of the same id as a duplicate language.
 *
 * Only the grammars and the theme are registered. No language service (completion, diagnostics) is
 * involved, which is why the editor needs Monaco's one base worker and none of the per-language
 * ones.
 *
 * Python and Java come from Monaco's own definitions, which the transpile pane shows the generated
 * code in. Each `register` module registers the language with a lazy loader, so the grammar itself is
 * only fetched once a model is opened in it; the package's default entry point, which would register
 * every language Monaco ships, is still avoided.
 */

// The generated code of the transpile pane. Side-effect imports: each registers one language.
import "monaco-editor/languages/definitions/python/register";
import "monaco-editor/languages/definitions/java/register";
import {
  COBOL_INSIGHT_THEME,
  LANGUAGE_ID,
  bmsLanguage,
  cobolInsightTheme,
  cobolLanguage,
  jclLanguage,
  jsonLanguage,
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

/** Registers the COBOL, JCL, BMS and JSON grammars and the theme. Repeated calls do nothing. */
export function registerLanguages(monaco: LanguageRegistrationTarget): void {
  if (registered) {
    return;
  }
  const definitions: readonly [string, MonarchLanguage][] = [
    [LANGUAGE_ID.cobol, cobolLanguage()],
    [LANGUAGE_ID.jcl, jclLanguage()],
    [LANGUAGE_ID.bms, bmsLanguage()],
    [LANGUAGE_ID.json, jsonLanguage()],
  ];
  for (const [id, definition] of definitions) {
    monaco.languages.register({ id });
    monaco.languages.setMonarchTokensProvider(id, definition);
  }
  monaco.editor.defineTheme(COBOL_INSIGHT_THEME, cobolInsightTheme());
  registered = true;
}
