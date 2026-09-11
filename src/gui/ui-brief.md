# UI brief — COBOL Insight

Written by product-ui Step 5. This is the plan the design reviewers conform the screens to; `shippable-text-auditor` does not receive it.

## Screen

- Purpose, one sentence: a VS Code-shaped desktop workbench in which a mainframe engineer opens a folder of COBOL, copybook, JCL and BMS assets, runs the static analysis, reads the findings beside the source, and applies or exports the fixes.
- Surface: saas (application)
- Tokens: C:\Mycode\ClaudeCode\COBOL_Insight\src\gui\tokens.json
- Theme: C:\Mycode\ClaudeCode\COBOL_Insight\src\gui\theme.css
- Built files: C:\Mycode\ClaudeCode\COBOL_Insight\src\gui\src\renderer\src (TSX; strings in i18n\text.ts); screenshots of every screen in both themes under C:\Mycode\ClaudeCode\COBOL_Insight\src\gui\out\screenshots\{light,dark}

## Screens in the screenshots

| File | Screen |
|---|---|
| checkShell | Empty shell: title bar, activity bar (エクスプローラー, ルール; bottom group 呼び出し関係図, レポート, 変換, 設定), welcome screen, panel |
| checkAssetTree | Explorer tree after a folder open |
| checkEditorLayout, checkEbcdicColumns, checkCopyExpansion, checkDirtyMark, checkEditSurvivesSwitch | Source editor: fixed-format columns, COPY expansion zones, dirty dot on the tab |
| checkFindings, checkFindings-detail | 指摘 panel: table, filter, detail pane |
| checkQuickFix | Monaco lightbulb → fix tab |
| checkCallGraph | 呼び出し関係図 editor: canvas, execution-order tree, detail pane |
| checkRules, checkRules-view, checkRuleAndSettingsEditors, checkRuleAndSettingsEditors-customRules | ルール side view, rule detail tab, 利用者定義ルール editor, 設定 tab |
| checkTranspile, checkTranspile-panes | 変換 tab (COBOL beside Python/Java) |
| checkImportDialog, checkImportDialog-open | 端末からの貼り付け dialog |
| checkSaveConflict, checkSaveConflict-dialog | 原本が書き換わっています dialog |
| checkCommandPalette, checkCommandPalette-open | Command palette with chords |
| checkZoomReflow | Shell at 200 % zoom |

## What the reviewer judges

Everything the four scripts cannot: contrast as rendered, whether anything guides the eye, whether interactive elements do what they look like they do, reflow at 200 % zoom. The window is a desktop application with a 1024 px minimum width; there is no 375 px layout.

## Copy questions, answered against the screenshots

1. Does each button label name what the button actually does? 「保存」 on a control that publishes passes every script.
2. Does each error state a cause the reader can act on, rather than a plausible one?
3. Is each heading specific to this screen? 「概要」「詳細」「設定」「情報」 pass every rule and carry nothing.
4. Is the copy true — 「この操作は取り消せません」 on something that can be undone, a dialog understating what it deletes?
5. Is one object called by one name throughout, including names not yet in `voice.terms`?
6. Is the empty state's action the right next action for a reader who cannot perform it yet?
7. Does 敬体 suit this audience at all?
