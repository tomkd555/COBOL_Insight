/**
 * The resolved theme, kept in step with the settings and the operating system.
 *
 * One effect applies the class to the document, caches the choice for the next start-up and hands
 * Monaco its matching theme; Monaco's `setTheme` is global, so every editor on screen follows.
 */

import { useEffect, useState } from "react";
import {
  applyTheme,
  cacheChoice,
  darkSchemeQuery,
  resolveTheme,
  systemPrefersDark,
  type ThemeName,
} from "../theme";
import { monacoEditor } from "../vendor/monacoEditor";
import { registerLanguages } from "../vendor/monacoLanguages";
import { COBOL_INSIGHT_THEME } from "../vendor/monarch";
import { useSettings } from "./settingsStore";

export function useTheme(): ThemeName {
  const { theme: choice, restored } = useSettings();
  const [prefersDark, setPrefersDark] = useState(systemPrefersDark);

  useEffect(() => {
    const query = darkSchemeQuery();
    if (query === null) {
      return;
    }
    const onChange = (event: MediaQueryListEvent): void => setPrefersDark(event.matches);
    query.addEventListener("change", onChange);
    return () => query.removeEventListener("change", onChange);
  }, []);

  const theme = resolveTheme(choice, prefersDark);

  useEffect(() => {
    applyTheme(theme);
    const monaco = monacoEditor();
    registerLanguages(monaco);
    monaco.editor.setTheme(COBOL_INSIGHT_THEME[theme]);
  }, [theme]);

  // The cache mirrors the stored choice; before the settings are read it would only overwrite the
  // value the start-up just used.
  useEffect(() => {
    if (restored) {
      cacheChoice(choice);
    }
  }, [choice, restored]);

  return theme;
}
