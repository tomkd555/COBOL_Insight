/*
 * The offscreen render smoke. It draws the built renderer (out/renderer/index.html) with Electron's
 * offscreen rendering and checks what jsdom cannot see.
 *
 * The checklist, numbered as the plan numbers it:
 *
 *   1. the shell is built from four regions (activity bar, side bar, editor area, panel)
 *   2. choosing a folder runs the scan and fills the asset tree with kind badges
 *   3. opening an asset starts Monaco and its lines land on distinct y coordinates
 *      (without 'unsafe-inline' in style-src every line collapses onto the same y)
 *   4. typing into the body raises the unsaved mark on that tab
 *   5. the problems rows open the asset's tab
 *   6. Cytoscape paints the call graph onto a canvas
 *   7. the execution-order tree orders its children by the engine's seq
 *   8. the rules view lists its toggles and a change round-trips through the rule file
 *  16. the import dialog cuts the pasted columns and writes a source file into the asset folder
 *   9. a 200% zoom produces no horizontal scrollbar (never two scroll directions at once)
 *  10. the console carries no error and no CSP refusal ("Refused to ...")
 *  11. Ctrl+Shift+P opens the palette, typing filters it, and Enter runs the command
 *  12. a CP930 asset opens as readable text and its identification area is marked at the
 *      character that byte column 73 falls on
 *  13. switching away from an edited tab and back keeps the edit, and Ctrl+Z takes it back
 *  14. saving a file that changed underneath raises the conflict dialog and leaves the file on disk as it was
 *  15. a finding whose rule has a fix offers a quick fix, which opens the diff tab
 *  17. the transpile view lines the generated code up with the COBOL through the line map
 *  19. a COPY statement carries its expansion as a view zone, which its own control collapses
 *  20. the code column is 80 columns wide under its scale, and the pane beside it takes the rest
 *  21. the pane lists the structure, shows a pressed paragraph and traces from one; a paragraph folds
 *  22. Ctrl+K S saves from inside the code editor, ahead of the editor's own Ctrl+K sequences
 *  23. the import dialog reads a copy of ISPF edit screens and keeps a line two screens share once
 *
 * Four screens are drawn for the screenshots and checked only for having drawn: the rule
 * description, the report with the engine's HTML, the run log, and the call graph with a node selected.
 *
 * Elements are selected by data-testid: selecting by visible text or class name would break this
 * smoke every time the wording or the styling changed. The engine is never launched — the preload is
 * replaced with smoke/fake-preload.cjs, whose canned data carries the screens to their results
 * state. It loads with the production settings, contextIsolation:true and sandbox:true. A failed
 * check exits non-zero.
 *
 * Run: npm run smoke:render (electron-vite build, then electron smoke/render.cjs)
 *
 * With `--shots <dir>` the suite runs once per theme, dark then light, and writes a PNG of the
 * window after every check into <dir>/<theme>/. That is how the design review sees the screens:
 * the renderer needs the preload bridge, so a browser cannot open it on its own.
 *
 * `--width <px>` sets the window width (1440 by default, the production default size). `--dom`, with
 * `--shots`, also writes each capture's text elements — computed font size, colour, background and
 * box — to <dir>/dom/<theme>-<name>.json, in the shape review-team's check_render.py reads.
 */

const { app, BrowserWindow } = require("electron");
const { existsSync, mkdirSync, writeFileSync } = require("node:fs");
const { join } = require("node:path");

/** The directory the screenshots go to, or null when the run is the plain smoke. */
const SHOTS_DIR = (() => {
  const at = process.argv.indexOf("--shots");
  return at < 0 || process.argv[at + 1] === undefined ? null : process.argv[at + 1];
})();

/** The window width; the minimum the main process allows is 1120 (src/main/window.ts). */
const WINDOW_WIDTH = (() => {
  const at = process.argv.indexOf("--width");
  const width = at < 0 ? NaN : Number(process.argv[at + 1]);
  return Number.isFinite(width) && width > 0 ? width : 1440;
})();

/** Whether each capture also writes its DOM measurements. */
const DOM_DUMP = process.argv.includes("--dom");

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

/** The theme of the suite in progress; `snap` files its captures under it. */
let currentTheme = "dark";

/**
 * Resolves once the icon font is in, every finite animation (a dialog rising, a toast, a transition)
 * has finished, and two frames have been drawn after that. Capped, so a frame that never comes only
 * delays the capture.
 */
const SETTLED = `(async () => {
  const settle = (async () => {
    await document.fonts.ready;
    await Promise.all(
      document
        .getAnimations()
        .filter((animation) => animation.effect?.getTiming().iterations !== Infinity)
        .map((animation) => animation.finished.catch(() => undefined)),
    );
    await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));
  })();
  await Promise.race([settle, new Promise((resolve) => setTimeout(resolve, 3000))]);
  return true;
})()`;

/**
 * Every element that holds text of its own, with what check_render.py measures: computed font size,
 * colour, the background the nearest painted ancestor gives it, and its box. The controls and the
 * headings go along for its hit-area and heading checks. The shape follows review-team's cdp.js.
 */
