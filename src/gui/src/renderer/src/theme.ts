/**
 * The colour theme: what the user chose, what that resolves to, and how it reaches the document.
 *
 * The stylesheet carries both palettes and switches on the `dark` class of the root element
 * (theme.css, generated from tokens.json). The class is set here and nowhere else.
 *
 * The settings file is the record of the choice. A copy is kept in localStorage so the class can be
 * applied synchronously before the first paint, which the settings file — read over IPC after the
 * shell has mounted — cannot do; the two are reconciled once the settings arrive.
 */

/** What the settings screen offers. "system" follows the operating system. */
export type ThemeChoice = "system" | "dark" | "light";

/** What is actually drawn. */
export type ThemeName = "dark" | "light";

export const THEME_CHOICES: readonly ThemeChoice[] = ["system", "dark", "light"];

const STORAGE_KEY = "ci.theme";
const DARK_CLASS = "dark";

export function isThemeChoice(value: string): value is ThemeChoice {
  return (THEME_CHOICES as readonly string[]).includes(value);
}

/** The theme a choice resolves to under the given system preference. */
export function resolveTheme(choice: ThemeChoice, systemPrefersDark: boolean): ThemeName {
  if (choice === "system") {
    return systemPrefersDark ? "dark" : "light";
  }
  return choice;
}

/** The system preference query, or null where the environment has none (jsdom). */
export function darkSchemeQuery(): MediaQueryList | null {
  return typeof window.matchMedia === "function"
    ? window.matchMedia("(prefers-color-scheme: dark)")
    : null;
}

/** Whether the operating system asks for a dark theme right now. */
export function systemPrefersDark(): boolean {
  return darkSchemeQuery()?.matches ?? false;
}

/** Sets the root element's class so the stylesheet draws the given theme. */
export function applyTheme(theme: ThemeName): void {
  document.documentElement.classList.toggle(DARK_CLASS, theme === "dark");
}

/** The choice cached in this browser profile, or "system" when nothing usable is stored. */
export function readCachedChoice(): ThemeChoice {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    return stored !== null && isThemeChoice(stored) ? stored : "system";
  } catch {
    return "system";
  }
}

export function cacheChoice(choice: ThemeChoice): void {
  try {
    localStorage.setItem(STORAGE_KEY, choice);
  } catch {
    // A profile that refuses storage only loses the pre-paint shortcut; the settings file still wins.
  }
}

/** Applies the cached choice before React mounts, so the first frame is already the right theme. */
export function applyStartupTheme(): void {
  applyTheme(resolveTheme(readCachedChoice(), systemPrefersDark()));
}
