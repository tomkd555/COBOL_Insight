/*
 * The offscreen render smoke. It draws the built renderer (out/renderer/index.html) with Electron's
 * offscreen rendering and checks what jsdom cannot see.
 *
 * The checklist, numbered as the plan numbers it:
 *
 *   1. the shell is built from four regions (activity bar, side bar, editor area, panel)
 *   2. choosing a folder runs the analysis and fills the asset tree with kind badges
 *   3. opening an asset starts Monaco and its lines land on distinct y coordinates
 *      (without 'unsafe-inline' in style-src every line collapses onto the same y)
 *   4. typing into the body raises the unsaved mark on that tab
 *   5. the problems rows open the asset's tab
 *   8. the rules view lists its toggles and a change round-trips through the rule file
 *  16. the import dialog cuts the pasted columns and writes a source file into the asset folder
 *   9. a 200% zoom produces no horizontal scrollbar (never two scroll directions at once)
 *  10. the console carries no error and no CSP refusal ("Refused to ...")
 *  11. Ctrl+Shift+P opens the palette, typing filters it, and Enter runs the command
 *  12. a CP930 asset opens as readable text and its identification area is marked at the
 *      byte-correct character, not the 73rd character
 *  13. switching away from an edited tab and back keeps the edit, and Ctrl+Z takes it back
 *  14. saving a file that changed underneath raises the conflict dialog instead of overwriting
 *  15. a finding whose rule has a fix offers a quick fix, which opens the diff tab
 *
 * TODO 6: Cytoscape builds a canvas and paints nodes onto it (opaque pixels are present).
 * TODO 7: the execution-order list orders its children by the engine's seq.
 * TODO 17: the report view renders the engine's HTML.
 * TODO 18: the transpile view lines the generated code up with the COBOL.
 *
 * Elements are selected by data-testid: selecting by visible text or class name would break this
 * smoke every time the wording or the styling changed. The engine is never launched — the preload is
 * replaced with smoke/fake-preload.cjs, whose canned data carries the screens to their results
 * state. It loads with the production settings, contextIsolation:true and sandbox:true. A failed
 * check exits non-zero.
 *
 * Run: npm run smoke:render (electron-vite build, then electron smoke/render.cjs)
 */

const { app, BrowserWindow } = require("electron");
const { existsSync } = require("node:fs");
const { join } = require("node:path");

/** The ceiling on each wait. Generous, because it covers rendering start-up. */
const WAIT_TIMEOUT_MS = 30000;

const RENDERER_HTML = join(__dirname, "..", "out", "renderer", "index.html");
const FAKE_PRELOAD = join(__dirname, "fake-preload.cjs");

/** The check results. A single ok=false exits non-zero. */
const results = [];
/** The errors and CSP refusals the console carried. */
const consoleErrors = [];

/**
 * Messages Chromium reports on the error channel that are not faults.
 *
 * The ResizeObserver notice is raised when an observer's callback resizes what it observes within
 * the same frame, which is exactly what Monaco's automaticLayout does when an editor is mounted into
 * a tab that is itself still being laid out. The specification calls for the remaining work to be
 * delivered on the next frame, and it is; nothing is lost.
 */
const BENIGN = [/^ResizeObserver loop /];