const DOM_EXPR = `(() => {
  const bg = (el) => {
    for (let at = el; at; at = at.parentElement) {
      const colour = getComputedStyle(at).backgroundColor;
      if (colour && colour !== 'rgba(0, 0, 0, 0)' && colour !== 'transparent') return [colour, false];
    }
    return ['rgb(255, 255, 255)', true];
  };
  const ownText = (el) => [...el.childNodes].filter((node) => node.nodeType === 3)
    .map((node) => node.textContent).join(' ').replace(/\\s+/g, ' ').trim();
  const text = [];
  for (const el of document.body.querySelectorAll('*')) {
    const own = ownText(el);
    if (!own) continue;
    const style = getComputedStyle(el);
    if (style.display === 'none' || style.visibility === 'hidden') continue;
    const box = el.getBoundingClientRect();
    if (!box.width && !box.height) continue;
    const [background, backgroundFallback] = bg(el);
    text.push({
      tag: el.tagName.toLowerCase(), id: el.id || undefined,
      cls: typeof el.className === 'string' && el.className !== '' ? el.className.slice(0, 60) : undefined,
      text: own.slice(0, 80), color: style.color, background, backgroundFallback: backgroundFallback || undefined,
      opacity: style.opacity !== '1' ? style.opacity : undefined,
      fontSize: style.fontSize, fontWeight: style.fontWeight, lineHeight: style.lineHeight,
      rect: { x: Math.round(box.x), y: Math.round(box.y), w: Math.round(box.width), h: Math.round(box.height) },
      clipped: el.scrollWidth > el.clientWidth + 1 && style.overflowX !== 'visible',
      disabled: el.closest('[disabled],[aria-disabled="true"]') ? true : undefined,
    });
  }
  const controlEls = [...document.querySelectorAll('a,button,input,select,textarea,[role=button],[tabindex]')];
  return {
    title: document.title,
    viewport: { width: innerWidth, height: innerHeight },
    htmlClass: document.documentElement.className || '',
    horizontalOverflow: document.documentElement.scrollWidth > document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
    headings: [...document.querySelectorAll('h1,h2,h3,h4,h5,h6')]
      .map((h) => ({ level: Number(h.tagName[1]), text: h.textContent.trim().slice(0, 80) })),
    controlsTotal: controlEls.length,
    controls: controlEls.map((el) => {
      const box = el.getBoundingClientRect();
      return {
        tag: el.tagName.toLowerCase(),
        text: (el.textContent || el.value || el.getAttribute('aria-label') || '').trim().slice(0, 40),
        w: Math.round(box.width), h: Math.round(box.height),
        labelled: el.tagName !== 'INPUT' || (el.labels !== null && el.labels.length > 0) ||
          el.hasAttribute('aria-label') || el.hasAttribute('aria-labelledby') || el.hasAttribute('title'),
        href: el.tagName === 'A' ? el.getAttribute('href') : undefined,
        type: el.getAttribute('type') || undefined,
        disabled: el.disabled === true || el.getAttribute('aria-disabled') === 'true' || undefined,
        inline: getComputedStyle(el).display === 'inline' || undefined,
      };
    }),
    text,
  };
})()`;

/**
 * Writes the window as it stands to <shots>/<theme>/<name>.png, and with --dom its measurements to
 * <shots>/dom/<theme>-<name>.json. A no-op in the plain smoke.
 */
