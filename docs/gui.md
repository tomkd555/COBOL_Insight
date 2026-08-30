# GUI design tokens and themes

The renderer's look is settled in one file, `src/gui/tokens.json`, and everything else reads from it.

## The files

| File | Role | Edited by |
|---|---|---|
| `src/gui/tokens.json` | Colours (light in `color`, dark in `color_dark`), type, spacing, radius, shadow, motion, and the wording rules under `voice` | hand |
| `src/gui/theme.css` | Generated from `tokens.json`: the `--brand-*` palette under `:root` and `.dark`, and the `@theme inline` block that binds `--color-*`, `--font-*`, `--radius-*`, `--shadow-*`, `--ease-*` to it | the generator only |
| `src/gui/src/renderer/src/styles/app-tokens.css` | What the shell needs beyond the palette: surfaces mixed from it (`--ci-bg-hover`, `--ci-bg-selected`, `--ci-bg-overlay`, `--ci-border-strong`, `--ci-danger-bg`), the code font, the type sizes, the spacing steps and the chrome sizes. No colour literal | hand |
| `src/gui/src/renderer/src/styles/{base,chrome,lists,editor,overlays,forms,graph,report}.css` | The component rules, `.ci-*` BEM classes, reading `var(--color-*)` and the app tokens only | hand |
| `src/gui/src/renderer/src/vendor/monarch.ts`, `src/gui/src/renderer/src/model/graphLayout.ts` | The Monaco and Cytoscape palettes, one hex pair (dark, light) per token. Neither library reads CSS variables, so these repeat the `tokens.json` values by hand | hand, whenever `tokens.json` changes |

Tailwind v4 (`tailwindcss`, `@tailwindcss/vite`, renderer build only) exists to turn the `@theme` block into
CSS custom properties. No utility class is written in JSX. Deleting the `@import "tailwindcss"` line
leaves `theme.css` a plain custom-property file, which is the escape hatch if the dependency ever fails.

## Changing a token

```powershell
python $env:USERPROFILE\.claude\skills\product-ui\scripts\validate_tokens.py src\gui\tokens.json --json
python $env:USERPROFILE\.claude\skills\product-ui\scripts\generate_theme.py src\gui\tokens.json
cd src\gui
python $env:USERPROFILE\.claude\skills\product-ui\scripts\check_slop.py src\renderer\src --theme theme.css
python $env:USERPROFILE\.claude\skills\product-ui\scripts\check_copy.py src\renderer\src --tokens tokens.json --emit-prose copy-prose.md
python $env:USERPROFILE\.claude\skills\shippable-text\scripts\check_shippable_text.py src\renderer\src --surface app
npm run check
npm run smoke:shots     # builds, runs the smoke once per theme, writes out/screenshots/<theme>/*.png
```

`check_slop.py` fails on any colour literal outside `theme.css`; `check_copy.py` reads the string catalog
`src/renderer/src/i18n/text.ts` (the `i18n` path is what makes it a catalog) against `voice` in
`tokens.json`; the screenshots are what a design review looks at, since the renderer needs the preload
bridge and cannot be opened in a browser.

## Themes

`color` is the light palette and `color_dark` the dark one; the `.dark` class on `<html>` selects it.

- The choice lives in the settings file (`theme`: `system`, `dark` or `light`; empty means `system`).
- `main.tsx` applies the class before React mounts, from a copy cached in `localStorage` (`ci.theme`)
  and `prefers-color-scheme`, so the first frame is already the right theme. `state/useTheme.ts` keeps
  the class, the cache and Monaco's `setTheme` in step with the settings and with the OS afterwards.
- The window's own background (`src/main/window.ts`) follows `nativeTheme.shouldUseDarkColors`, so
  the frame is not the wrong colour while the renderer loads.
- Cytoscape restyles in place on a theme change (`GraphCanvas.tsx`); the layout does not rerun.
