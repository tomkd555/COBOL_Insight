# COBOL Insight — UI Design Brief

This is a self-contained brief for **Claude Design** to produce the visual and UX design of the COBOL Insight desktop application. It is the only material the designer receives, so every fact needed is stated inline here. No other document is referenced.

**Language convention.** This brief is written in English for efficiency, but **all product UI text, labels, menus, buttons, tooltips, and messages are Japanese**. The canonical screen names are Japanese; each is kept in Japanese with an English gloss so the designer can label the actual UI in Japanese. Source code shown in the app, and much data in it, is also Japanese (including double-byte / DBCS heritage text).

**What Claude Design is asked to deliver.** Visual and interaction design for the eight screens below: layout, visual hierarchy, component design, color, typography, iconography, spacing, and interaction states (empty / running / results / error). Not asked for: application logic, data schemas, or engineering decisions beyond what an Electron + React desktop shell naturally implies.

---

## 1. Product context

**COBOL Insight** (Japanese reading: コボル・インサイト) is a Windows 11 desktop application for **static analysis of legacy mainframe COBOL assets**. It reads source without executing it and reveals call relationships, latent defects, embedded-SQL improvement opportunities, fix suggestions shown as diffs, and verbatim translations of COBOL into Python or Java. The analysis targets are IBM Enterprise COBOL, MVS JCL, embedded Db2 SQL, COBOL copybooks, and CICS/BMS elements.

The name conveys "insight into the internals of an asset": defect detection, SQL advice, and quality improvement through static analysis.

**Who uses it.** Three user roles, all working with aging mainframe COBOL where knowledge is concentrated in a shrinking pool of experts:

- **保守担当者 (Maintenance staff)** — during incident investigation or feature changes, need to grasp a program's callers, callees, and data flow quickly.
- **移行調査担当者 (Migration survey staff)** — inventory the current assets and their dependencies to scope migration and impact; may not be fluent in COBOL and lean on the verbatim translation to read the logic.
- **品質管理・レビュー担当者 (QA / review staff)** — mechanically surface latent defects and SQL issues to make reviews more complete and objective.

**Platform and locale.**
- Windows 11 desktop application. Runs fully **locally and offline**; it never performs any network communication and never sends source or results off the machine.
- Delivered as an **Electron shell (TypeScript + React, Chromium bundled)**. The analysis runs as a separate **Java (JDK 21) command-line subprocess**; the GUI reads results back from a local **SQLite database and JSON files** (no sockets, no server). This is invisible to the user but explains one visible reality: **analysis is a real, potentially long-running background job** with running/progress and completion states the UI must represent.
- **Japanese UI throughout.** No login, account, cloud, sharing, or billing surfaces exist — nothing connects outward.
- Version 1 is **fully deterministic and rule-based**. There are **no LLM / AI features** anywhere in the UI; the same input always yields the same output.

---

## 2. Design scope and constraints

**In scope for the design:** the visual and interaction design of the eight screens in section 4 — layout and grid, information hierarchy, component library (tables, trees, graph canvas, code panes, diff panes, filter bars, dialogs, status/progress indicators), color system, typography (including a monospace family for code), severity color coding, and the empty / running / results / error states of each screen.

**Hard constraints:**