async function snap(win, name) {
  if (SHOTS_DIR === null) {
    return;
  }
  const dir = join(SHOTS_DIR, currentTheme);
  mkdirSync(dir, { recursive: true });
  await evaluate(win, SETTLED);
  // An offscreen window paints on demand; this makes the capture the frame just settled.
  win.webContents.invalidate();
  await delay(100);
  const image = await win.webContents.capturePage();
  writeFileSync(join(dir, `${name}.png`), image.toPNG());
  if (DOM_DUMP) {
    const domDir = join(SHOTS_DIR, "dom");
    mkdirSync(domDir, { recursive: true });
    const dump = await evaluate(win, DOM_EXPR);
    writeFileSync(join(domDir, `${currentTheme}-${name}.json`), JSON.stringify(dump, null, 1));
  }
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
  // With no folder open the welcome view carries the action; the title bar's chip appears with one.
  await waitUntil(win, clickTestId("welcome-select-folder"), "the folder picker");
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

/**
 * The source editor of the group, reached through the handle vendor/monacoEditor publishes. It is
 * picked by the element it is mounted in: the pane beside the code holds a second editor.
 */
const EDITOR = `window.ciMonaco.editor.getEditors().find((each) => each.getContainerDomNode().closest('[data-testid="source-code"]') !== null)`;

/** The read-only editor of the pane, which shows one paragraph of the same text. */
const PEEK = `window.ciMonaco.editor.getEditors().find((each) => each.getContainerDomNode().closest('[data-testid="paragraph-peek"]') !== null)`;

/** Whether the window is wide enough for the pane beside the 80 columns of code. */
const PANE_FITS = WINDOW_WIDTH >= 1400;

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
      // Whether the 80 columns of the fixed format, identification area included, fit unscrolled.
      const editor = ${EDITOR};
      const info = editor.getLayoutInfo();
      const half = editor.getOption(window.ciMonaco.editor.EditorOption.fontInfo).typicalHalfwidthCharacterWidth;
      const visible = info.width - info.contentLeft - info.verticalScrollbarWidth - info.minimap.minimapWidth;
      return { lines: lines.length, distinct: tops.size, visible, needed: 80 * half };
    })()`,
    "the editor's rendered lines",
  );
  record(
    "3. Monaco renders its lines at distinct y coordinates",
    layout.lines >= 3 && layout.distinct === layout.lines,
    `${layout.lines} lines, ${layout.distinct} distinct tops; 80 columns need ${layout.needed}px of ${layout.visible}px`,
  );
}

/**
 * Check 19: the COPY expansion. The scan's table puts the copybook's lines under the COPY statement
 * on line 5 of the first asset, and they are drawn as a Monaco view zone outside the text — so the
 * model must not carry them, or a save would write the copybook into the program.
 */
async function checkCopyExpansion(win) {
  await openAsset(win, "cobol/SYK001.cbl");
  const zone = await waitUntil(
    win,
    `(() => {
      const node = document.querySelector('[data-testid="copy-zone-5"]');
      if (node === null) return null;
      return {
        text: node.textContent,
        inModel: ${EDITOR}.getModel().getValue().includes('SYK-ORDER-REC'),
      };
    })()`,
    "the COPY expansion view zone",
  );
  // The copybook's text has to start at the x the source's text starts at, so its sequence area
  // (columns 1-6) sits under the source's.
  const columns = await evaluate(
    win,
    `(() => {
      const editor = ${EDITOR};
      const line = [...document.querySelectorAll('.monaco-editor .view-line')]
        .find((element) => element.textContent.startsWith('000500'));
      const copied = document.querySelector('[data-testid="copy-zone-5"] .ci-copy__text');
      const fontInfo = editor.getOption(window.ciMonaco.editor.EditorOption.fontInfo);
      return {
        contentLeft: editor.getLayoutInfo().contentLeft,
        charWidth: fontInfo.typicalHalfwidthCharacterWidth,
        source: line === undefined ? null : line.getBoundingClientRect().left,
        copy: copied === null ? null : copied.getBoundingClientRect().left,
      };
    })()`,
  );

  await waitUntil(win, clickTestId("copy-zone-toggle-5"), "the collapse control");
  const collapsed = await waitUntil(
    win,
    `(() => {
      const toggle = document.querySelector('[data-testid="copy-zone-toggle-5"]');
      const node = document.querySelector('[data-testid="copy-zone-5"]');
      if (toggle === null || node === null) return null;
      const lines = node.querySelector('.ci-copy__lines');
      const hidden = lines !== null && lines.hidden === true;
      return toggle.getAttribute('aria-expanded') === 'false' && hidden ? 'collapsed' : null;
    })()`,
    "the collapsed expansion",
  );
  // Leave it open again, so the checks that follow see the editor as the ones before them left it.
  await waitUntil(win, clickTestId("copy-zone-toggle-5"), "the expand control");

  /*
   * The anchor has to follow the edits. Inserting a line at the top moves the COPY statement from
   * line 5 to line 6, and the editor action — which is also the keyboard's only way in, since Monaco
   * will not hand focus to a control inside a view zone — has to find the zone at its new line. An
   * anchor left on the scan's line number would toggle nothing here.
   */
  await waitUntil(
    win,
    `(() => {
      const editor = ${EDITOR};
      if (editor === undefined) return false;
      editor.setPosition({ lineNumber: 1, column: 1 });
      editor.trigger('smoke', 'type', { text: '\\n' });
      editor.setPosition({ lineNumber: 6, column: 1 });
      const action = editor.getAction('cobolInsight.toggleCopyExpansion');
      if (action === null || action === undefined) return false;
      action.run();
      return true;
    })()`,
    "the toggle action on the line the COPY statement moved to",
  );
  const followed = await waitUntil(
    win,
    `(() => {
      const toggle = document.querySelector('[data-testid="copy-zone-toggle-5"]');
      if (toggle === null) return null;
      return toggle.getAttribute('aria-expanded') === 'false' ? 'followed' : null;
    })()`,
    "the expansion the action collapsed at its new line",
  );
  // Undo the inserted line and open the expansion again, back to the state this check started from.
  await evaluate(win, `${EDITOR}.getAction('cobolInsight.toggleCopyExpansion').run()`);
  await evaluate(win, `${EDITOR}.trigger('smoke', 'undo', null)`);
  await waitUntil(
    win,
    // The dot lives in the close button's own slot now (B14) and stays in the DOM, hidden by CSS,
    // so dirtiness is read off the button's modifier class.
    `document.querySelector('[data-testid="close-source:cobol/SYK001.cbl"]')?.classList.contains('ci-tab__close--dirty') === false ? 'clean' : null`,
    "the inserted line undone",
  );

  record(
    "19. a COPY statement carries its expansion as a collapsible view zone",
    zone.text.includes("SYK-ORDER-REC") &&
      zone.inModel === false &&
      collapsed === "collapsed" &&
      followed === "followed" &&
      columns.source !== null &&
      columns.copy !== null &&
      Math.abs(columns.source - columns.copy) < 0.5,
    `zone held ${JSON.stringify(zone.text.slice(0, 48))}; sequence x ${columns.source} (source) / ${columns.copy} (copy), contentLeft ${columns.contentLeft}, half-width ${columns.charWidth}px`,
  );
}

/**
 * Check 20: the code column is 80 columns wide with the scale standing over its columns, and the
 * pane takes the rest of the row — or, on a window too narrow for it, the code keeps the row.
 * Check 21: the outline lists the program's structure; running the show-paragraph command on a
 * PERFORM operand puts that paragraph in the pane and leaves the source where it was; a paragraph
 * folds; and the trace from a paragraph is listed and drawn.
 */
async function checkSourcePane(win) {
  await openAsset(win, "cobol/SYK001.cbl");
  const layout = await waitUntil(
    win,
    `(() => {
      const editor = ${EDITOR};
      const scale = document.querySelector('.ci-scale > span');
      if (editor === undefined || scale === null) return null;
      const info = editor.getLayoutInfo();
      const host = editor.getContainerDomNode().getBoundingClientRect();
      const pane = document.querySelector('[data-testid="paragraph-pane"]') !== null;
      if (${PANE_FITS} && (!pane || info.viewportColumn !== 80)) return null;
      return {
        columns: info.viewportColumn,
        pane,
        scaleOffset: Math.abs(scale.getBoundingClientRect().left - (host.left + info.contentLeft - editor.getScrollLeft())),
      };
    })()`,
    "the code column, its scale and the pane",
  );
  record(
    "20. the code column holds 80 columns under its scale, and the pane takes the rest",
    layout.scaleOffset < 1 && layout.pane === PANE_FITS && (PANE_FITS ? layout.columns === 80 : layout.columns >= 80),
    `${layout.columns} columns, scale off by ${layout.scaleOffset}px, pane ${layout.pane ? "shown" : "hidden"}`,
  );

  // The folding ranges are computed a moment after the model is set, so the fold is asked for until
  // it takes. What the editor holds is reported when it never does.
  let folded = 0;
  let folding = null;
  for (let attempt = 0; attempt < 40 && folded <= 1; attempt++) {
    folding = await evaluate(
      win,
      `(async () => {
        const editor = ${EDITOR};
        const model = await editor.getContribution('editor.contrib.folding').getFoldingModel();
        // The line that opens SUB-PROC, found in the text; the folding command counts from zero.
        const header = editor.getModel().getLinesContent()
          .findIndex((line) => /^.{6} SUB-PROC\\./.test(line));
        editor.trigger('smoke', 'editor.fold', { selectionLines: [header] });
        await new Promise((resolve) => setTimeout(resolve, 100));
        return {
          ranges: editor.getVisibleRanges().length,
          // The fold is the paragraph's own: the lines hidden start right under its header.
          header: header + 1,
          firstHidden: editor.getVisibleRanges()[0].endLineNumber + 1,
          regions: model === null ? null : model.regions.length,
          language: editor.getModel().getLanguageId(),
        };
      })()`,
    );
    folded = folding.ranges;
  }
  if (folded <= 1 || folding.firstHidden !== folding.header + 1) {
    throw new Error(`the paragraph never folded: ${JSON.stringify(folding)}`);
  }
  await evaluate(win, `${EDITOR}.trigger('smoke', 'editor.unfoldAll', null)`);
  if (!PANE_FITS) {
    record("21. a paragraph folds (the pane is hidden at this width)", folded > 1);
    return;
  }

  const rows = await waitUntil(
    win,
    countOf('[data-testid^="outline-"][role="treeitem"]'),
    "the outline",
  );
  const before = await evaluate(
    win,
    `(() => {
      const editor = ${EDITOR};
      // The PERFORM that names CHECK-PROC, found in the text.
      const lines = editor.getModel().getLinesContent();
      const lineNumber = lines.findIndex((line) => line.includes('PERFORM CHECK-PROC')) + 1;
      const column = lines[lineNumber - 1].indexOf('CHECK-PROC') + 2;
      editor.setPosition({ lineNumber, column });
      editor.getAction('cobolInsight.showParagraph').run();
      return editor.getScrollTop();
    })()`,
  );
  const shown = await waitUntil(
    win,
    `(() => {
      const name = document.querySelector('.ci-pane__name')?.textContent;
      const peek = ${PEEK};
      // The pane brings the line that opens CHECK-PROC to its top.
      const start = ${EDITOR}.getModel().getLinesContent()
        .findIndex((line) => /^.{6} CHECK-PROC\\./.test(line)) + 1;
      const first = peek === undefined ? undefined : peek.getVisibleRanges()[0]?.startLineNumber;
      return name === 'CHECK-PROC' && first === start ? { name, first, top: ${EDITOR}.getScrollTop() } : null;
    })()`,
    "the pressed paragraph in the pane",
  );

  // The trace starts at the paragraph chosen in the outline.
  await waitUntil(win, clickTestId("outline-SUB-SECT/SUB-PROC"), "the outline row of SUB-PROC");
  await waitUntil(win, clickTestId("pane-tab-trace"), "the trace tab");
  const traced = await waitUntil(
    win,
    `(() => {
      const count = document.querySelectorAll('[data-testid^="paragraph-trace-"][role="treeitem"]').length;
      const drawn = document.querySelectorAll('[data-testid="paragraph-pane"] canvas').length;
      return count >= 4 && drawn > 0 ? { count, drawn } : null;
    })()`,
    "the trace rows and the drawing",
  );
  record(
    "21. the pane lists the structure, shows a pressed paragraph, and traces from one; a paragraph folds",
    rows >= 10 && shown.top === before && folded > 1 && traced.count >= 4,
    `${rows} outline rows, ${shown.name} from line ${shown.first}, ${traced.count} trace rows`,
  );
}

/** Check 4: typing raises the unsaved mark on the tab. */
async function checkDirtyMark(win) {
  await waitUntil(win, typeAtStart("X"), "typing into the editor");
  const marked = await waitUntil(
    win,
    `document.querySelector('[data-testid="close-source:cobol/SYK001.cbl"]')?.classList.contains('ci-tab__close--dirty') === true`,
    "the unsaved mark",
  );
  record("4. typing raises the unsaved mark on the tab", marked === true);
}

/**
 * Check 13: the text model belongs to the tab and outlives the editor, so switching away and back
 * keeps both the edit and the undo stack. The edit made by check 4 is the one carried across.
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
      const dirty = document.querySelector('[data-testid="close-source:cobol/SYK001.cbl"]')?.classList.contains('ci-tab__close--dirty');
      return clean && dirty === false ? 'undone' : null;
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
    `document.querySelector('[data-testid="close-source:cobol/SYK001.cbl"]')?.classList.contains('ci-tab__close--dirty') === true`,
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
  await snap(win, "checkSaveConflict-dialog");
  record(
    "14. saving over a changed original raises the conflict dialog",
    raised.ok === true,
    raised.ok === true ? undefined : JSON.stringify(raised),
  );

  if (raised.ok !== true) {
    return;
  }
  // Take the overwrite, so the later checks start from a saved file with no dialog open.
  await waitUntil(win, clickTestId("save-conflict-overwrite"), "the overwrite button");
  await waitUntil(
    win,
    `document.querySelector('[data-testid="save-conflict"]') === null ? 'closed' : null`,
    "the dialog closing",
  );
}

/**
 * Check 22: Ctrl+K S saves everything while the caret is in the code. The editor has Ctrl+K
 * sequences of its own, so the two keys are sent to the editor's own input and have to reach the
 * shell ahead of it.
 */
async function checkSaveAllChord(win) {
  await activateSourceTab(win, "cobol/SYK001.cbl", SYK001_MARK);
  await waitUntil(win, typeAtStart("Q"), "an unsaved edit");
  await waitUntil(
    win,
    `document.querySelector('[data-testid="close-source:cobol/SYK001.cbl"]')?.classList.contains('ci-tab__close--dirty') === true`,
    "the unsaved mark before the chord",
  );
  await evaluate(
    win,
    `(() => {
      const input = ${EDITOR}.getContainerDomNode().querySelector('textarea, .native-edit-context');
      const press = (key, code, keyCode, ctrlKey) =>
        input.dispatchEvent(new KeyboardEvent('keydown', { key, code, keyCode, ctrlKey, bubbles: true, cancelable: true }));
      press('k', 'KeyK', 75, true);
      press('s', 'KeyS', 83, false);
      return true;
    })()`,
  );
  const saved = await waitUntil(
    win,
    `document.querySelector('[data-testid="close-source:cobol/SYK001.cbl"]')?.classList.contains('ci-tab__close--dirty') === false`,
    "the unsaved mark going after Ctrl+K S",
  ).catch(() => false);
  record("22. Ctrl+K S saves from inside the code editor", saved === true);
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
      // Through trigger, because the code-action commands are contributed as editor commands, which
      // getAction does not list.
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

/** Runs a command by its title through the palette, which is the only route the shell offers. */
async function runCommand(win, title) {
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
      setter.call(input, ${JSON.stringify(title)});
      input.dispatchEvent(new Event('input', { bubbles: true }));
      return true;
    })()`,
    `typing ${title} into the palette`,
  );
  await evaluate(
    win,
    `document.querySelector('[data-testid="command-palette-input"]')
       .dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))`,
  );
}

