/*
 * The offscreen render smoke. It draws the built renderer (out/renderer/index.html) with Electron's
 * offscreen rendering and checks what jsdom cannot see.
 *
 * The checklist, numbered as the plan numbers it:
 *
 *   1. the shell is built from four regions (activity bar, side bar, editor area, panel)
 *   2. choosing a folder runs the analysis and fills the asset tree with kind badges
 *   5. the problems rows open the asset's tab
 *   9. a 200% zoom produces no horizontal scrollbar (never two scroll directions at once)
 *  10. the console carries no error and no CSP refusal ("Refused to ...")
 *  11. Ctrl+Shift+P opens the palette, typing filters it, and Enter runs the command
 *
 * TODO 3: opening an asset starts Monaco and its lines land on distinct y coordinates
 *         (without 'unsafe-inline' in style-src every line collapses onto the same y).
 * TODO 4: typing into the body raises the unsaved mark on that tab.
 * TODO 6: Cytoscape builds a canvas and paints nodes onto it (opaque pixels are present).
 * TODO 7: the execution-order list orders its children by the engine's seq.
 * TODO 8: the rules tab lists its toggles and a change round-trips through the rule file.
 * TODO 12: the fix diff shows the original beside the fixed text.
 * TODO 13: the report view renders the engine's HTML.
 * TODO 14: the transpile view lines the generated code up with the COBOL.
 * TODO 15: the import dialog writes a source file into the asset folder.
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
    if (isError || /Refused to /.test(String(message))) {
      consoleErrors.push(String(message));
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
    await checkShell(win);
    await checkAssetTree(win);
    await checkFindings(win);
    await checkCommandPalette(win);
    await checkZoomReflow(win);
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
