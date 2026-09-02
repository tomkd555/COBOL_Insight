# UI brief — COBOL Insight

Written by product-ui Step 5. This is the plan the design reviewers conform the screens to; `shippable-text-auditor` does not receive it.

## Screen

- Purpose, one sentence: a desktop workbench in which an engineer opens a folder of Japanese mainframe assets (COBOL, copybooks, JCL, BMS, embedded SQL), reads the findings the engine raised, edits and saves the sources in their original encoding, and follows the call graph between them.
- Surface: saas (application)
- Tokens: `src/gui/tokens.json`
- Theme: `src/gui/theme.css` (generated) plus `src/gui/src/renderer/src/styles/app-tokens.css`
- Built files: `src/gui/src/renderer/src/styles/*.css`, `src/gui/src/renderer/src/**/*.tsx`, strings in `src/gui/src/renderer/src/i18n/text.ts`
- Rendered evidence: `src/gui/out/screenshots/<dark|light>/<check>.png`, one capture after each smoke check, both themes, 1440×900; `checkZoomReflow.png` is the 200 % zoom.

## What the reviewer judges

Everything the four scripts cannot: contrast as rendered, whether anything guides the eye, whether interactive elements do what they look like they do, whether the two themes read as one product, and reflow at 200 % zoom (a 720 px viewport).

## Copy questions, answered against the running page

1. Does each button label name what the button actually does? 「保存」 on a control that publishes passes every script.
2. Does each error state a cause the reader can act on, rather than a plausible one?
3. Is each heading specific to this screen? 「概要」「詳細」「設定」「情報」 pass every rule and carry nothing.
4. Is the copy true — 「この操作は取り消せません」 on something that can be undone, a dialog understating what it deletes?
5. Is one object called by one name throughout, including names not yet in `voice.terms`?
6. Is the empty state's action the right next action for a reader who cannot perform it yet?
7. Does 敬体 suit this audience at all?