/** Chooses an option in a select the way a person would, so React sees the change. */
function chooseOption(testId, value) {
  return `(() => {
    const field = document.querySelector('[data-testid=${JSON.stringify(testId)}]');
    if (field === null) return false;
    const setter = Object.getOwnPropertyDescriptor(window.HTMLSelectElement.prototype, 'value').set;
    setter.call(field, ${JSON.stringify(value)});
    field.dispatchEvent(new Event('change', { bubbles: true }));
    return true;
  })()`;
}

/**
 * The two editors of the transpile view, told apart by the element each was created in. Telling them
 * apart by their text would not do: the COBOL and its translation share the program's name.
 */
const TRANSPILE_PANES = `(() => {
  const editors = window.ciMonaco.editor.getEditors();
  const paneAt = (testId) => {
    const host = document.querySelector('[data-testid="' + testId + '"]');
    if (host === null) return undefined;
    return editors.find(
      (editor) => editor.getModel() !== null && host.contains(editor.getContainerDomNode()),
    );
  };
  return { cobol: paneAt('transpile-cobol'), generated: paneAt('transpile-generated') };
})()`;

/**
 * Check 17: the transpile view. It opens for the asset in the active source tab, and moving the
 * caret in the COBOL moves the caret in the generated code to the line LINE_MAP names — line 10 of
 * the COBOL is line 3 of the Python and line 4 of the Java, so a pane that merely followed the line
 * number would land on 10 in both.
 */
