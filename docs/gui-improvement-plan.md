# GUI copy and design improvement plan

Written 2026-09-02 from eight independent audits of the V2 renderer and the engine text it shows.
The plan changes wording, layout, colour and motion; it changes no rule's detection and no
acceptance oracle. The yardstick throughout is an engineer who lives in VS Code: every screen
should put each thing where VS Code puts it, name it as VS Code's Japanese localisation names it
unless this product has a reason to differ, and show nothing the reader does not need at that
moment.

## 1. Baseline

| Check | Result |
|---|---|
| `check_slop.py` (48 files) | 0 errors, 0 warnings |
| `check_copy.py` (353 strings) | 0 errors, 0 warnings |
| `check_shippable_text.py` (39 files) | 0 errors, 0 warnings |
| textlint, UI config, on `copy-prose.md` | clean |
| textlint, full Japanese config | 12 hits, all misfires on the one-string-per-line format except three half-width colons (「ルール: すべて」 and siblings), settled below |
| natural-japanese `lint.py --genre tech` | 8 informational hits: the same 「資産フォルダを選ぶと、ここに…」 opening repeated across four empty states |
| Unreferenced keys in `text.ts` | 12 of 389 |

The mechanical gates are clean, so everything below is what a script cannot see: information
that is duplicated, absent, false, or shaped for the wrong reader.

Audits (all on opus, none given the others' findings): layout and density (21 findings),
readability and contrast (19), information design (24), conformance and copy truth (23),
GUI string catalog (96 rewrites, 15 deletes), engine rule text and error text (81),
animation opportunities (4 additions, 4 removals), shippable-text (20). Every finding is ruled
on in Appendix A.

## 2. Decision rules

These settle the disputes the audits raised, and any new one that comes up while implementing.

1. **A string exists only if it carries what the reader needs at that moment.** A toast confirming
   what the screen shows, a heading repeating the tab, a helper line restating its label, and a
   sentence explaining what a pane will contain are deleted, not reworded.
2. **VS Code's Japanese word wins for shell concepts** (エクスプローラー, 出力, 設定, 配色テーマ,
   キャンセル, 変更を破棄, 行 N、列 N). **The product's own word wins for its domain** (資産, 指摘,
   ルール, 修正案, 中止 for the run). 指摘 stays; 問題 is not adopted.
3. **One object, one name.** The families and the winning word are in §4.3.
4. **Register.** 敬体 everywhere, GUI and engine alike, in the shape Japanese compiler
   diagnostics use: javac 「シンボルを見つけられません」, MSVC 「'x': 定義されていない識別子です。」,
   GCC 「'x' が宣言されていません」, Enterprise COBOL 「"X" は定義されていませんでした。」. A finding
   message is the identifier followed by its state in 〜です / 〜ています / 〜されていません; rule prose
   is 敬体 description. The engine's 常体 house rule in `docs/rule-text.md` is retired (D1). A 敬体
   frame that interpolates an engine reason still keeps the reason on its own line, for layout, not
   register.
5. **A finding message is a diagnostic.** It opens with the identifier, states the defect in one
   clause, adds at most one sentence of consequence, and never carries the remedy, the rule name
   or the analysis that found it.
6. **Truth over reassurance.** A label names exactly what the handler does; a guarantee that a
   setting on the same screen can break is guarded in code or removed.
7. **Motion goes down, not up.** Nothing keyboard-initiated animates. The tokens
   `--duration-*` and `--ease-*` are the only values used.

## 3. Decisions settled with the user (2026-09-02)

| # | Decision | Ruling |
|---|---|---|
| D1 | Register of engine diagnostics | **敬体 in the compiler shape** used by Java and C++ compilers (javac, Eclipse JDT, MSVC, GCC). Messages, code-flow labels where they are sentences, and all five rule prose fields move from 常体 to 敬体, so the product has one register. Field shapes are in Appendix C. |
| D2 | 桁 vs けた for fixed-format column positions | **桁 for column positions** (「80桁の記録」「8〜72桁」「開始桁」), as IBM's Japanese manuals write 第 7 桁; けた stays the JIS word for digits (けた数, けたあふれ). Recorded in `docs/rule-text.md`. |
| D3 | 「資産フォルダを選ぶ」 also runs the three-stage analysis | **No automatic analysis.** Opening a folder runs the `scan` stage only — file discovery, decoding, kind and codepage detection, and the call-graph link, which is what fills the explorer and the graph — and never lint or sql-lint. The engineer can open, read and edit without a run; 解析 stays an explicit action (title-bar button and palette; no chord is bound to it today and none is added) and runs scan → lint → sql-lint. The button reads 「資産フォルダを開く」. |

Settled without asking: フォルダ keeps no 長音 (the catalog is uniform and it is the register the
user writes in); 中止 stays for cancelling the run; 指摘 stays.

## 4. Work, by phase

Each item names its source finding (Appendix A) so the reasoning is traceable. Phases are
ordered by what breaks if it stays wrong; within a phase, order is free.

### Phase 0 — Truth and safety (do first)

| Item | Change | Where | Source |
|---|---|---|---|
| 0.1 | Refuse a 修正案の出力先 that is the asset folder or inside it; error 「資産フォルダの外を指定してください。」. Reword the note to 「資産フォルダの外へ書き出します。」 | `editors/settings/Settings.tsx`, `model/artifactPaths.ts` | C3 |
| 0.2 | Register a picocli `IExecutionExceptionHandler` in `Main` so an uncaught exception prints one Japanese line (cause, then what to do) and exits with `ExitCodes.ERRORS`; no stack trace reaches stderr | `app/cli/Main.java` | E67 |
| 0.3 | Stop splicing `Exception.toString()` into Japanese stderr warnings; print the path and the next step | `pipeline/Decode.java:37-38`, `pipeline/Link.java:71-72`, `cli/SaveCommand.java:116` | E68, E69, E72 |
| 0.3b | The JSON `error` fields that the GUI shows verbatim carry a Japanese sentence (cause, then the next step) instead of `e.getMessage()` / `e.toString()`: 「本文を復号できない: 〈reason〉。文字コードを選び直す。」 and 「保存できない: 〈reason〉。原本は書き換えていない。」, where 〈reason〉 is a Japanese mapping of the exception class (missing file, permission, unsupported codepage), never the raw text | `cli/DecodeCommand.java:62-64`, `cli/SaveCommand.java:82` | E70, E71 |
| 0.4 | Print the missing-database message and return `ExitCodes.ERRORS` instead of throwing | `cli/ReportRunner.java:111` | E73 |
| 0.5 | Translate the main-process error strings that reach the renderer through `errorMessage()`: `save.ts:48`, `decode.ts:88-92`, `pathGuard.ts:47`, `importSource.ts:78-98` | `src/gui/src/main/**` | E78–E80, E64 |
| 0.6 | On cancel, do not read the killed stage's artefact; log 「〈段階〉を中止しました。」 and skip the done/failed line. Add `state/useAnalysis.test.ts` (none exists) asserting that after `cancel()` the killed stage's artefact reader is not called and the log holds the 中止 line and no done/failed line | `state/useAnalysis.ts:109-175`, `main/engine/run.ts:92` | C6 |
| 0.7 | Drop 「出力を確認してください。」 from `save.failed`, `transpileView.error`, `fixView.error`: nothing logs those failures to the output panel. The appended reason is the cause | `i18n/text.ts` | C4 |
| 0.8 | Give save-verification rows a Japanese rule name (「保存時の検証」) in the `ruleOf` fallback | `model/ruleIndex.ts:44-48` | C7 |
| 0.9 | Confirm before 「変更を破棄する」. Reuse the `Modal` component the close guard uses (wired today only to tab close through `model/closeGuard.ts`), with a new body string `modal.discardBody` 「破棄すると、この資産の変更は失われます。」 and the buttons 「破棄する」/「編集を続ける」; wire SourceEditor's discard handler to it. The reset clears the undo stack, so the gate is the only way back | `editors/source/SourceEditor.tsx:372-407`, `App.tsx:326-373` | C5 |
| 0.10 | Keep the requested pane size apart from the clamped render size so a zoom round-trip no longer shrinks the side bar and panel permanently | `ui/SplitHandle.tsx:136-140` | L4 |
| 0.11 | Capture `checkZoomReflow.png` while the zoom factor is still 2.0 | `smoke/render.cjs:936-957, 1049-1058` | L5 |
| 0.12 | Relabel the fix-apply button 「フォルダ全体の修正案を書き出す」 and count the files in the toast | `editors/diff/FixDiff.tsx:90-103`, `text.ts fixView.apply/applied` | C2 |
| 0.13 | Folder open runs the scan only (D3). Split `useAnalysis` into `scan(inputDir)` — stage 1 as it is today, reading the inventory afterwards — and `run(inputDir)`, which calls `scan` then lint and sql-lint. `selectFolder` calls `scan`; the 解析 button (`TitleBar` `onRun`) and the palette command `run.analyze` (`commands.ts:117`) call `run`; the doc comment on `selectFolder` at `commands.ts:62` changes with it. The log line for a folder open is 「走査を終えました（資産 N件）。」; 「解析を開始しました。」 is logged only by `run`. Extend `useAnalysis.test.ts` (0.6) with: opening a folder invokes the `scan` subcommand and neither `lint` nor `sql-lint` | `App.tsx:108-132`, `state/useAnalysis.ts`, `state/commands.ts:110-122` | C1, D3 |