function record(name, ok, detail) {
  results.push({ name, ok, detail });
  console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail === undefined ? "" : ` — ${detail}`}`);
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** Evaluates an expression in the page. The CSP does not block executeJavaScript. */
function evaluate(win, expression) {
  return win.webContents.executeJavaScript(expression, true);
}

/** Waits until the expression returns a truthy value and returns it; throws when it never does. */
async function waitUntil(win, expression, description) {
  const deadline = Date.now() + WAIT_TIMEOUT_MS;
  let last;
  while (Date.now() < deadline) {
    last = await evaluate(win, expression);
    if (last) {
      return last;
    }
    await delay(120);
  }
  throw new Error(`never became true: ${description} (last value ${JSON.stringify(last)})`);
}

/** An expression that clicks the element with that data-testid; doubles as a wait for it. */
function clickTestId(testId, inner) {
  const selector = `[data-testid=${JSON.stringify(testId)}]${inner === undefined ? "" : ` ${inner}`}`;
  return `(() => {
    const target = document.querySelector(${JSON.stringify(selector)});
    if (target === null) return false;
    target.click();
    return true;
  })()`;
}

/** An expression returning how many elements match, or null at zero so it can be waited on. */
function countOf(selector) {
  return `(() => {
    const count = document.querySelectorAll(${JSON.stringify(selector)}).length;
    return count > 0 ? count : null;
  })()`;
}

/** Check 1: the four regions of the shell. */
async function checkShell(win) {
  const present = await waitUntil(
    win,
    `(() => {
      const ids = ['activitybar', 'sidepanel', 'editorarea', 'bottompanel'];
      const found = ids.filter((id) => document.querySelector('[data-testid="' + id + '"]') !== null);
      return found.length === ids.length ? found : null;
    })()`,
    "the four regions of the shell",
  );
  record("1. the shell is built from four regions", present.length === 4, present.join(" / "));
}

/** Check 2: choosing a folder through to the asset tree. */
async function checkAssetTree(win) {
  await waitUntil(win, clickTestId("select-folder"), "the folder picker");
  const rows = await waitUntil(win, countOf('[data-testid^="tree-"]'), "the asset tree");
  const badges = await evaluate(
    win,
    `document.querySelectorAll('[data-testid^="tree-"] .ci-badge').length`,
  );
  record(
    "2. the asset tree fills with kind badges",
    rows > 0 && badges >= 5,
    `${rows} rows, ${badges} badges`,
  );
}

/** The one editor of the group, reached through the handle vendor/monacoEditor publishes. */
const EDITOR = `window.ciMonaco.editor.getEditors()[0]`;

/** Opens one asset from the tree and waits until the editor is showing it. */
async function openAsset(win, path) {
  await waitUntil(win, clickTestId(`tree-${path}`), `the tree row for ${path}`);
  await waitUntil(
    win,
    `(() => {
      const editor = ${EDITOR};
      const model = editor === undefined ? null : editor.getModel();
      return model !== null && model.getValueLength() > 0 ? true : null;
    })()`,
    `the editor showing ${path}`,
  );
}

/** A fragment only the first asset's text carries, for telling which model the editor is showing. */
const SYK001_MARK = "SYK00110";

/**
 * Selects an already-open source tab and waits until the editor is showing that tab's model. The
 * model is swapped in an effect, so clicking the tab and typing straight away would type into the
 * model of the tab that was open before.
 */
async function activateSourceTab(win, path, mark) {
  await waitUntil(win, clickTestId(`tab-source:${path}`, "button"), `the tab for ${path}`);
  await waitUntil(
    win,
    `(() => {
      const model = ${EDITOR} === undefined ? null : ${EDITOR}.getModel();
      return model !== null && model.getValue().includes(${JSON.stringify(mark)}) ? true : null;
    })()`,
    `the editor showing ${path}`,
  );
}

/** Types one character at the start of the document, through Monaco's own input handling. */
function typeAtStart(text) {
  return `(() => {
    const editor = ${EDITOR};
    if (editor === undefined) return false;
    editor.focus();
    editor.setPosition({ lineNumber: 1, column: 1 });
    editor.trigger('smoke', 'type', { text: ${JSON.stringify(text)} });
    return true;
  })()`;
}

/**
 * Check 3: Monaco really lays its lines out. Without 'unsafe-inline' in style-src the editor's
 * generated stylesheet is refused and every line collapses onto the same y coordinate, which is a
 * regression no jsdom test can see.
 */
async function checkEditorLayout(win) {
  await openAsset(win, "cobol/SYK001.cbl");
  const layout = await waitUntil(
    win,
    `(() => {
      const lines = [...document.querySelectorAll('.monaco-editor .view-line')];
      if (lines.length < 3) return null;
      const tops = new Set(lines.map((line) => Math.round(line.getBoundingClientRect().top)));
      return { lines: lines.length, distinct: tops.size };
    })()`,
    "the editor's rendered lines",
  );
  record(
    "3. Monaco renders its lines at distinct y coordinates",
    layout.lines >= 3 && layout.distinct === layout.lines,
    `${layout.lines} lines, ${layout.distinct} distinct tops`,
  );
}

/** Check 4: typing raises the unsaved mark on the tab. */
async function checkDirtyMark(win) {
  await waitUntil(win, typeAtStart("X"), "typing into the editor");
  const marked = await waitUntil(
    win,
    `document.querySelector('[data-testid="dirty-source:cobol/SYK001.cbl"]') !== null`,
    "the unsaved mark",
  );
  record("4. typing raises the unsaved mark on the tab", marked === true);
}

/**
 * Check 13: the text model belongs to the tab, not to the editor, so switching away and back keeps
 * both the edit and the undo stack. The edit made by check 4 is the one carried across.
 */
async function checkEditSurvivesSwitch(win) {
  await openAsset(win, "cobol/SYK002.cbl");
  await activateSourceTab(win, "cobol/SYK001.cbl", SYK001_MARK);
  const kept = await waitUntil(
    win,
    `${EDITOR}.getValue().startsWith('X') ? 'kept' : null`,
    "the edit after switching back",
  );
  await evaluate(win, `${EDITOR}.trigger('smoke', 'undo', null)`);
  const undone = await waitUntil(
    win,
    `(() => {
      const clean = !${EDITOR}.getValue().startsWith('X');
      const mark = document.querySelector('[data-testid="dirty-source:cobol/SYK001.cbl"]');
      return clean && mark === null ? 'undone' : null;
    })()`,
    "the undo",
  );
  record(
    "13. an edit survives a tab switch and Ctrl+Z takes it back",
    kept === "kept" && undone === "undone",
  );
}

/**
 * Check 12: an EBCDIC CP930 asset opens as readable text, and its identification area is marked at
 * the character byte column 73 actually falls on. The fixture's third line holds double-byte
 * characters wrapped in shift bytes, so that is character 53 — anything counting characters instead
 * of bytes would mark character 73 or nothing at all.
 */
async function checkEbcdicColumns(win) {
  await openAsset(win, "encoding/SYKENC1_CP930.cbl");
  const marked = await waitUntil(
    win,
    `(() => {
      const model = ${EDITOR}.getModel();
      if (model === null || model.getLineCount() < 3) return null;
      const line = model.getLineContent(3);
      if (!line.includes('文字コード')) return null;
      const rendered = [...document.querySelectorAll('.monaco-editor .view-line')].find(
        (element) => element.textContent.replace(/\\u00a0/g, ' ').startsWith('      *  文字'),
      );
      if (rendered === undefined) return null;
      const areas = [...rendered.querySelectorAll('.ci-code__identification')];
      if (areas.length === 0) return null;
      return {
        expected: line.slice(53),
        marked: areas.map((area) => area.textContent.replace(/\\u00a0/g, ' ')).join(''),
        readable: line.includes('文字コード検証用'),
      };
    })()`,
    "the identification area of the CP930 fixture",
  );
  record(
    "12. a CP930 asset opens readable and its identification area starts at the right character",
    marked.readable === true && marked.marked === marked.expected && marked.expected !== "",
    `marked ${JSON.stringify(marked.marked)}, expected ${JSON.stringify(marked.expected)}`,
  );
}

/**
 * Check 14: a file that changed under the editor is not overwritten silently. The fake preload's
 * `touch` stands in for the outside edit by moving the file's stamp.
 */
async function checkSaveConflict(win) {
  await activateSourceTab(win, "cobol/SYK001.cbl", SYK001_MARK);
  await waitUntil(win, typeAtStart("Y"), "an unsaved edit");
  await waitUntil(
    win,
    `document.querySelector('[data-testid="dirty-source:cobol/SYK001.cbl"]') !== null`,
    "the unsaved mark on the asset about to be saved",
  );
  await evaluate(win, `window.cobolInsight.touch('cobol/SYK001.cbl')`);
  await evaluate(
    win,
    `window.dispatchEvent(new KeyboardEvent('keydown', { key: 's', ctrlKey: true, bubbles: true }))`,
  );
  const raised = await waitUntil(
    win,
    `(() => {
      if (document.querySelector('[data-testid="save-conflict"]') !== null) return { ok: true };
      // A notification instead means the save went through, which is the failure worth naming.
      const toast = document.querySelector('[data-testid^="toast-"]');
      if (toast !== null) return { ok: false, toast: toast.textContent };
      return null;
    })()`,
    "the conflict dialog",
  ).catch((error) => ({
    ok: false,
    reason: error.message,
  }));
  record(
    "14. saving over a changed original raises the conflict dialog",
    raised.ok === true,
    raised.ok === true ? undefined : JSON.stringify(raised),
  );

  if (raised.ok !== true) {
    return;
  }
  // Take the overwrite, so the later checks start from a saved file rather than a dialog.
  await waitUntil(win, clickTestId("save-conflict-overwrite"), "the overwrite button");
  await waitUntil(
    win,
    `document.querySelector('[data-testid="save-conflict"]') === null ? 'closed' : null`,
    "the dialog closing",
  );
}

/**
 * Check 15: a finding whose rule can be fixed offers the quick fix, and taking it opens the diff
 * tab for that asset. The canned lint result puts R004 — the one rule in the fake catalog with
 * hasFix — on line 10 of the first asset.
 */
async function checkQuickFix(win) {
  await activateSourceTab(win, "cobol/SYK001.cbl", SYK001_MARK);
  const started = await waitUntil(
    win,
    `(() => {
      const editor = ${EDITOR};
      const model = editor.getModel();
      const markers = window.ciMonaco.editor.getModelMarkers({ resource: model.uri });
      if (markers.length === 0) return null;
      if (!markers.some((marker) => marker.code === 'R004')) {
        return { ok: false, codes: markers.map((marker) => marker.code) };
      }
      editor.focus();
      editor.setPosition({ lineNumber: 10, column: 1 });
      // Through trigger rather than getAction: the code-action commands are contributed as editor
      // commands, which getAction does not list.
      editor.trigger('smoke', 'editor.action.quickFix', {});
      return { ok: true };
    })()`,
    "the quick fix action",
  );
  if (started.ok !== true) {
    record("15. a fixable finding offers a quick fix that opens the diff tab", false, JSON.stringify(started));
    return;
  }
  const chosen = await waitUntil(
    win,
    `(() => {
      const rows = [...document.querySelectorAll('.action-widget .monaco-list-row')];
      const titles = rows.map((row) => row.textContent.trim());
      // The first row is the group header ("Quick Fix"); the action itself follows it.
      const action = rows.find((row) => row.textContent.includes('修正案'));
      if (action === undefined) return null;
      action.click();
      return { titles };
    })()`,
    "the quick fix chooser",
  );
  const opened = await waitUntil(
    win,
    `document.querySelector('[data-testid="tab-fix:cobol/SYK001.cbl"]') !== null`,
    "the diff tab the quick fix opened",
  ).catch(() => false);
  record(
    "15. a fixable finding offers a quick fix that opens the diff tab",
    opened === true,
    `offered ${JSON.stringify(chosen.titles)}`,
  );
}

/** Check 5: the problems table and the route from a row into the source. */
async function checkFindings(win) {
  const rows = await waitUntil(win, countOf('[data-testid^="finding-"]'), "the problems rows");
  await waitUntil(
    win,
    `(() => {
      const row = [...document.querySelectorAll('[data-testid^="finding-"]')]
        .find((element) => element.textContent.includes('cobol/SYK002.cbl'));
      if (row === undefined) return false;
      row.click();
      return true;
    })()`,
    "a problems row",
  );
  const opened = await waitUntil(
    win,
    `document.querySelector('[data-testid="tab-source:cobol/SYK002.cbl"]') !== null`,
    "the tab the row opened",
  );
  record("5. a problems row opens the asset's tab", rows >= 3 && opened === true, `${rows} rows`);
}

/**
 * Check 8: the rules view. Its toggles are drawn from the engine's catalogue, and switching one off
 * has to travel out through the rule file and back through the catalogue, not merely flip a
 * checkbox on screen.
 */
async function checkRules(win) {
  await waitUntil(win, clickTestId("activity-rules"), "the rules view");
  const toggles = await waitUntil(win, countOf('[data-testid^="rule-toggle-"]'), "the rule toggles");
  const severities = await evaluate(
    win,
    `document.querySelectorAll('[data-testid^="rule-severity-"]').length`,
  );

  await waitUntil(win, clickTestId("rule-toggle-R001"), "the toggle of R001");
  const roundTripped = await waitUntil(
    win,
    `(() => {
      const box = document.querySelector('[data-testid="rule-toggle-R001"]');
      const written = window.cobolInsightSmoke.rulesFile().rules.R001;
      if (box === null || written === undefined) return null;
      return box.checked === false && written.enabled === false ? 'round-tripped' : null;
    })()`,
    "the toggle round trip through the rule file",
  );
  record(
    "8. a rules toggle round-trips through the rule file",
    toggles >= 3 && severities >= 3 && roundTripped === "round-tripped",
    `${toggles} toggles, ${severities} severity selects`,
  );

  // Leave the side bar on the explorer, which the later checks expect to be there.
  await waitUntil(win, clickTestId("activity-explorer"), "the explorer");
  await delay(200);
}

/**
 * The two editors the checks above never open: the custom-rule editor (its form is built from a
 * union of three detection shapes) and the settings screen. Neither is in the plan's numbered list;
 * this only proves they draw, since nothing else on this route would notice if they threw.
 */
async function checkRuleAndSettingsEditors(win) {
  await waitUntil(win, clickTestId("activity-rules"), "the rules view");
  await waitUntil(win, clickTestId("rules-open-custom"), "the custom-rule editor");
  await waitUntil(win, clickTestId("custom-add"), "the button that adds a rule");
  const form = await waitUntil(
    win,
    `document.querySelector('[data-testid="custom-rule-U001"]') !== null`,
    "the form of the added rule",
  );
  await waitUntil(win, clickTestId("custom-pane-raw"), "the raw pane");
  const raw = await waitUntil(
    win,
    `(() => {
      const area = document.querySelector('[data-testid="custom-raw"]');
      return area === null ? null : area.value;
    })()`,
    "the raw JSON of the custom rules",
  );

  await waitUntil(win, clickTestId("activity-explorer"), "the explorer");
  await evaluate(
    win,
    `window.dispatchEvent(new KeyboardEvent('keydown', { key: 'P', ctrlKey: true, shiftKey: true, bubbles: true }))`,
  );
  await waitUntil(
    win,
    `(() => {
      const input = document.querySelector('[data-testid="command-palette-input"]');
      if (input === null) return false;
      const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
      setter.call(input, '設定を開く');
      input.dispatchEvent(new Event('input', { bubbles: true }));
      return true;
    })()`,
    "typing the settings command",
  );
  await evaluate(
    win,
    `document.querySelector('[data-testid="command-palette-input"]')
       .dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))`,
  );
  const settings = await waitUntil(
    win,
    `document.querySelector('[data-testid="settings-save"]') !== null`,
    "the settings screen",
  );

  const parsedRaw = (() => {
    try {
      return JSON.parse(raw)[0].id === "U001";
    } catch {
      return false;
    }
  })();
  record(
    "the custom-rule editor and the settings screen render",
    form === true && parsedRaw && settings === true,
    parsedRaw ? "form and raw agree" : `raw pane held ${raw}`,
  );
}

/** Sets a field's value the way a person typing into it would, so React sees the change. */
function typeInto(testId, value, prototype) {
  return `(() => {
    const field = document.querySelector('[data-testid=${JSON.stringify(testId)}]');
    if (field === null) return false;
    const setter = Object.getOwnPropertyDescriptor(window.${prototype}.prototype, 'value').set;
    setter.call(field, ${JSON.stringify(value)});
    field.dispatchEvent(new Event('input', { bubbles: true }));
    return true;
  })()`;
}

/**
 * Check 15: the terminal import. The pasted screen carries a line-number area the column range has
 * to cut away, and the preview is what the user checks that against before saving.
 */
async function checkImportDialog(win) {
  const pasted = ["000100 IDENTIFICATION DIVISION.", "000200 PROGRAM-ID. SYK900."].join("\n");
  await waitUntil(win, clickTestId("explorer-import"), "the import dialog");
  await waitUntil(win, typeInto("import-paste", pasted, "HTMLTextAreaElement"), "the pasted text");
  await waitUntil(win, typeInto("import-column-from", "8", "HTMLInputElement"), "the first column");
  await waitUntil(win, typeInto("import-column-to", "72", "HTMLInputElement"), "the last column");
  await waitUntil(win, typeInto("import-filename", "SYK900.cbl", "HTMLInputElement"), "the name");
  const preview = await waitUntil(
    win,
    `(() => {
      const block = document.querySelector('[data-testid="import-preview"]');
      return block === null ? null : block.textContent;
    })()`,
    "the preview of the cut text",
  );

  await waitUntil(win, clickTestId("import-save"), "the save button");
  const saved = await waitUntil(
    win,
    `document.querySelector('[data-testid="import-saved"]') !== null`,
    "the saved message",
  );
  await waitUntil(win, clickTestId("import-close"), "the close button");
  await delay(200);
  record(
    "16. the import dialog cuts the columns and writes the file",
    preview.includes("IDENTIFICATION DIVISION.") && !preview.includes("000100") && saved === true,
    "the line-number area was cut away",
  );
}

/**
 * Check 11: the command palette. Ctrl+Shift+P opens it, typing filters it, and Enter runs the
 * highlighted command, which here toggles the panel.
 */
async function checkCommandPalette(win) {
  const before = await evaluate(
    win,
    `document.querySelector('[data-testid="bottompanel"]') !== null`,
  );
  await evaluate(
    win,
    `window.dispatchEvent(new KeyboardEvent('keydown', { key: 'P', ctrlKey: true, shiftKey: true, bubbles: true }))`,
  );
  await waitUntil(
    win,
    `document.querySelector('[data-testid="command-palette-input"]') !== null`,
    "the command palette",
  );

  const filtered = await waitUntil(
    win,
    `(() => {
      const input = document.querySelector('[data-testid="command-palette-input"]');
      if (input === null) return false;
      const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
      setter.call(input, 'パネル');
      input.dispatchEvent(new Event('input', { bubbles: true }));
      return true;
    })()`,
    "typing into the palette",
  );
  const matches = await waitUntil(
    win,
    countOf('[data-testid^="command-"]:not([data-testid$="input"]):not([data-testid$="empty"])'),
    "the filtered commands",
  );

  await evaluate(
    win,
    `document.querySelector('[data-testid="command-palette-input"]')
       .dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))`,
  );
  const toggled = await waitUntil(
    win,
    `(document.querySelector('[data-testid="bottompanel"]') !== null) !== ${before} ? 'toggled' : null`,
    "the panel toggled by the command",
  );
  record(
    "11. the palette opens, filters and runs a command",
    filtered === true && matches >= 1 && toggled === "toggled",
    `${matches} commands matched`,
  );

  // Restore the panel so the later checks see the layout they expect.
  await evaluate(
    win,
    `window.dispatchEvent(new KeyboardEvent('keydown', { key: 'j', ctrlKey: true, bubbles: true }))`,
  );
  await delay(200);
}

/**
 * Check 9: a page zoom must not produce a horizontal scrollbar. A pixel min-width on the shell would
 * do exactly that once the zoom shrank the viewport, leaving the page scrolling in both directions
 * alongside the panes' own vertical scrolling (WCAG 1.4.10, Reflow).
 */
async function checkZoomReflow(win) {
  const original = win.webContents.getZoomFactor();
  try {
    // 200%: a 1440px window becomes a 720 CSS px viewport, well under any 1120px floor.
    win.webContents.setZoomFactor(2);
    await delay(400);
    const overflow = await evaluate(
      win,
      `(() => {
        const root = document.documentElement;
        return { scroll: root.scrollWidth, client: root.clientWidth };
      })()`,
    );
    record(
      "9. a 200% zoom produces no horizontal scrollbar",
      overflow.scroll <= overflow.client,
      `scrollWidth ${overflow.scroll} / clientWidth ${overflow.client}`,
    );
  } finally {
    win.webContents.setZoomFactor(original);
    await delay(200);
  }
}

/** Check 10: console errors and CSP refusals. */
function checkConsole() {
  record(
    "10. the console carries no error and no CSP refusal",
    consoleErrors.length === 0,
    consoleErrors.length === 0 ? "0" : consoleErrors.slice(0, 5).join(" | "),
  );
}

async function main() {
  if (!existsSync(RENDERER_HTML)) {
    console.error(`the renderer has not been built: ${RENDERER_HTML}`);
    console.error("run npm run build before the smoke.");
    app.exit(1);
    return;
  }

  const win = new BrowserWindow({
    // The production default size (see src/main/window.ts).
    width: 1440,
    height: 900,
    show: false,
    webPreferences: {
      preload: FAKE_PRELOAD,
      contextIsolation: true,
      sandbox: true,
      nodeIntegration: false,
      // Render for real regardless of the display environment.
      offscreen: true,
    },
  });

  win.webContents.on("console-message", (...args) => {
    // Electron 33 passes (event, level, message, line, sourceId); later versions pass a detail object.
    const detail = typeof args[1] === "object" && args[1] !== null ? args[1] : null;
    const level = detail === null ? args[1] : detail.level;
    const message = detail === null ? args[2] : detail.message;
    const isError = level === 3 || level === "error";
    const text = String(message);
    if ((isError || /Refused to /.test(text)) && !BENIGN.some((pattern) => pattern.test(text))) {
      consoleErrors.push(text);
    }
  });
  win.webContents.on("preload-error", (_event, path, error) => {
    consoleErrors.push(`the preload failed at ${path}: ${error.message}`);
  });
  win.webContents.on("render-process-gone", (_event, details) => {
    consoleErrors.push(`the renderer stopped: ${details.reason}`);
  });

  try {
    await win.loadFile(RENDERER_HTML);
    // Each check is run on its own: one that fails should not hide the ones that follow it, and the
    // order matters only in that the editor checks build on the tab the one before them opened.
    for (const check of [
      checkShell,
      checkAssetTree,
      checkFindings,
      checkEditorLayout,
      checkDirtyMark,
      checkEditSurvivesSwitch,
      checkEbcdicColumns,
      checkSaveConflict,
      checkQuickFix,
      checkRules,
      checkRuleAndSettingsEditors,
      checkImportDialog,
      checkCommandPalette,
      checkZoomReflow,
    ]) {
      try {
        await check(win);
      } catch (error) {
        record(
          `${check.name} ran to completion`,
          false,
          error instanceof Error ? error.message : String(error),
        );
      }
    }
    checkConsole();
  } catch (error) {
    record("the smoke ran to completion", false, error instanceof Error ? error.message : String(error));
    checkConsole();
  }

  const failed = results.filter((result) => !result.ok);
  console.log(`\nrender smoke: ${results.length - failed.length} / ${results.length} checks passed`);
  app.exit(failed.length === 0 ? 0 : 1);
}

app.commandLine.appendSwitch("disable-gpu");
app.disableHardwareAcceleration();
app.whenReady().then(main);