async function checkTranspile(win) {
  await activateSourceTab(win, "cobol/SYK001.cbl", SYK001_MARK);
  await runCommand(win, "変換");
  await waitUntil(
    win,
    `document.querySelector('[data-testid="transpile-cobol"]') !== null`,
    "the transpile view",
  );
  await waitUntil(
    win,
    `(() => {
      const panes = ${TRANSPILE_PANES};
      if (panes.cobol === undefined || panes.generated === undefined) return null;
      if (!panes.generated.getModel().getValue().includes('def main')) return null;
      panes.cobol.setPosition({ lineNumber: 10, column: 1 });
      return true;
    })()`,
    "the two panes showing the Python",
  );
  const python = await waitUntil(
    win,
    `(() => {
      const panes = ${TRANSPILE_PANES};
      const line = panes.generated === undefined ? 0 : panes.generated.getPosition().lineNumber;
      return line === 0 ? null : line;
    })()`,
    "the caret the Python pane followed to",
  );

  await waitUntil(win, chooseOption("transpile-language", "java"), "the language selector");
  await waitUntil(
    win,
    `(() => {
      const panes = ${TRANSPILE_PANES};
      if (panes.cobol === undefined || panes.generated === undefined) return null;
      if (!panes.generated.getModel().getValue().includes('public final class')) return null;
      panes.cobol.setPosition({ lineNumber: 1, column: 1 });
      panes.cobol.setPosition({ lineNumber: 10, column: 1 });
      return true;
    })()`,
    "the two panes showing the Java",
  );
  const java = await waitUntil(
    win,
    `(() => {
      const panes = ${TRANSPILE_PANES};
      const line = panes.generated === undefined ? 0 : panes.generated.getPosition().lineNumber;
      return line === 0 ? null : line;
    })()`,
    "the caret the Java pane followed to",
  );

  // The model holds the Java before the pane has drawn it; the capture waits for the drawn lines.
  await waitUntil(
    win,
    `[...document.querySelectorAll('[data-testid="transpile-generated"] .view-line')]
       .some((line) => line.textContent.replace(/\\u00a0/g, ' ').includes('public final class'))
       ? true
       : null`,
    "the Java drawn in its pane",
  );
  // The line number 10 and the tenth line of the COBOL are drawn on one row after the panes moved.
  const gutter = await waitUntil(
    win,
    `(() => {
      const host = document.querySelector('[data-testid="transpile-cobol"]');
      const number = [...host.querySelectorAll('.margin-view-overlays .line-numbers')]
        .find((each) => each.textContent.trim() === '10');
      const line = [...host.querySelectorAll('.view-lines .view-line')]
        .find((each) => each.textContent.startsWith('001000'));
      if (number === undefined || line === undefined) return null;
      return { number: number.getBoundingClientRect().top, line: line.getBoundingClientRect().top };
    })()`,
    "line 10 of the COBOL pane and its number",
  );
  await snap(win, "checkTranspile-panes");
  record(
    "17. the transpile view lines the generated code up through the line map",
    python === 3 && java === 4 && Math.abs(gutter.number - gutter.line) <= 1,
    `COBOL line 10 reached Python line ${python} and Java line ${java}; its number is drawn at ${gutter.number} and its text at ${gutter.line}`,
  );

  // Back to the source, so the pane is unmounted and its two editors are disposed.
  await activateSourceTab(win, "cobol/SYK001.cbl", SYK001_MARK);
}