- **Desktop-first, single primary breakpoint ~1920×1080.** This is a professional tool used at a desk on a large monitor. It should remain usable when the window is smaller, but mobile and touch layouts are not required.
- **Dense, data-heavy UI.** Findings lists, SQL lists, and call graphs can be large. Favor information density, sortable/filterable tables, resizable split panes, and efficient scanning over decorative whitespace. This is closer to an IDE / analysis console than a marketing site.
- **Monospace source rendering with column awareness.** COBOL sources are **fixed-format, 80 columns**. Rendering must be monospace and column-accurate (see section 5). Diff and translation panes are also monospace.
- **Japanese labels and Japanese content.** Design typography and spacing for Japanese text, and for source content that mixes Japanese (DBCS) with ASCII.
- **Local-only.** No sign-in, no avatars, no cloud status, no notifications-to-server, no share links. The only "connections" are the local file system (importing assets, writing outputs) and the local analysis subprocess.
- **Offline asset constraint (for the designer's own artifacts):** the shipped app bundles all its libraries and fonts locally and never loads from a CDN or external font service. Keep the design realizable with self-contained, bundled assets.

**Tone.** Professional, calm, trustworthy, legible under long working sessions. Light theme is the expected default for this class of internal tool; a dark theme is a reasonable addition but not required by the source material.

---

## 3. Information architecture

The eight screens form a single-window desktop app with top-level navigation among the primary areas and cross-navigation from result screens into the source viewer.

**Entry point and the analysis lifecycle.** The **資産エクスプローラー (Asset Explorer)** is the starting point: the user imports folders/files, specifies character encoding, and **runs analysis** from here. Analysis is a background job; until it completes there are no results, so most other screens show an empty or "analysis running" state before the first successful run. After a run, results populate the graph, findings, SQL advice, and reports.

**Result-browsing screens and the shared jump target.** Three screens present analysis results and all cross-link into the same source view:

- **呼出関係図ビュー (Call-Graph View)** — selecting a node navigates to the source of that element.
- **指摘一覧ビュー (Findings List)** — selecting a finding navigates to the exact source line; it can also trigger report output.
- **SQL助言ビュー (SQL Advice View)** — selecting an item navigates to the corresponding program source line.

All three converge on the **ソースビューア (Source Viewer)**, which is the shared destination for "jump to source line." The Source Viewer itself supports line jumps and copybook expansion but is primarily a target reached from the other views.

**Standalone primary screens.** **diffビュー (Diff View)** presents a fix suggestion as a before/after diff with preview and apply modes. **レポート出力 (Report Output)** produces exportable reports (reachable directly and from the Findings List). **設定 (Settings)** configures analysis defaults. These are reached through top-level navigation.

**Navigation model summary:**

```
資産エクスプローラー ──run analysis──▶ (results populate)
        │
        ├─▶ 呼出関係図ビュー ──node select──┐
        ├─▶ 指摘一覧ビュー ──finding select─┼─▶ ソースビューア (jump to line)
        │        └──────────────────▶ レポート出力
        ├─▶ SQL助言ビュー ──item select────┘
        ├─▶ diffビュー (preview / apply a fix suggestion)
        ├─▶ レポート出力
        └─▶ 設定
```

Only the cross-links stated in the screen definitions are real; the design should not invent additional deep links beyond those.

---

## 4. Screen specifications

Each screen is listed by its canonical Japanese name plus an English gloss. Purpose, primary objects, key operations, data characteristics, and the four required UI states (empty / analysis running / results present / error) are given for each. The key operations are the authoritative set for each screen — do not add operations beyond these.

### 4.1 資産エクスプローラー — Asset Explorer

- **Purpose.** List the imported assets in a hierarchy by type and storage location, and serve as the starting point for analysis.
- **Primary objects.** Assets: files/members of JCL, COBOL programs, copybooks (コピー句), and dataset definitions.
- **Key operations.** Import folders/files; specify character encoding; run analysis; filter by name and by asset type.
- **Data characteristics.** Potentially many files across a deep folder tree; asset type must be visually distinguishable (JCL vs COBOL vs copybook vs dataset definition). Encoding needs a per-file/per-dataset affordance (see section 5 — auto-detected encodings plus manual override, with a decode preview).
- **UI states.**
  - *Empty:* no assets imported yet — prominent import affordance.
  - *Analysis running:* run has been triggered; show progress/running indication and keep the asset list visible.
  - *Results present:* assets listed, analyzed items indicated, analysis can be re-run.
  - *Error:* import failure or an asset whose parse failed (failed files are reported, not hidden; analysis of other files continues).

### 4.2 呼出関係図ビュー — Call-Graph View

- **Purpose.** Interactively draw the call relationships JCL → program → subroutine → dataset.
- **Primary objects.** The call-relationship graph. **Node types:** job (ジョブ), step (ステップ), program (プログラム / PROGRAM-ID), paragraph (段落), dataset (データセット), Db2 table (Db2表), external utility (外部ユーティリティ: DFSORT / IDCAMS / IEBGENER, plus PL/I and assembler external leaves), unresolved (未解決), transaction (トランザクション), and BMS map (BMSマップ). **Edge types:** EXEC (JCL step → program), CALL (static and dynamic), dataset linkage (step-to-step and job-to-job data hand-off), plus CICS **transaction-transition edges** (from XCTL / LINK / START / RETURN TRANSID, including pseudo-conversational transitions) and **BMS map-reference edges** (from SEND MAP / RECEIVE MAP).
- **Key operations.** Expand/collapse nodes; filter by node type; select a node to navigate to its source; export the graph to SVG / PNG.
- **Data characteristics.** Graphs can be large (many nodes and edges), so partial expansion, type filtering, and level-of-detail control are essential; a full one-shot render of the whole graph is a separate concern handled outside this interactive view. Edges carry a **resolution basis** (constant-derived, dataflow-derived, or unresolved); unresolvable dynamic-CALL targets appear as explicit "unresolved (dynamic)" boundary nodes carrying the variable name, and JCL IF/THEN/ELSE and COND branches are shown as "possible paths." The design must let the graph honestly show incompleteness rather than imply full resolution.
- **UI states.**
  - *Empty:* no analysis has run — nothing to draw.
  - *Analysis running:* graph is being built; show running indication.
  - *Results present:* interactive graph with filter/expand/export controls.
  - *Error:* nodes that could not be analyzed appear as "unanalyzable" nodes in the graph rather than being dropped.

### 4.3 指摘一覧ビュー — Findings List

- **Purpose.** List bug-detection results and organize them by severity and type.
- **Primary objects.** Findings — each with file, line number, defect type, severity, and rationale.
- **Key operations.** Filter and sort by severity, by type, and by file; select a finding to jump to the corresponding source line; output a report.
- **Data characteristics.** There are 31 bug-detection rule types (R001–R031); across a real asset set the list can hold hundreds or thousands of findings, so fast filtering, sorting, and severity grouping matter. Severity uses the exact vocabulary in section 5. Each finding row should surface file + line, defect type/rule, severity, and a rationale.
- **UI states.**
  - *Empty:* two distinct cases to design for — analysis not yet run, versus analysis run with zero findings (a clean result).
  - *Analysis running:* detection in progress.
  - *Results present:* filterable/sortable findings table.
  - *Error:* parse-failure findings appear in the list as error-level entries.

### 4.4 ソースビューア（指摘ハイライトと対訳ペイン） — Source Viewer (finding highlight + translation pane)

- **Purpose.** Display source, highlighting flagged locations, with a Python/Java verbatim-translation pane placed alongside.
- **Primary objects.** Source lines, and the findings and translations linked to those lines.
- **Key operations.** Highlight flagged locations; place the translation pane side-by-side with per-line mutual linking; line jump; copybook (コピー句) expansion display.
- **Data characteristics.** Source is fixed-format 80-column COBOL rendered in monospace with column awareness (section 5). The translation pane shows Python or Java generated line-by-line; the COBOL line ↔ generated line correspondence is many-to-one and one-to-many, so hovering/selecting a line on one side should highlight its counterpart(s) on the other, and constructs that cannot be translated verbatim (notably GO TO and REDEFINES) carry a note and a visualization of the multi-line grouping. Copybook expansion shows COPY/REPLACE-expanded content inline, mapped back to its original copybook location.
- **UI states.**
  - *Empty:* no source selected (this screen is normally reached by a jump from another view).
  - *Analysis running:* translation/analysis for the file not yet available.
  - *Results present:* source with highlights and the linked translation pane.
  - *Error:* a file that failed to parse shows the failure in context.

### 4.5 SQL助言ビュー — SQL Advice View

- **Purpose.** List embedded SQL statements and their optimization advice.
- **Primary objects.** SQL statements and their optimization advice.
- **Key operations.** List the SQL statements; show the advice content; navigate to the corresponding program source line.
- **Data characteristics.** Embedded Db2 SQL (EXEC SQL … END-EXEC) extracted from COBOL. There are 6 SQL advisory rules (S001–S006) covering topics such as avoiding SELECT *, non-SARGable predicates, functions applied to indexed columns, cursor declaration/cleanup, FETCH FIRST, and OPTIMIZE FOR. Advice items carry the same severity vocabulary as findings. The advice is syntax-level only (it does not depend on a live database), so present it as static guidance, not as live query metrics.
- **UI states.**
  - *Empty:* no analysis run, or no embedded SQL found.
  - *Analysis running:* advice being computed.
  - *Results present:* statement list with advice and jump-to-source.
  - *Error:* SQL that could not be parsed indicated as such.

### 4.6 diffビュー — Diff View

- **Purpose.** Show a fix suggestion as a before/after diff and confirm acceptance or rejection.
- **Primary objects.** A fix suggestion: the pre-change source and the post-change source.
- **Key operations.** Show the left/right diff; confirm accept/reject of the fix suggestion; switch between **preview** (diff confirmation) and **apply** (write the fixed source to a separate directory) modes.
- **Data characteristics.** The **original source is never modified.** Preview shows the diff for confirmation; apply writes the fixed source under a separate `fix/` directory that preserves the original relative path structure — the original files are left untouched. Accept/reject is always a human decision. Fix suggestions are generated only for four rules — **R004** (add ON SIZE ERROR), **R017** (insert FILE STATUS check), **R018** (insert SQLCODE check), and **R021** (insert CICS RESP/RESP2 check) — so this view handles those cases; other findings are advisory only and have no diff. When a fix falls inside a copybook, the diff still shows the change **and** must accompany it with the list of all programs that include that copybook, so the user sees the impact scope. Diff is monospace, fixed-format-aware, and can include multi-byte/DBCS content.
- **UI states.**
  - *Empty:* no fix suggestion selected.
  - *Analysis running:* fix being generated / re-parsed for verification.
  - *Results present (preview):* side-by-side diff with accept/reject and the mode switch; copybook cases show the impacted-programs list.
  - *Error:* a fix that fails verification is surfaced rather than silently applied.

### 4.7 レポート出力 — Report Output

- **Purpose.** Output the analysis results in various formats.
- **Primary objects.** Reports aggregating call relationships, findings, and SQL advice.
- **Key operations.** Choose output format (HTML / text); choose output scope; write out.
- **Data characteristics.** A report bundles the call-relationship summary, the findings list, and the SQL advice into one document. The user picks format and scope before writing to a local file.
- **UI states.**
  - *Empty:* no results to report yet.
  - *Analysis running:* results not yet complete.
  - *Results present:* format and scope selectors plus a write/export action; optional preview of what will be written.
  - *Error:* write failure (e.g., path not writable) reported clearly.

### 4.8 設定 — Settings

- **Purpose.** Configure the default behavior of analysis.
- **Primary objects.** Setting items.
- **Key operations.** Set the default character encoding and the copybook (コピー句) search paths; enable/disable rules; set the severity threshold.
- **Data characteristics.** Rule enable/disable spans the full rule set — 31 bug-detection rules (R001–R031) and 6 SQL advisory rules (S001–S006), all enabled by default and individually switchable — so the rule list needs grouping (by category/severity) and search. Copybook search paths are an ordered list (order determines which same-named copybook wins). The severity threshold selects the minimum severity to surface, using the vocabulary in section 5.
- **UI states.**
  - *Empty:* first run — shows defaults.
  - *Analysis running:* settings may be read-only while a run is in progress.
  - *Results present:* configured values shown and editable.
  - *Error:* invalid input (e.g., a non-existent search path) flagged inline.

---

## 5. Cross-cutting elements

**Severity indication.** Findings and SQL advice carry one of four severity levels. Use these exact Japanese labels as the canonical vocabulary; a consistent color + shape encoding should distinguish them at a glance in tables, graph annotations, and the source viewer.

| Severity (canonical, Japanese) | English gloss | Notes |
|---|---|---|
| 高 | High | Most serious defects. |
| 中 | Medium | |
| 低 | Low | |
| 警告 | Warning | Code-smell-class items (e.g., GO TO usage, both operands of a binary operator identical). |

A configurable **severity threshold** (in Settings) filters which items are surfaced, so the severity encoding must also read well when the set is filtered.

**Source-line jump pattern (shared).** Three screens — 呼出関係図ビュー (node select), 指摘一覧ビュー (finding select), and SQL助言ビュー (item select) — navigate to a specific line in the ソースビューア. Design this jump as one consistent pattern: the target line is scrolled into view and highlighted, and the origin context is clear. This is the app's primary cross-screen motion.

**Copybook (コピー句) expansion display.** COBOL programs pull in copybooks via COPY (with optional REPLACE/REPLACING). The Source Viewer can expand copybook content inline, mapping expanded lines back to their original copybook location. When a fix suggestion touches a copybook, the Diff View additionally lists every program that includes that copybook, communicating that one change ripples across many programs.

**Fixed-format 80-column source rendering.** All source, diff, and translation panes are monospace and column-aware. COBOL fixed format divides each line into fixed regions: sequence-number area (columns 1–6), indicator (column 7, e.g., `-` for continuation, `*` for comment), the code body / area B (columns 8–72), and the identification area (columns 73–80). Column guides or a ruler help users read alignment, and rendering must remain correct when lines mix single-byte and double-byte (DBCS) characters.

**Character encoding and decode preview.** On import, encoding is either auto-detected (Shift_JIS and UTF-8) or estimated (EBCDIC CP930/939), with a **manual per-file / per-dataset override** that takes precedence. Immediately after decoding, a **preview** lets a human confirm the decoded content looks right and re-specify the code page if not. The Asset Explorer and Settings need affordances for this; a preview surface is required.

**Export actions.** Two export families: the call graph exports to **SVG / PNG**; reports export to **HTML / text**. Present these as clear, discoverable actions on their respective screens.

**Long-running analysis: progress, running, and cancel.** Analysis runs as a background job and can take time. The UI must clearly represent the running state with progress indication, keep the app responsive while it runs, allow the user to cancel a run in progress, and transition cleanly to results or to an error state. Partial results are legitimate: if some files fail to parse, those failures are shown as error-level findings and as "unanalyzable" nodes, while the rest of the results still appear — the design must accommodate a partially-successful outcome, not only all-or-nothing.

---

## 6. Out of scope

The following are explicitly not part of this design engagement:

- **Application logic and internals.** Data schemas, subprocess orchestration, parsing, and analysis behavior are fixed and not a design concern.
- **Operations beyond those listed per screen.** Each screen's key operations in section 4 are the complete set; do not design new features, screens, data, or navigation the specifications do not define.
- **Any AI / LLM features.** Version 1 is fully deterministic; there is no generative, assistant, or suggestion-by-model surface to design.
- **Any network, account, or cloud features.** No login, sign-up, user profiles, sharing, collaboration, sync, notifications-to-server, telemetry, or billing. The app is local and offline by definition.
- **Branding beyond the product name.** Use the name **COBOL Insight**; no logo suite, brand guidelines, or marketing identity is required or provided.
- **Mobile / touch layouts.** Desktop-first only, as stated in section 2.
