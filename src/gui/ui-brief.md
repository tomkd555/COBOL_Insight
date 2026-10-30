# UI brief — COBOL Insight

Written by product-ui Step 5. This is the plan the design reviewers conform the screens to; `shippable-text-auditor` does not receive it.

## Screen

- Purpose, one sentence: a VS Code-shaped desktop workbench in which a mainframe engineer opens a folder of COBOL, copybook, JCL and BMS assets, runs the static analysis, reads the findings beside the source, and applies or exports the fixes.
- Surface: saas (application)
- Tokens: C:\Mycode\ClaudeCode\COBOL_Insight\src\gui\tokens.json
- Theme: C:\Mycode\ClaudeCode\COBOL_Insight\src\gui\theme.css
- Built files: C:\Mycode\ClaudeCode\COBOL_Insight\src\gui\src\renderer\src (TSX; strings in i18n\text.ts); screenshots of every screen in both themes under C:\Mycode\ClaudeCode\COBOL_Insight\src\gui\out\screenshots\{light,dark} (1440 px wide) and C:\Mycode\ClaudeCode\COBOL_Insight\src\gui\out\screenshots-1120\{light,dark} (the minimum width)

## Screens in the screenshots

| File | Screen |
|---|---|
| checkShell | Empty shell: title bar, activity bar (エクスプローラー, ルール; bottom group 呼び出し関係図, レポート, 変換, 設定), welcome screen, panel |
| checkAssetTree | Explorer tree after a folder open |
| checkEditorLayout, checkEbcdicColumns, checkCopyExpansion, checkDirtyMark, checkEditSurvivesSwitch | Source editor: a code column exactly 80 桁 wide under a 桁 scale, boundary lines at 桁 6, 7, 11, 72 and 80, COPY expansion zones, dirty dot on the tab |
| checkSourcePane | Source editor with its right-hand pane (shown when 300 px or more remain beside the code column; absent in the 1120 px set): tabs 段落一覧 / トレース, the text of the paragraph whose name was pressed with the button 本文に移動, and the graph of the trace under a legend that opens on request (凡例, the same shape as the call graph's). The two boundaries between the three regions can be dragged or moved with the arrow keys |
| checkSaveAllChord | Source editor after Ctrl+K S |
| checkFindings, checkFindings-detail | 指摘 panel: table, filter, detail pane |
| checkQuickFix | Monaco lightbulb → fix tab |
| checkCallGraph | 呼び出し関係図 editor: canvas, execution-order tree, detail pane |
| checkGraphSelection | 呼び出し関係図 with a node chosen in the トレース tree: detail pane with 呼び出し元 and 呼び出し先 |
| checkRules | Source editor after a rule was switched: the strip 一覧は変更前のルールによる結果です。 above the 指摘 table |
| checkRules-view, checkRuleAndSettingsEditors, checkRuleAndSettingsEditors-customRules | ルール side view, 利用者定義ルール editor, 設定 tab |
| checkRuleDetail | Rule description tab (R004) |
| checkReport | レポート tab with the engine's HTML report |
| checkOutput | 出力 panel: the run log |
| checkTranspile, checkTranspile-panes | 変換 tab (COBOL beside Python/Java) |
| checkImportDialog, checkImportDialog-open | 端末からの貼り付け dialog holding numbered source with no title row: 画面の種類 on 自動 reads a screen by its title row (an SEU display also by the shape of its rows), so this text is cut to the column range, with one line saying so and pointing at 画面の種類 for a screen copied without its title |
| checkImportScreen, checkImportScreen-open | 端末からの貼り付け dialog holding two overlapping ISPF edit screens: 画面の種類 on 自動, the cut-out text, and one line stating the screen read and the rows removed |
| checkSaveConflict, checkSaveConflict-dialog | 原本が書き換わっています dialog |
| checkCommandPalette, checkCommandPalette-open | Command palette with chords |
| checkZoomReflow | Shell at 200 % zoom |

## What the reviewer judges

One filled (primary) button stands on a screen: the title bar's 解析, except where the editor in front carries its own, where 解析 is drawn outlined. An editor carries its own in three states: the welcome view before the first analysis, the report tab once a whole-folder analysis exists to report on (HTMLで作成), and the custom-rule editor while it holds an edit that can be saved (保存). Every other button, the fix tab's write-out and the finding detail's 修正案を開く included, is outlined. A dialog is a surface of its own and has its own filled button. The activity bar marks the side-bar view in front with the primary rule and the editor in front (呼び出し関係図, レポート, 変換, 設定) with a rule in the edge colour. In the 指摘 panel the detail pane appears once a row is chosen, with its actions under its head (修正案を開く where the rule has a fix, ルールの説明を開く, 呼び出し関係図を開く). The status bar's codepage select is disabled while the tab in front holds unsaved edits. In the trace, a flow into the paragraph below is labelled 次の段落. Under 900 px the fix tab's diff is one column, and its heading row reads as the key to the marks (− 原本, + 修正案). In the dark theme the report preview is drawn dark; the file written out stays as the engine made it. In an editor narrower than 860 px the call graph's detail pane sits under the tree and the drawing. A boundary between two regions (side bar, panel, the regions of the source pane) is a 1 px line with a 9 px hit area and arrow-key operation; the 24 px minimum is the rule for buttons and inputs. In the 端末からの貼り付け dialog the two column inputs are present only while the text is cut by a column range. A dialog taller than the window scrolls between its title and its actions, which keep their place.

Everything the four scripts cannot: contrast as rendered, whether anything guides the eye, whether interactive elements do what they look like they do, reflow at 200 % zoom. The window is a desktop application with a 1120 px minimum width; there is no 375 px layout.

## Copy questions, answered against the screenshots

1. Does each button label name what the button actually does? 「保存」 on a control that publishes passes every script.
2. Does each error state a cause the reader can act on?
3. Is each heading specific to this screen? 「概要」「詳細」「設定」「情報」 pass every rule and carry nothing.
4. Is the copy true — 「この操作は取り消せません」 on something that can be undone, a dialog understating what it deletes?
5. Is one object called by one name throughout, including names not yet in `voice.terms`?
6. Is the empty state's action the right next action for a reader who cannot perform it yet?
7. Does 敬体 suit this audience at all?