/** Check 5: the problems table and the route from a row into the source. */
async function checkFindings(win) {
  // Opening a folder only scans it; the findings arrive with the analysis the run button starts.
  // That button analyses what the tree has selected, so a row is chosen first. The folder row is
  // the target: clicking it also collapses the folder, so it is clicked twice to leave the tree as
  // it was for the checks that open an asset from it.
  await waitUntil(win, clickTestId("tree-cobol"), "the tree row for the cobol folder");
  await waitUntil(win, clickTestId("tree-cobol"), "the cobol folder row expanded again");
  await waitUntil(win, clickTestId("run-analysis"), "the run button");
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
  const detailed = await waitUntil(
    win,
    `(() => {
      const detail = document.querySelector('[data-testid="problem-detail"]');
      return detail !== null && detail.textContent.includes('宣言 4行');
    })()`,
    "the finding detail",
  );
  const scope = await evaluate(
    win,
    `(() => { const all = window.cobolInsightSmoke.lintScopes(); return all[all.length - 1]; })()`,
  );
  await snap(win, "checkFindings-detail");
  record(
    "5. a problems row opens the asset's tab and its detail",
    rows >= 3 && opened === true && detailed === true && scope?.[0] === "cobol",
    `${rows} rows, lint scope ${JSON.stringify(scope)}`,
  );
}

/**
 * Check 18: the whole-folder run. The run button analyses what the tree has selected, so the run
 * that covers everything is a command of its own, and it has to reach the engine with no scope.
 */
async function checkAnalyseAll(win) {
  const before = await evaluate(win, `window.cobolInsightSmoke.lintScopes().length`);
  await evaluate(
    win,
    `window.dispatchEvent(new KeyboardEvent('keydown', { key: 'P', ctrlKey: true, shiftKey: true, bubbles: true }))`,
  );
  await waitUntil(win, clickTestId("command-run.analyzeAll"), "the whole-folder command");
  const scopes = await waitUntil(
    win,
    `(() => {
      const all = window.cobolInsightSmoke.lintScopes();
      return all.length > ${before} ? all : null;
    })()`,
    "the lint the command started",
  );
  record(
    "18. the palette's whole-folder command runs lint over everything",
    scopes[scopes.length - 1] === null,
    `lint scopes so far: ${JSON.stringify(scopes)}`,
  );
}

/**
 * Checks 6 and 7: the call graph. Cytoscape draws to a canvas, which jsdom cannot exercise at all,
 * so this is the only place the drawing is proved to happen: the canvas has to carry pixels that are
 * not fully transparent. The execution-order tree beside it has to list the two steps in the order
 * the engine's seq gives them, which is the reverse of the order the canned edges are written in.
 */
async function checkCallGraph(win) {
  await waitUntil(win, clickTestId("activity-graph"), "the call-graph tab");
  await waitUntil(
    win,
    `document.querySelector('[data-testid="graph-canvas"] canvas') !== null`,
    "the cytoscape canvas",
  );

  // The layout runs asynchronously; poll until something has been painted.
  const painted = await waitUntil(
    win,
    `(() => {
      const canvases = [...document.querySelectorAll('[data-testid="graph-canvas"] canvas')];
      let opaque = 0;
      for (const canvas of canvases) {
        if (canvas.width === 0 || canvas.height === 0) continue;
        const image = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
        // Every fourth byte is alpha; a stride keeps a large canvas cheap to scan.
        for (let index = 3; index < image.length; index += 4 * 37) {
          if (image[index] > 0) opaque += 1;
        }
      }
      return opaque > 0 ? opaque : null;
    })()`,
    "opaque pixels on the graph canvas",
  );
  record("6. Cytoscape paints the call graph onto a canvas", painted > 0, `${painted} opaque pixels`);

  const steps = await waitUntil(
    win,
    `(() => {
      const rows = [...document.querySelectorAll('[data-testid="trace-tree"] [role="treeitem"]')]
        .map((row) => row.textContent);
      const found = rows.filter((label) => label.includes('STEP0'));
      return found.length >= 2 ? found : null;
    })()`,
    "the steps in the execution-order tree",
  );
  const ordered = steps.findIndex((label) => label.includes("STEP010")) <
    steps.findIndex((label) => label.includes("STEP020"));
  record("7. the execution-order tree orders its children by seq", ordered, steps.join(" / "));

  // The toolbar drives cytoscape imperatively, which jsdom cannot exercise either. Nothing is
  // asserted here beyond the buttons working: an exception would surface in the console check.
  for (const button of ["graph-zoom-in", "graph-zoom-out", "graph-fit", "graph-recenter"]) {
    await waitUntil(win, clickTestId(button), `the ${button} button`);
    await delay(120);
  }
}