### Phase 1 — Delete and consolidate

Chrome and views. Every row removes something or merges two things into one.

| Item | Change | Where | Source |
|---|---|---|---|
| 1.1 | Delete the 指摘 entry from the activity bar and the compact side-bar copy; 指摘 lives in the panel only | `shell/ActivityBar.tsx`, `shell/SideBar.tsx` | I1 |
| 1.2 | Delete the encoding toolbar row above every source editor. The status-bar encoding item becomes a button that opens the codepage list (VS Code's 「エンコード付きで再度開く」); add the same as a palette command 「文字コードを指定して開き直す」 | `editors/source/SourceEditor.tsx:379-395`, `shell/StatusBar.tsx:51-54`, `state/commands.ts` | I3, L8 |
| 1.3 | Move 「変更を破棄する」 off the toolbar into the palette and the tab context menu (with 0.9's confirmation) | `SourceEditor.tsx:398-407` | L8, C5 |
| 1.4 | Delete the success toasts for save and reload (discard has none today); keep failures and `save.savedWithErrors` | `state/useSourceSave.ts:124,196` | I17 |
| 1.5 | Delete the body headings that repeat the tab: 設定, 利用者定義ルール, レポート, and the 「修正案 〈path〉」 line | `Settings.tsx:124`, `CustomRules.tsx:48`, `ReportEditor.tsx:87`, `FixDiff.tsx:108-110` | I10 |
| 1.6 | Settings apply on change; delete 「保存する」, 「保存しました。」, 「未保存の変更があります。」 and the threshold note (the label 指摘の重大度しきい値 already says it) | `Settings.tsx:100-142, 237-252` | I6, I18, I20 |
| 1.7 | Custom-rule editor: mark the tab with the dirty dot through `isTabDirty`; delete its 「未保存の変更があります。」 line | `shell/EditorTabs.tsx:72-78`, `CustomRules.tsx:82` | I18 |
| 1.8 | Explorer rows: keep the codicon glyph, drop the kind text; drop the per-row codepage text (the status bar carries it); keep the finding-count pill; keep 文字コード不明 as a warning glyph with a tooltip. Drop `overflow-x` on the side-bar body | `views/explorer/Explorer.tsx:200-220`, `styles/lists.css:89-116`, `chrome.css:174-179` | I2, L6, L7, R15 |
| 1.9 | Status bar: delete the folder path (the title-bar chip has it); make 資産 N件 a button that opens the explorer; merge 「コード指摘」「SQL指摘」 into one 「指摘 N件」 button. Delete `problems.allSources` and `source.*` | `shell/StatusBar.tsx` | I8, I9, I24, C12 |
| 1.10 | Rules view: badge only 利用者定義 rules; fold the effective severity into the select (「既定（高）」 as the default option); delete the two 「表示中をすべて…」 buttons, keep the per-group ✓/✗ pair; stretch the remaining toolbar controls to one edge | `views/rules/Rules.tsx:56-83, 107-177`, `styles/forms.css:18-23, 57-83` | I5, I15, L17, L19 |
| 1.11 | Findings detail pane: actions first, then the message; delete the head that repeats the selected row | `views/problems/Problems.tsx:59-135` | I4, I14 |
| 1.12 | Call graph: render a kind chip only when its count is above zero (the legend already does); drop the `opacity` on disabled chips | `editors/graph/GraphEditor.tsx:235-250`, `styles/graph.css:132-135` | I16, L11, R13 |
| 1.13 | Import dialog: show 「取り込み先」 only when it differs from the typed name; show the copybook note only while コピー句 is selected; delete the empty-preview sentence (the 0行 count states it) | `views/explorer/ImportDialog.tsx:169-187, 238` | I19, L14, ST |
| 1.14 | Delete the 12 unreferenced keys, plus `explorer.changeFolder` (one label under D3) and `graph.searchLabel` (identical to `graph.search`). Delete `Placeholder.tsx` and `placeholder.notImplemented`; the fallback `return <Placeholder />` in `editorFor()` becomes `throw new Error(\`no editor for tab ${tab.kind} ${tab.path}\`)`. A compile-time `never` check is not available: `WorkbenchTab` is a flat interface whose `path` is `string | null` for every kind, and the source/fix/transpile branches also test `path !== null`, so the fallback stays reachable to the type checker. An unreachable state fails loudly instead of rendering a screen | `i18n/text.ts`, `editors/Placeholder.tsx`, `shell/EditorGroup.tsx:14,69` | copy audit |
| 1.15 | 「起点を解除」 disabled when no focus; 「この資産の変換結果を開く」 offered for COBOL only | `GraphEditor.tsx:190-201`, `state/commands.ts:194-205` | C13, C19 |
| 1.16 | Rule tab title `${id} ${name}`, not the bare id | `state/workbenchStore.tsx:98-100` | I13 |
| 1.17 | Edge tables: 相手 first, 実行順 second | `editors/graph/GraphDetailPane.tsx:117-126` | I21 |
| 1.18 | Rules list: a failed catalog load shows 「ルールを読み込めませんでした。」 with the reason instead of the loading line | `views/rules/Rules.tsx:96-97`, `state/useShellStartup.ts:53-75` | C17 |

### Phase 2 — Rewrite the GUI strings

All in `src/gui/src/renderer/src/i18n/text.ts` unless a component is named. Appendix B holds the
complete old → new table, including the keys Phase 1 deletes and the keys this phase adds; the
sections below explain the groupings.

#### 2.1 Empty and loading states — one shape

Replace the per-view 「〜すると、ここに…が出ます。」 sentences with two shared strings and one
branch on `inputDir`, used by explorer, problems, output, graph, report and transpile:

- `empty.noFolder` 「資産フォルダを開いていません。」
- `empty.notAnalysed` 「まだ解析していません。」

Under D3 the explorer and the graph are filled by the scan that a folder open runs, so they use
only the first string (the graph keeps its own 「ノードがありません」 state); problems, output, report
and transpile use both. The report tab stops asking for an analysis when no folder exists, and
the transpile pane stops reporting 「変換結果はありませんでした」 before any run. Present tense for standing states: 「〜はありません。」. The findings detail
placeholder becomes 「指摘を選んでください。」. (ST, I23, C8, C9, C21, copy audit)

#### 2.2 Errors — cause the reader can act on

- `explorer.error`, `problems.error`: add 「もう一度解析してください。」 (C6 of ui-copy).
- `sourceView.error`: 「本文を表示できませんでした。」 plus the engine's reason; the codepage advice
  only when the reason names the codepage. (C16)
- `run.stageFailed`: 「〈段階〉を完了できませんでした。」 with the reason on its own line. (E63)
- `settings.copybookMissing`: 「このフォルダは見つかりません（このまま保存できます）。」 — moot if
  1.6 lands first; then 「このフォルダは見つかりません。」 stays as a warning, not an error style. (C23)
- `customRules.problem.idDuplicate` 「このIDは他のルールで使われています。」; `listHint` names
  カンマ・読点・改行 (the parser accepts all three). (copy audit)
- `save.savedWithErrors`: 「…保存時の検証で N件の指摘が出ています。」 — the rows land in the 指摘
  table, so call them that. (copy audit)
- On an aborted 「すべて保存する」, notify 「N件の資産が未保存のままです。」. (C10)

#### 2.3 Consistency families

| Family | Winner | Keys |
|---|---|---|
| Unsaved content 編集/変更 | 変更 | `sourceView.discard`, `modal.confirmDiscardBody`, `save.nothingToSave`, `save.dirtyBeforeFolderChange`, `save.conflictBody` (keep 編集 in 「編集を続ける」 and the pane label 「編集中」) |
| Opening a tab 見る/開く | 開く | `problems.detailRule`, `problems.showFix`, `quickFix.showFix`, `problems.openInGraph` (「差分を見る」 stays: it reveals inside the dialog) |
| Removing 消す/削除/クリア | 削除 for list items (削除する on the danger button), 消去 for the log | `customRules.remove`, `settings.copybookRemove`, `output.clear` |
| Adding 足す/追加 | 追加 | `customRules.add`, `settings.copybookAdd` |
| Backing out やめる/キャンセル | キャンセル in dialogs; 中止 stays for the run | `save.conflictCancel`, `import.cancel` |
| CALL 呼出/呼び出し | 呼び出し (glossary) | `graph.title`, `graph.loading`, `graph.error`, `graph.noNodes`, `graph.canvas`, `graph.edgeKind.CALL`, `problems.openInGraph`, `command.showGraph` |
| Source text ソース/本文 | 本文 | `sourceView.editor` |
| 変換/生成 | 変換 | `transpileView.language` 「変換する言語」, `transpileView.right` 「〈name〉（変換結果）」 |
| Import 保存/取り込み | 取り込み | `import.title` 「端末からの取り込み」, `import.saving` 「取り込んでいます…」, `import.encodingNote` 「UTF-8で取り込みます。」, `import.saved` 「「〈path〉」を取り込みました（N行）。もう一度解析すると一覧に出ます。」, `import.paste` 「端末から貼り付けた本文」 |
| Graph root 中心/起点 | 起点 | `graph.depthLabel` 「起点からたどる深さ」; `import.columnsNote` 「1始まり」 |
| Toggle 開閉/切り替え | 切り替える | `copyExpansion.action`, `copyExpansion.glyphHint`; `copyExpansion.collapse` 「展開を畳む」 |
| Explorer name | エクスプローラー (VS Code) | `activity.explorer`, `sideBar.explorerTitle`, `command.showExplorer` |
| Region labels | アクティビティバー, ステータスバー, エディタータブ | `activity.label`, `status.label`, `editor.tabs` |
| Numeral + counter | no space: N件, N行, Nバイト | `explorer.findingCount`, `import.lineCount`, `copyExpansion.heading`, `run.stageDone`, `run.scanDone`, `save.savedWithErrors`, `sourceView.overflow`, `problems.jumpTo`, `graph.nodeCount` (「行 11、列 80」 stays: it is VS Code's) |
| Paths in sentences | 「 」 | `save.savedWithErrors`, `save.failed`, `save.conflictBody`, `import.exists`, `import.saved`, `report.saved`, `fixView.applied` (not aria-labels or pane labels) |
| Half/full-width spacing | none between ASCII and kana | `customRules.rawLabel`, `customRules.rawInvalid`, `customRules.messageHint` (「${match}」) |

#### 2.4 Single rewrites

`status.stage` → the stage name (走査 / 指摘の検出 / SQL指摘の検出) beside the progress bar;
`welcome.shortcutHint` 「コマンドパレットを開く」; `explorer.typeLabel` 「資産の種別で絞り込む」;
`graph.kinds` 「ノードの種別で絞り込む」; `graph.incoming`/`outgoing` 「このノードへの辺」/「このノードからの辺」;
`graph.columnSeq` 「実行順」; `rules.searchLabel` 「ルールをID・名前・概要で絞り込む」;
`rules.enabledLabel` 「〈id〉の有効・無効」; `rules.detailUnknown` 「このルールは見つかりません。」;
`customRules.paneRaw` 「JSON」, `paneLabel` 「編集方法」, `validate` 「検証」, `save` 「保存」,
`validationFailed` (は), `fieldMissingClause` 「欠けていると検出する句」, `fieldIgnoreCase`
「大文字と小文字を区別しない」, `fieldOnEveryPath` 「全経路での検査を求める」;
`settings.theme` 「配色テーマ」; `import.columnsInvalid` 「終了けたは開始けた以上にしてください。」
(桁 under D2); `problems.thresholdNote` shown only when something is hidden, with the count:
「しきい値未満の指摘 N件を表示していません。」; `modal.confirmDiscardBody` keeps its sentence (VS Code's
own dialog carries the consequence line); `save.conflictBody` keeps three sentences with 変更.

Dialog button order (conflict): 差分を見る (primary) → 再読み込みする → 上書きする (danger, unfilled)
→ キャンセル. (I7, C22, L15)

### Phase 3 — Engine text

| Item | Change | Where | Source |
|---|---|---|---|
| 3.1 | In `docs/rule-text.md`: change the 文体 decision (line 19) from 常体 to 敬体 in the compiler shape (D1); replace the "Sentence shapes" list (lines 62-76) with Appendix C, which restates every field shape in 敬体 and adds the message rules; record 桁 for column positions (D2) | `docs/rule-text.md:19, 62-76` | E1, E62, E65, E66, D1, D2 |
| 3.2 | Rewrite all 38 message variants (34 rules, four of them with two forms) to that shape. Appendix D holds the proposed text per rule; it is the starting point, adjusted where a rule test or `samples/expected-results.md` shows the message needs a fact the proposal dropped. Five rules gain the identifier they omit today (R010, R013, R025, R026, R029); six drop the remedy sentence (R001, R003, R011, R017, R018, S001) | `src/engine/rules/src/main/java/jp/cobolinsight/rules/**` | E2–E38 |
| 3.2b | Move the five prose fields (summary, rationale, detection, remedy, and the fix descriptions in `FixEdits`) of all 34 rules and the three custom-rule kinds (`line`, `statement`, `checked-after`) to 敬体, field by field, with the shapes in Appendix C: summary 「〜を検出します。」, detection 「〜を検出します。〜は対象外です。」, remedy 「〜してください。」. Mechanical bulk work: one pass per file, no change to facts. `RuleTextGlossaryTest` is updated first (3.7) so the build fails on any 常体 ending left behind | `rules/**`, `rules/FixEdits.java`, `rules/custom/*` | D1 |
| 3.3 | Code-flow labels: one clause, no internal 「。」; parentheses for the role | R001, R017, R018 | E39–E41 |
| 3.4 | 検出条件 states the condition and the exclusions, not the module, the signal name or the trade-off: S001, S002, R011, R018, R023, R010, R030, and the four that open with the analysis name | | E42–E49 |
| 3.5 | Names: full-width parentheses; a space between a reserved word and kana as the body text does; 「JCL ステップ間の COND パラメーター未指定」; 「静的に追えない ALTER 文」; 「列を明示しない SELECT *」; 「原始プログラムに直接書かれた資格情報」 (and 資格情報 in R026's message); 「PERFORM THRU の入口を経由しない GO TO 文」 | | E52–E58 |
| 3.6 | Remedy S002 to two sentences; R029's heuristic sentence moves from rationale to detection | | E50, E51 |
| 3.7 | Glossary: add 動的SQL文 (drop 文字列), オプティマイザー as accepted, the message-vs-prose rule for section names, and 桁 for column positions (D2). `RuleTextGlossaryTest`: the matcher is `line.contains(entry[0])` (lines 70-74), so change `BANNED` from string pairs to `Pattern` pairs and match with `find()`; the 桁 entry becomes `(?<![0-9第〜])桁` so 「8〜72桁」「第 7 桁」 pass and 「桁数」「桁あふれ」 fail; delete the three entries that ban 敬体 endings (`{"ます。","常体"}`, `{"です。","常体"}`, `{"ません","常体"}` at lines 49-51) and add the reverse — a 常体 sentence ending (`(る|い|ない|である|得る)。`) in any text field fails; extend the scan to the `app` module: replace the single `root` (`Path.of("src/main/java/jp/cobolinsight/rules")`, line 60) and `SCANNED` (line 24) with a list of (root, packages) pairs — the existing rules pair plus `(Path.of("../app/src/main/java/jp/cobolinsight/app"), {"cli", "pipeline"})` — and loop over both; then ban ソース, コール, 可能性がある, 条件コード | `docs/rule-text.md`, `RuleTextGlossaryTest.java:24,29,60` | E59–E61, E76, E77, E81, D1, D2 |
| 3.8 | CLI messages: 「該当するルールが無い: 〈id〉。--id を外して一覧を出す。」; 「警告: rules.json の 〈error〉。該当ルールは無効のまま解析を続ける。」; 原始プログラム for ソース in `TranspileRunner`, `Persist` | `cli/RulesCommand.java:34`, `cli/RuleOptions.java:42`, `cli/TranspileRunner.java:199`, `pipeline/Persist.java:571` | E74–E77 |
| 3.9 | Update the ~21 rule tests that assert on message substrings; `samples/expected-findings.tsv` and `corpus/baseline.tsv` are unaffected because no detection changes — confirm with `RuleEvaluationReportTest` and `ExpectedFindingsAcceptanceTest` | `src/engine/**/test` | E-track summary |

### Phase 4 — Layout and readability

| Item | Change | Where | Source |
|---|---|---|---|
| 4.1 | Findings table: ルール and 資産 are 16em each today and 内容 already absorbs the rest under `table-layout: fixed`; set ルール 10em and 資産 12em so 内容 gets the width back, and show the severity column as the glyph only (no wrapped 「重大/度」 header) | `styles/lists.css:194-205` | L1, L2 |
| 4.2 | Default panel height 300 px | `state/workbenchStore.tsx:65` | L3 |
| 4.3 | One filter box for the findings panel with the severity toggles inside it; sorting by column header; delete 「ルール: すべて」「資産: すべて」「重大度順」 as separate controls (this also removes the half-width colons textlint flagged) | `views/problems/Problems.tsx:218-294` | L10 |
| 4.4 | Settings controls capped at 320 px; the 72ch measure stays for prose only | `styles/forms.css:184-189` | L13 |
| 4.5 | Tabs shrink to a 120 px minimum before the strip scrolls; overflow chevrons instead of the native scrollbar | `styles/chrome.css:221-264` | L16 |
| 4.6 | One left inset for the whole side-bar column: the toolbar and filters use the 12 px padding in `lists.css:17-22`, the tree rows the inline `paddingInlineStart: depth * 12 + 8` in `Explorer.tsx:182`; make the row formula start at 12 px | `styles/lists.css:17-22`, `views/explorer/Explorer.tsx:182` | L18 |
| 4.7 | Rulers only on the COBOL pane of the transpile view | `editors/transpile/SideBySide.tsx:29-49` | L20 |
| 4.8 | Toast anchored above the panel's top edge, not over the detail pane | `styles/overlays.css:140-151` | L21, R8 |
| 4.9 | Graph side panes collapsible so the drawing keeps the width | `styles/graph.css:98-105, 215-222` | L12 |
| 4.10 | Identification area: drop `opacity: 0.6`, carry the effect in the band background | `styles/editor.css:123-126` | R1 |
| 4.11 | Light editor tokens: sequence `#6b7280` → `#5b6270`, indicator `#8a5f0f` → `#6f4c0a`, comment `#3f7d3f` → `#2f6a34`. Acceptance: each at 5.5:1 or better against the light editor background `#f4f7fa` (the dark twins sit at 5.5, 9.9 and 5.6) | `vendor/monarch.ts:337-352` | R2 |
| 4.12 | `--ci-bg-selected` mix 28 % → 14 %; selection carried by the inset rule | `styles/app-tokens.css:10` | R3 |
| 4.13 | Dark accent `oklch(72% 0.15 80)` so MEDIUM never out-shouts HIGH; warning badge gets its own ground | `tokens.json color_dark.accent`, `styles/lists.css:118-120` | R4, R7 |
| 4.14 | Theme-aware scrim (`.dark` 70 %), dialog lifted 6 % foreground; dark shadows with a light ring | `styles/app-tokens.css:13`, `tokens.json shadow` + `.dark` block | R6, R18 |
| 4.15 | Glyph-margin hover carries the severity word | `editors/source/SourceEditor.tsx:325` | R5 (hover only) |
| 4.16 | Code font 13 px / 20 px line height; graph labels 12 px (`font-size` at line 226) with node `height` 34 → 36 (line 234) | `vendor/monarch.ts:84-88`, `model/graphLayout.ts:226,234` | R9, R10 |
| 4.17 | `renderLineHighlight: 'line'` in the source editor with a 6 % foreground mix in both palettes; `editorError/Warning/Info.foreground` set from the app's own hexes | `SourceEditor.tsx:159`, `vendor/monarch.ts:354-361` | R11, R12 |
| 4.18 | Disabled primary label at ~4.5:1; `--ci-border-strong` on the five controls still using `--color-border`; COPY zone tint per theme; custom-rule textarea on `--color-surface` | `styles/base.css:101-105`, `chrome.css:72`, `lists.css:252,344`, `base.css:147`, `overlays.css:159`, `editor.css:177-186`, `forms.css:325-335` | R14, R16, R17, R19 |

Token changes go through `tokens.json` → `validate_tokens.py` → `generate_theme.py`, and the
Monaco and Cytoscape hex pairs are updated by hand as `docs/gui.md` requires.

### Phase 5 — Motion

| Item | Change | Where | Source |
|---|---|---|---|
| 5.1 | Remove the command-palette entrance animation (keyboard-initiated, 100+/day) | `styles/overlays.css:47` | A-remove |
| 5.2 | Remove the hover transition on explorer and findings rows; drop `border-color` from the activity-bar and panel-tab transitions | `lists.css:71,235`, `chrome.css:139-142, 326-328` | A-remove |
| 5.3 | Toast enter (`@starting-style`, opacity + `translateY(8px)`, `--duration-base`) and exit on the same edge (`--duration-fast`, a `leaving` flag in `Toast.tsx`) | `overlays.css:153`, `ui/Toast.tsx` | A1 |
| 5.4 | Backdrop fades with the modal it belongs to (`--duration-base`); the palette backdrop stays instant | `overlays.css:30-35` | A2 |
| 5.5 | Progress bar value transition (`--duration-slow`) — only after one render confirms Chromium transitions the `::-webkit-progress-value` pseudo-element | `chrome.css:98` | A4 (deferred) |

Not done: press-scale on buttons (A3). The 解析 button already changes its label and starts the
progress bar; a scale on an IDE button is decoration.

## 5. Verification

After each phase:

```powershell
cd src\gui
python $env:USERPROFILE\.claude\skills\product-ui\scripts\check_slop.py src\renderer\src --theme theme.css
python $env:USERPROFILE\.claude\skills\product-ui\scripts\check_copy.py src\renderer\src --tokens tokens.json --emit-prose copy-prose.md
python $env:USERPROFILE\.claude\skills\shippable-text\scripts\check_shippable_text.py src\renderer\src --surface app
& "$env:USERPROFILE\.claude\textlint\node_modules\.bin\textlint.cmd" --config "$env:USERPROFILE\.claude\skills\product-ui\assets\textlintrc.ui.json" copy-prose.md
npm run check
npm run smoke:shots
```

After Phase 3, with `JAVA_HOME` set: `.\gradlew.bat build` (runs `RuleTextGlossaryTest`,
`RuleEvaluationReportTest`, `ExpectedFindingsAcceptanceTest` and the rule tests).

After Phases 1, 2 and 4, a fresh design review on the new screenshots (the four perspectives used
here, each on opus, none given this plan's reasoning), and `shippable-text-auditor` on the TSX.
Pass condition: no critical, every major ruled on.

## Appendix A — Rulings on every finding

Verdicts: **A** accept, **M** accept with modification, **R** reject, **D** defer. The reason is
one clause; the finding text is in the audit outputs.

### Layout and density (L)

| ID | Verdict | Reason |
|---|---|---|
| L1 | A | The message is the cell the engineer reads; VS Code leads with it |
| L2 | A | Header wraps at 48 px; glyph-only column is VS Code's shape |
| L3 | A | The 220 px default (`PANEL_LIMITS.initial`) leaves about 130 px for the detail pane after the tab row, filter row and table head; 300 px default |
| L4 | A | Real bug: zoom shrinks panes permanently |
| L5 | A | The 200 % capture is a 100 % capture; fix the smoke |
| L6 | A | File name is the only shrinking element |
| L7 | A | Follows from L6 |
| L8 | A | VS Code's reopen-with-encoding lives in the status bar |
| L9 | M | Delete the status-bar copy; the title-bar chip already shows the path, so no folder name in the explorer header |
| L10 | A | More chrome than data at the default height |
| L11 | A | Same as I16 |
| L12 | D | Collapsible panes are worth doing after the panel and table changes land; minor |
| L13 | A | 72ch is for prose |
| L14 | A | Same as I19 |
| L15 | M | No destructive default and Cancel last: yes; 差分を見る stays primary because two other audits independently want the safe non-terminal action emphasised |
| L16 | A | Clipped first tab with no ellipsis |
| L17 | A | Two views, two sizing rules |
| L18 | A | One inset |
| L19 | A | Same as I5 |
| L20 | A | Rulers over Java are wrong |
| L21 | A | Same as R8 |

### Readability and contrast (R)

| ID | Verdict | Reason |
|---|---|---|
| R1 | A | 2.2:1 on text the engineer reads to locate a card |
| R2 | A | Light tokens at or near the AA floor (sequence 4.5, comment 4.6, indicator 5.2); the dark twins are 1.2–1.9× higher (auditor's estimates from the hex pairs) |
| R3 | A | Selected row is the least readable row |
| R4 | A | Severity order flips between themes |
| R5 | M | Hover text yes; shapes per severity no — the table already carries severity as text, and VS Code marks by colour |
| R6 | A | Dark dialogs do not read as modal |
| R7 | A | Follows R4; own ground for the badge |
| R8 | A | Same as L21 |
| R9 | A | 12 px Japanese code below the product's own floor |
| R10 | A | Smallest type in the product on names that may be Japanese |
| R11 | A | Two code surfaces disagree; the editing one has no caret line |
| R12 | A | Two ambers for one finding |
| R13 | A | Moot once chips at zero are not rendered (I16) |
| R14 | A | Main action label at 3.8:1 |
| R15 | A | Same as L6 |
| R16 | A | The project's own token comment sets 3:1 |
| R17 | A | Prominent in one theme, recessive in the other |
| R18 | A | Elevation exists only in light |
| R19 | A | Editable surfaces should share one cue |

### Information design (I)

| ID | Verdict | Reason |
|---|---|---|
| I1 | A | One list in two shell slots, one of them degraded |
| I2 | A | Badge repeats the extension and truncates the name |
| I3 | A | Encoding three times; VS Code pattern |
| I4 | A | Actions below the fold |
| I5 | A | Severity twice, source label on every row |
| I6 | A | VS Code settings apply on change; the theme chips already look immediate |
| I7 | A | Destructive action first and loudest |
| I8 | M | Collapse to one 「指摘 N件」 rather than add a source filter; the rule id prefix (R/S) already tells the source and 0.8 names the save-check rows |
| I9 | A | Path twice |
| I10 | A | Tab already names the surface |
| I11 | A | 変換 |
| I12 | M | VS Code's word 「エクスプローラー」 in all three places, not 「資産エクスプローラー」 |
| I13 | A | Bare id in the tab strip |
| I14 | A | Head repeats the selected row |
| I15 | A | Five bulk controls above three rows |
| I16 | A | Chips and legend disagree |
| I17 | A | VS Code raises none of the three |
| I18 | A | One state, three expressions; two editors never mark the tab |
| I19 | A | Helper line repeats the field; note for an unselected kind |
| I20 | M | Delete the settings note (1.6); the panel note gains the hidden count |
| I21 | A | Blank leading column |
| I22 | R | VS Code's empty editor carries shortcut hints, not an instruction; `editor.empty` is deleted |
| I23 | A | Folded into 2.1 |
| I24 | A | Three look-alike items, one inert |

### Conformance and copy truth (C)

| ID | Verdict | Reason |
|---|---|---|
| C1 | M | The user settled it the other way (D3): drop the automatic run, open runs the scan only, label 「資産フォルダを開く」 |
| C2 | A | Whole-folder write behind a single-file screen |
| C3 | A | Critical: guard in code, reword the note |
| C4 | M | Drop the phrase; logging three more paths is the larger change for the same reader outcome |
| C5 | A | One-click irreversible loss with no gate |
| C6 | A | Log lies after cancel |
| C7 | M | Japanese fallback name; no source filter (see I8) |
| C8 | A | Folded into 2.1 |
| C9 | A | Folded into 2.1 |
| C10 | A | Minor, cheap |
| C11 | R | 「設定」 is VS Code's word, and the body heading is deleted anyway (1.5); 「表示と解析の設定」 disambiguates nothing since the rule views are named ルール |
| C12 | M | Once all three items act (I24) the affordance is uniform, as in VS Code; no underline |
| C13 | A | Inert control |
| C14 | A | 取り込み |
| C15 | A | Same as I11 |
| C16 | A | One message for several causes |
| C17 | A | Loading message after failure |
| C18 | R | The editor visibly opens; narrating it is what the ban list forbids; the placeholder becomes 「指摘を選んでください。」 |
| C19 | A | Command offered where it cannot apply |
| C20 | R | Saving a file is silent in VS Code; the tab dot (1.7) is the signal, and the reader re-runs the analysis to see rules take effect regardless |
| C21 | A | Folded into 2.1 |
| C22 | A | Same as I7 |
| C23 | M | Reword; becomes a warning style once settings apply on change |

### GUI string catalog ("Copy")

96 rewrites and 15 deletes proposed. Rulings by group; keys not listed under R or M are accepted
as proposed.

| Group | Verdict | Reason |
|---|---|---|
| 12 unreferenced keys, `graph.searchLabel`, `placeholder.notImplemented` | A | Dead strings |
| `explorer.changeFolder` delete | A | One label under D3 |
| `activity.explorer` → 資産エクスプローラー | M | エクスプローラー, VS Code's word (I12) |
| `status.stage` → 「3段階中1段階目」 | M | Show the stage name instead; the counter form is clumsy and the auditor's own alternative |
| `explorer.emptyNoFolder` → 「資産フォルダを選んでいません。」 | A | Becomes the shared `empty.noFolder` |
| `sourceView.reopenLabel` → 「文字コード」 | M | Superseded: the toolbar row is deleted (1.2) |
| `sourceView.overflow`, `import.columns*`, `customRules.area.programArea` 桁→けた | R | D2: 桁 for column positions |
| `save.saved`, `save.reloaded` brackets | M | Moot: both toasts are deleted (1.4) |
| `save.failed` | M | Brackets yes; 「出力を確認してください。」 is dropped (C4) |
| `save.conflictBody` | A | Three sentences with 変更; the shippable-text colon form is rejected below |
| `report.failed` + 「出力を確認してください。」 | R | The output panel never logs a report failure (C4); the reason is the cause |
| `rules.enableAll` / `disableAll` | M | Superseded: both buttons deleted (1.10) |
| `settings.thresholdNote` | M | Deleted (1.6) rather than reworded |
| `settings.save` → 保存 | M | Deleted (1.6) |
| `import.encodingNote` → 「UTF-8で保存します。」 | M | 「UTF-8で取り込みます。」 per the 取り込み family |
| `import.exists` | M | Brackets yes; second sentence deleted (the button says 上書きする) |
| フォルダ→フォルダー (16 keys) | R | Catalog is uniform; it is the register the user writes in |
| 指摘→問題 | R | Glossary fixes 指摘 (the auditor agrees) |
| 中止 for the run | A (keep) | Internally consistent, z/OS register |
| `sourceView.overflow` register (敬体 marker beside 常体 findings) | A (keep 敬体) | Moot under D1: the findings beside it become 敬体 too |
| Everything else in the rewrite list | A | As proposed |

### Engine text (E)

| ID | Verdict | Reason |
|---|---|---|
| E1 | A | 33 of 37 messages die at the ellipsis |
| E2–E6 | A | No identifier; identical findings within one program |
| E7–E38 | A | Applied as one batch under 3.2; proposals are the starting text, checked against the rule tests |
| E39–E41 | A | Code-flow label shape |
| E42–E48 | A | 検出条件 names modules and trade-offs |
| E49 | M | Open with the condition; keep the analysis name where it explains a hedge |
| E50, E51 | A | Field shapes |
| E52 | A | Third term for one concept |
| E53, E54 | A | Same token spelled two ways in one row |
| E55–E58 | A | Names should name the defect |
| E59–E62 | A | Glossary gaps and spacing rule |
| E63 | A | Reason on its own line (layout; the register seam is gone under D1) |
| E64 | M | Translate the sources (0.5) instead of adding a disclosure widget |
| E65, E66 | M | The auditor's premise — that 常体 is a house deviation from 敬体 compiler output — is accepted; its recommendation to keep 常体 is overruled by D1, and the engine moves to 敬体 |
| E67–E73 | A | Stack traces and English exceptions on screen |
| E74–E77 | A | Minor CLI wording |
| E78–E80 | A | English authored in the GUI's own main process |
| E81 | A | Extend the scan and the ban list |

### Animation (A)

| ID | Verdict | Reason |
|---|---|---|
| A1 toast | A | Only surface with no bridge; carries failures |
| A2 backdrop | A | Two halves of one surface arriving differently |
| A3 press scale | R | Label change and progress bar are the feedback; VS Code buttons do not scale |
| A4 progress | D | Needs one render to confirm the pseudo-element transitions |
| Remove palette animation | A | Keyboard-initiated |
| Remove row transitions | A | 100+/day sweeps |
| Shorten border-color transitions | A | Cheap; keyboard-switched marker |
| `--duration-slow`, `--ease-spring` unused | A (keep) | Schema-required tokens; A4 uses one |

### Shippable-text (ST)

| String | Verdict | Reason |
|---|---|---|
| Seven empty-state sentences 「〜すると、ここに…」 | M | Replaced by the two shared state sentences in 2.1, not by imperatives; a state reads correctly beside the button that performs the action |
| 「生成すると、ここにレポートが出ます。」 → 「レポート未生成」 | M | Becomes `empty.notAnalysed` or, after analysis, no text: the two generate buttons are the state |
| 「行を選ぶと、ここに指摘の詳細が出ます。」 | M | 「指摘を選んでください。」 |
| `import.previewEmpty` remove | A | The 0行 count states it |
| `modal.confirmDiscardBody` remove | R | VS Code's own dialog carries the consequence line; rewritten with 変更 instead |
| `save.conflictBody` colon form | R | A colon-joined sentence is worse Japanese; the three-sentence form with 変更 stays |
| `import.exists` second sentence | A | The button says 上書きする |
| `graph.canvas` | A | Keyboard route only, per the copy audit's wording |
| Six 「〜を入力してください。」 → 「必須です」 | R | 「〜してください」 is the recommended error form in ui-copy and the SmartHR shape; the label repetition is the norm |

## Appendix B — The string table

Every key in `text.ts` that changes. Keys not listed keep their current value. Function-valued
keys keep their signature unless a new parameter is shown. 桁 stays under D2.

### B.1 Deleted keys

Unreferenced today: `editor.empty`, `problems.allSources`, `source.lint`, `source.sql`,
`source.save`, `sourceView.codepage`, `sourceView.lines`, `transpileView.cobol`,
`transpileView.generated`, `save.discarded`, `graph.trace`, `graph.traceLabel`.

Made unnecessary by Phase 1: `explorer.changeFolder` (1.14), `graph.searchLabel` (1.14),
`placeholder.notImplemented` (1.14), `save.saved` and `save.reloaded` (1.4), `settings.save`,
`settings.saved`, `settings.dirty`, `settings.thresholdNote` (1.6), `customRules.dirty` (1.7),
`rules.enableAll`, `rules.disableAll` (1.10), `sourceView.reopenLabel` (1.2; the option label
`sourceView.reopenAuto` stays for the status-bar list), `status.codeFindings`, `status.sqlFindings`
(1.9), `status.stage` (replaced by the stage name, 2.4), `sideBar.problemsTitle` (1.1),
`activity.problems` (1.1).

Replaced by the shared empty states (2.1): `explorer.emptyNoFolder`, `problems.empty`,
`problems.emptyNoFolder`, `output.empty`, `output.emptyNoFolder`, `graph.empty`,
`graph.emptyNoFolder`, `report.notGenerated`, `report.noProject`, `problems.detailHint`.

### B.2 New keys

| Key | Value | Used by |
|---|---|---|
| `empty.noFolder` | 資産フォルダを開いていません。 | explorer, problems, output, graph, report, transpile |
| `empty.notAnalysed` | まだ解析していません。 | problems, output, report, transpile, when a folder is open and no run has finished |
| `problems.selectOne` | 指摘を選んでください。 | findings detail placeholder |
| `problems.hidden` | `(count) => しきい値未満の指摘 ${count}件を表示していません。` | shown only when `count > 0` |
| `status.findings` | 指摘 | the merged status-bar count 「指摘 N件」 |
| `run.stageCancelled` | `(stage) => ${stage}を中止しました。` | 0.6 |
| `save.unsavedRemain` | `(count) => ${count}件の資産が未保存のままです。` | C10 |
| `modal.discardBody` | 破棄すると、この資産の変更は失われます。 | 0.9 |
| `modal.discardConfirm` | 破棄する | 0.9 |
| `command.reopenWithEncoding` | 文字コードを指定して開き直す | 1.2 |
| `command.discard` | 変更を破棄する | 1.3 |
| `rules.loadFailed` | ルールを読み込めませんでした。 | 1.18 |
| `settings.fixOutDirInside` | 資産フォルダの外を指定してください。 | 0.1 |
| `transpileView.unsupported` | COBOL以外の資産は変換の対象外です。 | fallback if 1.15's `when` cannot be narrowed |

### B.3 Rewritten keys

| Key | Current | New |
|---|---|---|
| `activity.explorer` | 資産 | エクスプローラー |
| `sideBar.explorerTitle` | 資産エクスプローラー | エクスプローラー |
| `command.showExplorer` | 資産エクスプローラーを開く | エクスプローラーを開く |
| `activity.label` | 画面の切り替え | アクティビティバー |
| `status.label` | ステータス | ステータスバー |
| `editor.tabs` | 開いている資産 | エディタータブ |
| `welcome.selectFolder`, `explorer.selectFolder` | 資産フォルダを選ぶ | 資産フォルダを開く |
| `command.selectFolder` | 資産フォルダを選ぶ | 資産フォルダを開く |
| `run.noFolder`, `import.noFolder` | 先に資産フォルダを選んでください。 | 先に資産フォルダを開いてください。 |
| `welcome.shortcutHint` | コマンドパレット | コマンドパレットを開く |
| `explorer.typeLabel` | 種別で絞り込む | 資産の種別で絞り込む |
| `explorer.error` | 資産一覧を読めませんでした。 | 資産一覧を読めませんでした。もう一度解析してください。 |
| `explorer.findingCount` | `指摘 ${count} 件` | `指摘 ${count}件` |
| `problems.error` | 指摘を読めませんでした。 | 指摘を読めませんでした。もう一度解析してください。 |
| `problems.thresholdNote` | 重大度しきい値より低い指摘は表に出していません。 | replaced by `problems.hidden` |
| `problems.detailRule` | ルールの説明を見る | ルールの説明を開く |
| `problems.showFix` | 修正案を見る | 修正案を開く |
| `problems.openInGraph` | `${file} を呼出関係図で見る` | `${file} を呼び出し関係図で開く` |
| `problems.jumpTo` | `${file} の ${line} 行へ` | `${file} の${line}行へ` |
| `output.clear` | ログを消す | ログを消去 |
| `sourceView.editor` | ソース本文 | 本文 |
| `sourceView.error` | 本文を表示できませんでした。文字コードの指定を変えて開き直してください。 | 本文を表示できませんでした。 (the engine's reason follows on its own line; the codepage advice is appended only when the reason names the codepage) |
| `sourceView.discard` | 編集を破棄する | 変更を破棄する |
| `sourceView.overflow` | `この行は ${bytes} バイトあり、80 桁の記録に収まりません。` | `この行は${bytes}バイトあり、80桁の記録に収まりません。` |
| `copyExpansion.heading` | `COPY ${name}（${lines} 行）` | `COPY ${name}（${lines}行）` |
| `copyExpansion.collapse` | 展開を閉じる | 展開を畳む |
| `copyExpansion.toggleLabel` | `${name} の展開の開閉` | `${name} の展開` |
| `copyExpansion.action` | カーソル行のCOPYの展開を開閉する | カーソル行のCOPYの展開を切り替える |
| `copyExpansion.glyphHint` | このCOPYの展開を開閉します。 | このCOPYの展開を切り替えます。 |
| `transpileView.language` | 生成する言語 | 変換する言語 |
| `transpileView.right` | `${name}（生成）` | `${name}（変換結果）` |
| `transpileView.error` | 変換結果を取得できませんでした。出力を確認してください。 | 変換結果を取得できませんでした。 |
| `transpileView.empty` | この資産の変換結果はありませんでした。 | この資産の変換結果はありません。 |
| `save.savedWithErrors` | `${path} を保存しました。保存時の検証で ${count} 件の誤りが見つかりました。` | `「${path}」を保存しました。保存時の検証で${count}件の指摘が出ています。` |
| `save.failed` | `${path} を保存できませんでした。${reason} 出力を確認してください。` | `「${path}」を保存できませんでした。${reason}` |
| `save.nothingToSave` | 保存する編集がありません。 | 保存していない変更はありません。 |
| `save.dirtyBeforeFolderChange` | 編集中の資産があります。保存するか編集を破棄してから、資産フォルダを切り替えてください。 | 保存していない変更があります。保存するか変更を破棄してから、フォルダを開いてください。 |
| `save.conflictBody` | `${path} は、開いたあとに…編集中の内容は失われます。` | `「${path}」は、開いたあとにこのツールの外で書き換わりました。上書きすると、その変更は失われます。再読み込みすると、保存していない変更は失われます。` |
| `save.conflictCancel` | やめる | キャンセル |
| `quickFix.showFix` | この指摘の修正案を見る | この指摘の修正案を開く |
| `fixView.error` | 修正案を取得できませんでした。出力を確認してください。 | 修正案を取得できませんでした。 |
| `fixView.empty` | この資産に対する修正案はありませんでした。 | この資産に対する修正案はありません。 |
| `fixView.apply` | 修正案を書き出す | フォルダ全体の修正案を書き出す |
| `fixView.applied` | `${dir} へ修正案を書き出しました。` | `(dir, count) => 「${dir}」へ${count}件の修正案を書き出しました。` |
| `graph.title` | 呼出関係図 | 呼び出し関係図 |
| `graph.loading` | 呼出関係を読み込んでいます… | 呼び出し関係を読み込んでいます… |
| `graph.error` | 呼出関係を読めませんでした。もう一度解析してください。 | 呼び出し関係を読めませんでした。もう一度解析してください。 |
| `graph.noNodes` | 呼出関係のノードがありません。JCLかCOBOLを含むフォルダを解析してください。 | 呼び出し関係のノードがありません。JCLかCOBOLを含む資産フォルダを解析してください。 |
| `graph.canvas` | 呼出関係図。ノードを押すと右に情報が出ます。キーボードでは左の実行順から選びます。 | 呼び出し関係図。キーボードでは実行順の一覧から選びます。 |
| `graph.depthLabel` | 中心から辿る深さ | 起点からたどる深さ |
| `graph.kinds` | 種別で絞り込む | ノードの種別で絞り込む |
| `graph.nodeCount` | `ノード ${visible} / ${total}` | `ノード ${visible}/${total}` (and the depth readout in `GraphEditor.tsx:187` the same way) |
| `graph.incoming` | このノードへ | このノードへの辺 |
| `graph.outgoing` | このノードから | このノードからの辺 |
| `graph.columnSeq` | 順 | 実行順 |
| `graph.edgeKind.CALL` | 呼出（CALL・XCTL・LINK） | 呼び出し（CALL・XCTL・LINK） |
| `report.saved` | `${path} へ書き出しました。` | `「${path}」へ書き出しました。` |
| `run.stageDone` | `${stage}を終えました（${count} 件）。` | `${stage}を終えました（${count}件）。` |
| `run.stageFailed` | `${stage}に失敗しました。${reason}` | `${stage}を完了できませんでした。` (the reason is logged as its own indented line; mark the line `product-ui: ignore C6 run log line`) |
| `run.scanDone` | `走査を終えました（資産 ${count} 件）。` | `走査を終えました（資産 ${count}件）。` |
| `command.showGraph` | 呼出関係図を開く | 呼び出し関係図を開く |
| `modal.confirmDiscardBody` | 閉じると、この資産への編集は失われます。 | 閉じると、この資産の変更は失われます。 |
| `rules.searchLabel` | ID・名前・概要で絞り込む | ルールをID・名前・概要で絞り込む |
| `rules.enabledLabel` | `${id} を有効にする` | `${id} の有効・無効` |
| `rules.detailUnknown` | このルールはカタログにありません。 | このルールは見つかりません。 |
| `customRules.paneRaw` | 直接編集 | JSON |
| `customRules.paneLabel` | 編集の仕方 | 編集方法 |
| `customRules.rawLabel` | custom 配列の JSON | custom 配列のJSON |
| `customRules.rawInvalid` | `JSON として読めません。${detail}` | `JSONとして読めません。${detail}` |
| `customRules.add` | ルールを足す | ルールを追加 |
| `customRules.remove` | このルールを消す | このルールを削除する |
| `customRules.validate` | 検証する | 検証 |
| `customRules.save` | 保存する | 保存 |
| `customRules.validationFailed` | 解析エンジンがこの内容を受け付けませんでした。 | 解析エンジンはこの内容を受け付けませんでした。 |
| `customRules.fieldMissingClause` | 欠けていると指摘する句 | 欠けていると検出する句 |
| `customRules.fieldIgnoreCase` | 大文字小文字を区別しない | 大文字と小文字を区別しない |
| `customRules.fieldOnEveryPath` | 全経路で検査を求める | 全経路での検査を求める |
| `customRules.listHint` | 読点または改行で区切ります。 | カンマ・読点・改行のいずれかで区切ります。 |
| `customRules.messageHint` | `${match} と書くと、一致した文字列に置き換わります。` | `「${match}」と書くと、一致した文字列に置き換わります。` |
| `customRules.problem.idDuplicate` | このIDは他のルールと重なっています。 | このIDは他のルールで使われています。 |
| `settings.theme` | 配色 | 配色テーマ |
| `settings.copybookAdd` | パスを足す | パスを追加 |
| `settings.copybookRemove` | このパスを消す | このパスを削除 |
| `settings.copybookMissing` | このフォルダは見つかりません。 | unchanged text; rendered as a warning, not `ci-form__error` (1.6 removes the save it never blocked) |
| `settings.fixOutDirNote` | 原本は書き換えません。 | 資産フォルダの外へ書き出します。 |
| `import.title` | 端末から取り込む | 端末からの取り込み |
| `import.paste` | 端末から複写した本文 | 端末から貼り付けた本文 |
| `import.columnsNote` | 端末の表示桁で数えます（1起点・両端を含む）。 | 端末の表示桁で数えます（1始まり・両端を含む）。 |
| `import.columnsInvalid` | 終了桁は開始桁以上にします。 | 終了桁は開始桁以上にしてください。 |
| `import.lineCount` | `${count} 行` | `${count}行` |
| `import.saving` | 保存しています… | 取り込んでいます… |
| `import.cancel` | やめる | キャンセル |
| `import.encodingNote` | 保存はUTF-8で行います。 | UTF-8で取り込みます。 |
| `import.exists` | `${relPath} は既にあります。上書きすると元の内容は失われます。` | `「${relPath}」は既にあります。` |
| `import.saved` | `${relPath} を書き出しました（${count} 行）。再解析すると一覧に出ます。` | `「${relPath}」を取り込みました（${count}行）。もう一度解析すると一覧に出ます。` |
| `import.previewEmpty` | 本文を貼り付けると、ここに切り出した結果が出ます。 | deleted (1.13) |

Title-bar progress: `TitleBar.tsx:39` renders `text.run.scan` / `text.run.lint` / `text.run.sqlLint`
for the current stage in place of `status.stage`.

## Appendix C — Sentence shapes (for `docs/rule-text.md`)

Replaces the "Sentence shapes" list and the 文体 decision.

```markdown
## Register

文体 is 敬体 in every field, in the shape Japanese compiler diagnostics use
(javac 「シンボルを見つけられません」, MSVC 「'x': 定義されていない識別子です。」,
GCC 「'x' が宣言されていません」, Enterprise COBOL 「"X" は定義されていませんでした。」).
The GUI is 敬体 as well, so engine text and chrome read as one voice.

## Sentence shapes

- **name** — a noun phrase naming the defect. No 「〜の検出」「〜の回避」「〜の確認」
  「〜からの逸脱」 suffix. Full-width parentheses; a space between a reserved word
  and kana, as the body text writes it (「ALTER 文」「WHEN OTHER 句」).
- **summary** — one sentence: 「〜を検出します。」
- **rationale** — the consequence, one or two sentences: 「〜ます。」
- **detection** — the condition, then the exclusions: 「〜を検出します。〜は対象外です。」
  It names what the reader can check by hand, never the module, the signal
  name or the analysis (到達定義解析・区間値域解析・制御フローグラフ).
- **remedy** — one or two sentences in 「〜してください。」. `SarifWriter.helpTextOf`
  concatenates it behind 「直し方: 」, so keep it short.
- **fix description** — 「〜を挿入します」「〜を付けます」.
- **code-flow label** — a noun phrase or one short clause, no 「。」 inside; the
  role in parentheses: 「宣言（VALUE 句がなく初期値は不定）」.

## Message shape

A finding message is a diagnostic, not a paragraph. The problems table shows its
first ~14 full-width characters in the 内容 cell and the whole of it in the detail
pane, with the rule name already displayed beside it and the remedy already
displayed below it. What survives the ellipsis is the message.

- **Headline.** The message opens with the identifier the reader must look for —
  the data item, paragraph, section, cursor, map, JCL step or file name — followed
  by its state in 〜です / 〜ています / 〜されていません / 〜ありません. Nothing precedes
  the identifier: no rule-name restatement (「PERFORM 文が…」), no category prefix
  (「カーソル 」「機密項目 」「符号なし項目 」), no line number, no 「参照する」. Where
  the finding has no identifier, the statement's reserved word stands in its place
  (`MOVE`, `EVALUATE`, `EXEC CICS READ`).
- **Length.** The headline ends at its first 「。」 within 24 full-width units,
  and the identifier is complete within the first 14. A rule that cannot fit its
  identifier there is naming the wrong thing first.
- **Separator.** 「。」 only. No colon-introduced list in the headline; a list of
  matched items goes after the first 「。」 or into the code-flow steps.
- **Sentences.** Two at most: the headline, then the consequence in 「〜ます。」.
- **Never in a message.** The remedy — 直し方 is a field, `SarifWriter.helpTextOf`
  already puts it in the SARIF help text and the GUI shows it under the message.
  Also never: the rule name restated, the analysis that found it, and the reason
  the heuristic is trustworthy.
- **To the code-flow steps instead.** Every intermediate location and its role:
  the declaration and its PICTURE, each statement that sets the item, the point
  where a status is overwritten, the statement control jumps from. The message
  says what is wrong; the steps say along which path.
- **Hedging.** 「〜得ます」 at most once, and never inside the headline. A
  disjunction the analysis could resolve (「超え得るか、0 以下になり得る」) is the
  analyser talking about itself; pick the branch that matched.
- **Line references.** 「N行」, only where a second location is essential
  (「（宣言 12行）」「（119〜124行）」); the finding's own line is the 行 column.
  One half-width space before the number when it follows kana or kanji, none
  between the number and 行.
- **Column positions** are 桁 (「8〜72桁」「第 7 桁」); digits stay けた
  (けた数, けたあふれ).
```

## Appendix D — Proposed message per rule

Java expressions derived from the engine audit's proposals, moved to 敬体 under D1; `…` marks a
variable the rule already computes. Each is checked against the rule's tests and
`samples/expected-results.md` before it lands. R021's consequence changes meaning (CICS's default
action without RESP is an abend, not silent continuation); confirm against the rule's rationale
before adopting.

| Rule | Proposed message |
|---|---|
| R001 | `var + " を未設定のまま参照しています。値を設定する " + joinLines(setLines) + " を通らない経路があります（宣言 " + decl + "行）。"` — `decl` is computed inside the `support.item(var).ifPresent` lambda today (lines 134-139); hoist it (an `Optional<Integer>` from `support.item(var).map(...)`) before the message is assembled, and omit the parenthesis when it is empty |
| R002 | `item.name() + " は手続き部のどの文からも参照されていません（" + section + " SECTION の宣言）。"` |
| R003 | `"MOVE " + sender + " TO " + receiver + " で" + lost + "が切り捨てられます。実行時エラーも警告も出ません。"` |
| R004 | `receiver + " への " + verb + " 文に ON SIZE ERROR 句がありません。結果がけた数を超えても、けたあふれが検知されません。"` |
| R005 | `"表 " + tableName + " の添字が OCCURS " + max + " の範囲を" + (below ? "下回り得ます" : "超え得ます") + "。表の外の記憶域を参照します。"` |
| R006 | `arg + "（USAGE " + usage + "）を表 " + table + " の添字に使っています。参照のたびに二進数へ変換されます。"` |
| R007 | `hit + " に範囲外の段落 " + holder.name() + " から GO TO で入っています。PERFORM " + first + " THRU " + last + " の入口を経由しません。"` |
| R008 | `perform.targetProcedure() + " を PERFORM する文に THRU 句がありません。"` |
| R009 | `"GO TO " + rawTarget + " が節 " + holderSection + " から節 " + targetSection + " へ制御を移します。制御の流れを節の内側だけでは追えません。"` |
| R010 | `procedure.name() + " の ALTER 文が GO TO 文の移行先を実行時に書き換えます。"` |
| R011 (statement) | `verbOf(statement) + " 文（" + line + "行）に制御が到達しません。" + cause` (cause as its own sentence: 「直前の 〈verb〉（〈at〉行）で制御が移ります。」) |
| R011 (paragraph) | `"段落 " + procedure.name() + " を呼ぶ PERFORM・GO TO がありません。前の段落から制御が移る経路もなく、実行される機会がありません。"` |
| R012 | `String.join(", ", condVars) + " が PERFORM UNTIL の本体で更新されていません。ループから抜けられません。"` |
| R013 | `"EVALUATE " + subject + " に WHEN OTHER 句がありません。一致しない値は処理を受けずに通り抜けます。"`; without a resolvable subject `"EVALUATE 文（" + line + "行）に WHEN OTHER 句がありません。…"` |
| R014 | `"節 " + name + " の末尾に EXIT 文がありません。次の節 " + next + " へ制御が移ります。"` |
| R015 | `redefiner.name() + "（" + redefLen + "バイト）が元の項目 " + original.name() + "（" + origLen + "バイト）を超えています。隣接する領域を上書きします。"` |
| R016 (STRING) | `receiver + "（" + capacity + "バイト）に STRING の結果 " + total + "バイトが収まりません。あふれた分が切り捨てられます。"` |
| R016 (UNSTRING) | `sourceName + "（" + sourceLen + "バイト）が受け取り側項目の合計 " + receiverTotal + "バイトを超えています。分割結果を保持しきれません。"` |
| R017 | `var + " を " + verb + "（" + line + "行）の後で検査していません。" + until + "ため、入出力の失敗が検知されません。"` (the `until` clauses stay 連体形 before ため) |
| R018 | `"SQLCODE を EXEC SQL " + dml + "（" + span + "）の後で検査していません。" + until + "ため、更新の失敗が検知されません。"` |
| R019 | `cursor + " が OPEN のまま CLOSE されていません。"` |
| R020 | `String.join(", ", flagged) + " を検証せずに動的SQL文へ組み込んでいます。SQL インジェクションになり得ます。"` |
| R021 | `"EXEC CICS " + cicsVerb(block) + " に RESP・RESP2 がありません。応答コードを検査できず、異常時は既定の異常終了になります。"` |
| R022 | `model.programId() + " は EXEC CICS RETURN を持たずに終端します。疑似会話の制御が CICS に戻りません。"` |
| R023 | `duplicate.name() + " が重複して宣言されています（最初の宣言は " + firstLine + "行）。PERFORM 文の移行先が構文上あいまいになります。"` |
| R024 | `value.trim() + " がコピー句 " + copybookName + " に一件も出現しません。REPLACING の置換が起きません。"` |
| R025 (condition) | `operand + " どうしを比較しています。条件が常に同じ結果になります。"` |
| R025 (COMPUTE) | `expression + " は常に定数になります。"` |
| R026 | `keyword + " に文字定数で資格情報が書かれています。"` (keyword = the matched credential identifier, computed at line 75 and discarded today) |
| R027 | `String.join(", ", exposed) + " をマスキングせずに " + sinkVerb + " へ渡しています。機密情報が露出します。"` |
| R028 | `receiver + "（符号なし）に " + verb + " の結果が負で格納され得ます。符号が失われ、絶対値になります。"` |
| R029 | `"CALL " + callee + "（" + line + "行）の後で RETURN-CODE を検査していません。呼び出し先の失敗に気づかないまま後続が進みます。"` |
| R030 | `"ステップ " + step.name() + " に COND パラメーターがありません。先行ステップの異常終了後も実行されます。"` |
| R031 | `"マップ " + map + " が BMS のマップ定義にありません" + (mapset == null ? "" : "（マップセット " + mapset + "）") + "。"` |
| S001 | `"SELECT * で全列を取得しています。表の構造の変更に弱く、不要な列まで転送します。"` |
| S002 (predicate) | `String.join(" / ", predicates) + " は索引で絞り込めません。全表走査になります。"` |
| S002 (function) | `String.join(" / ", functions) + " は列を関数・CAST で包んでいます。索引で絞り込めません。"` |
| S004 | `cursor.cursorName() + " に FOR READ ONLY がありません。更新可能カーソルとして行ロックを取ります。"` |

Code-flow labels (3.3): R001 「宣言（VALUE 句がなく初期値は不定）」 / `var + " に値を設定する文（この文を通らない経路がある）"`;
R017 `verb + " の実行（" + var + " に入出力状態が設定される）"` / 「次の入出力（〈var〉が上書きされる）」;
R018 `dml + " の実行（SQLCODE が設定される）"` / 「次の SQL（SQLCODE が上書きされる）」.