/**
 * Check 8: the rules view. Its toggles are drawn from the engine's catalogue, and switching one off
 * has to travel out through the rule file and back through the catalogue, a longer path than
 * flipping a checkbox on screen.
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
  // The catalogue comes back through the engine after the write; the problems panel marks its list as
  // stale once it has, which is the last thing this change draws.
  await waitUntil(
    win,
    `document.querySelector('[data-testid="problems-stale-run"]') !== null ? true : null`,
    "the stale-rules note in the problems panel",
  );
  await snap(win, "checkRules-view");
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

  await snap(win, "checkRuleAndSettingsEditors-customRules");
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
  // The settings apply as they are changed, so the screen has no save button to wait for.
  const settings = await waitUntil(
    win,
    `document.querySelector('[data-testid="settings-threshold"]') !== null`,
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

/**
 * The call graph with a node chosen, so the detail pane is filled. It is chosen from a problems row:
 * a trace row would also open the step's JCL, which takes the graph tab out of view.
 */
async function checkGraphSelection(win) {
  await waitUntil(
    win,
    `(() => {
      const button = [...document.querySelectorAll('[data-testid^="finding-graph-"]')]
        .find((element) => element.closest('tr').textContent.includes('cobol/SYK001.cbl'));
      if (button === undefined) return null;
      button.click();
      return true;
    })()`,
    "the graph button of a SYK001 finding",
  );
  const label = await waitUntil(
    win,
    `document.querySelector('[data-testid="graph-detail-label"]')?.textContent ?? null`,
    "the detail pane of the chosen node",
  );
  record("the call graph shows the chosen node's detail", label.includes("SYK001"), label);
}

/** The description of one rule, opened from the rules view. */
async function checkRuleDetail(win) {
  await waitUntil(win, clickTestId("activity-rules"), "the rules view");
  await waitUntil(win, clickTestId("rule-open-R004"), "the name of R004");
  const shown = await waitUntil(
    win,
    `document.querySelector('[data-testid="rule-detail-R004"]') !== null ? true : null`,
    "the description of R004",
  );
  record("the rule description renders", shown === true);
  await waitUntil(win, clickTestId("activity-explorer"), "the explorer");
}

/** The report tab, with the HTML report the engine wrote. */
async function checkReport(win) {
  await waitUntil(win, clickTestId("activity-report"), "the report tab");
  await waitUntil(
    win,
    `(() => {
      const button = document.querySelector('[data-testid="report-generate-html"]');
      if (button === null || button.disabled) return null;
      button.click();
      return true;
    })()`,
    "the HTML report button",
  );
  const shown = await waitUntil(
    win,
    `document.querySelector('[data-testid="report-html"]') !== null ? true : null`,
    "the HTML report",
  );
  record("the report renders the engine's HTML", shown === true);
}

/** The run log in the panel. The problems view is put back afterwards. */
async function checkOutput(win) {
  await waitUntil(win, clickTestId("panel-tab-output"), "the output tab");
  const lines = await waitUntil(win, countOf('[data-testid^="log-"]'), "the run log lines");
  record("the run log lists the runs", lines > 0, `${lines} lines`);
  await snap(win, "checkOutput");
  await waitUntil(win, clickTestId("panel-tab-problems"), "the problems tab");
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
 * Check 16: the terminal import. The pasted screen carries a line-number area the column range has
 * to cut away, and the preview is what the user checks that against before saving.
 */
async function checkImportDialog(win) {
  const pasted = ["000100 IDENTIFICATION DIVISION.", "000200 PROGRAM-ID. SYK900."].join("\n");
  await waitUntil(win, clickTestId("explorer-import"), "the import dialog");
  await waitUntil(win, typeInto("import-paste", pasted, "HTMLTextAreaElement"), "the pasted text");
  await waitUntil(win, typeInto("import-column-from", "8", "HTMLInputElement"), "the first column");
  await waitUntil(win, typeInto("import-column-to", "72", "HTMLInputElement"), "the last column");
  await waitUntil(win, typeInto("import-filename", "SYK900.cbl", "HTMLInputElement"), "the name");
  // Waited for whole: the preview is cut again on every field the four inputs above set.
  const preview = await waitUntil(
    win,
    `(() => {
      const block = document.querySelector('[data-testid="import-preview"]');
      return block === null || !block.textContent.includes('PROGRAM-ID. SYK900.') ? null : block.textContent;
    })()`,
    "the preview of the cut text",
  );

  await snap(win, "checkImportDialog-open");
  await waitUntil(win, clickTestId("import-save"), "the save button");
  const saved = await waitUntil(
    win,
    `document.querySelector('[data-testid="import-saved"]') !== null`,
    "the saved message",
  );
  await waitUntil(win, clickTestId("import-close"), "the close button");
  // The scan that follows the import is what puts the new file into the tree.
  const listed = await waitUntil(
    win,
    `[...document.querySelectorAll('[data-testid="asset-tree"] .ci-tree__name')]
       .some((name) => name.textContent === 'SYK900.cbl') ? true : null`,
    "the imported file in the asset tree",
  );
  record(
    "16. the import dialog cuts the columns, writes the file, and the tree lists it",
    preview.includes("IDENTIFICATION DIVISION.") &&
      !preview.includes("000100") &&
      saved === true &&
      listed === true,
    "the line-number area was cut away",
  );
}

/**
 * Check 23: a copy of two overlapping ISPF edit screens is recognised from its title rows, and the
 * preview holds the source alone: the panel rows and line numbers are gone, the shared line is there
 * once, and each line is back at the column the title names.
 */
async function checkImportScreen(win) {
  const screen = (rows) => [
    " EDIT       USER01.SRC.COBOL(SYK901) - 01.00                Columns 00001 00072",
    " Command ===>                                                  Scroll ===> CSR",
    ...rows,
  ];
  const pasted = [
    ...screen([
      " ****** ***************************** Top of Data ******************************",
      " 000100        IDENTIFICATION DIVISION.",
      " 000200        PROGRAM-ID. SYK901.",
    ]),
    ...screen([" 000200        PROGRAM-ID. SYK901.", " 000300        PROCEDURE DIVISION."]),
  ].join("\n");
  await waitUntil(win, clickTestId("explorer-import"), "the import dialog");
  await waitUntil(win, typeInto("import-paste", pasted, "HTMLTextAreaElement"), "the pasted screens");
  const read = await waitUntil(
    win,
    `(() => {
      const note = document.querySelector('[data-testid="import-read"]');
      const block = document.querySelector('[data-testid="import-preview"]');
      if (note === null || block === null) return null;
      return {
        note: note.textContent,
        preview: block.textContent,
        columnsHidden: document.querySelector('[data-testid="import-column-from"]') === null,
      };
    })()`,
    "the screens read as an ISPF edit panel",
  );
  await snap(win, "checkImportScreen-open");
  await waitUntil(win, clickTestId("import-cancel"), "the cancel button");
  await delay(200);
  const lines = read.preview.split("\n").slice(2);
  record(
    "23. the import dialog reads an ISPF edit screen and keeps a shared line once",
    read.columnsHidden &&
      lines.length === 3 &&
      lines[1] === "       PROGRAM-ID. SYK901." &&
      !read.preview.includes("Command ===>"),
    read.note,
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
  const COMMANDS = '[data-testid^="command-"]:not([data-testid$="input"]):not([data-testid$="empty"])';
  const unfiltered = await waitUntil(
    win,
    `document.querySelector('[data-testid="command-palette-input"]') === null
       ? null
       : document.querySelectorAll(${JSON.stringify(COMMANDS)}).length`,
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
  // The list is counted once the filter has drawn it shorter, so the capture shows the filtered list.
  const matches = await waitUntil(
    win,
    `(() => {
      const count = document.querySelectorAll(${JSON.stringify(COMMANDS)}).length;
      return count > 0 && count < ${unfiltered} ? count : null;
    })()`,
    "the filtered commands",
  );

  await snap(win, "checkCommandPalette-open");
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
    // Captured here, while the zoom is still applied: a shot taken after the reset shows the
    // ordinary layout and says nothing about what the check looked at.
    await snap(win, "checkZoomReflow");
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

  for (const theme of SHOTS_DIR === null ? ["dark"] : ["dark", "light"]) {
    await runSuite(theme);
  }

  const failed = results.filter((result) => !result.ok);
  console.log(`\nrender smoke: ${results.length - failed.length} / ${results.length} checks passed`);
  app.exit(failed.length === 0 ? 0 : 1);
}

/** Loads the renderer with the fake preload told to store that theme, and runs every check. */
async function runSuite(theme) {
  // One suite's console faults must not be charged to the next.
  consoleErrors.length = 0;
  currentTheme = theme;
  const win = new BrowserWindow({
    // The production default size (see src/main/window.ts) unless --width asks for another.
    width: WINDOW_WIDTH,
    height: 900,
    show: false,
    webPreferences: {
      preload: FAKE_PRELOAD,
      contextIsolation: true,
      sandbox: true,
      nodeIntegration: false,
      // Render for real regardless of the display environment.
      offscreen: true,
      // The fake preload reads this into the stored settings, so the shell resolves that theme.
      additionalArguments: [`--ci-theme=${theme}`],
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
    // Chromium keeps a zoom level per origin in the profile, which a run stopped inside the zoom
    // check, or another smoke running beside this one, can leave at 200%.
    win.webContents.setZoomFactor(1);
    // Each check is run on its own: one that fails should not hide the ones that follow it, and the
    // order matters only in that the editor checks build on the tab the one before them opened.
    for (const check of [
      checkShell,
      checkAssetTree,
      checkFindings,
      checkAnalyseAll,
      checkCallGraph,
      checkGraphSelection,
      checkEditorLayout,
      checkCopyExpansion,
      checkSourcePane,
      checkDirtyMark,
      checkEditSurvivesSwitch,
      checkEbcdicColumns,
      checkSaveConflict,
      checkSaveAllChord,
      checkQuickFix,
      checkTranspile,
      checkRules,
      checkRuleDetail,
      checkRuleAndSettingsEditors,
      checkReport,
      checkOutput,
      checkImportDialog,
      checkImportScreen,
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
      // These two capture their own shot: the zoom check before it puts the zoom factor back, the
      // run log before the problems view returns.
      if (check !== checkZoomReflow && check !== checkOutput) {
        await snap(win, check.name);
      }
    }
    checkConsole();
  } catch (error) {
    record("the smoke ran to completion", false, error instanceof Error ? error.message : String(error));
    checkConsole();
  }
  win.destroy();
}

// Closing the first theme's window must not end the app before the second theme has run.
app.on("window-all-closed", () => undefined);
app.commandLine.appendSwitch("disable-gpu");
app.disableHardwareAcceleration();
app
  .whenReady()
  .then(main)
  .catch((error) => {
    console.error(error);
    app.exit(1);
  });
