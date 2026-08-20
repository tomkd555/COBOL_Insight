/*
 * 実描画 smoke。ビルド済みの renderer(out/renderer/index.html)を Electron の offscreen
 * レンダリングで実際に描かせ、jsdom では確かめられない次の点を検査する。
 *
 *   1. シェルが4領域(アクティビティバー・側パネル・本文領域・下部パネル)で組み上がる
 *   2. 資産フォルダを選ぶと解析が走り、資産ツリーが並ぶ
 *   3. 資産を開くと Monaco が起動し、行が別々の y 座標に並ぶ
 *      (CSP の style-src に 'unsafe-inline' が無いと全行が同じ y へ重なる。この検査がそれを捕らえる)
 *   4. 本文へ打鍵すると、そのタブに未保存の印が立つ
 *   5. 下部パネルの指摘表が並び、行を押すとその資産のタブが開く
 *   6. Cytoscape が canvas を作り、そこへノードを描く(不透明な画素がある)
 *   7. 実行順の一覧が、engine の seq のとおりに下位を並べる
 *   8. ルールのタブがトグルを並べ、切り替えが設定ファイル経由で往復する
 *   9. 200% 拡大でも横スクロールが出ない(縦横 2 方向のスクロールにならない)
 *  10. console にエラーと CSP 拒否("Refused to ...")が出ない
 *
 * 画面の要素は data-testid で選ぶ。表示文字とクラス名で選ぶと、文言や見た目を直すたびに
 * この smoke が壊れる。engine CLI は起動しない。preload を smoke/fake-preload.cjs へ差し替え、
 * window.cobolInsight を固定データで満たして画面を results 状態まで進める。本番と同じ
 * contextIsolation:true・sandbox:true で読み込む。失敗した検査があれば非ゼロで終了する。
 *
 * 実行: npm run smoke:render(electron-vite build のあとに electron smoke/render.cjs)
 */

const { app, BrowserWindow } = require("electron");
const { existsSync } = require("node:fs");
const { join } = require("node:path");

/** 各待機の上限。描画の初期化(Monaco の Worker 起動・ELK のレイアウト)を含むため長めに取る。 */
const WAIT_TIMEOUT_MS = 30000;

const RENDERER_HTML = join(__dirname, "..", "out", "renderer", "index.html");
const FAKE_PRELOAD = join(__dirname, "fake-preload.cjs");

/** 偽 preload が返すグラフのノード ID の下限。実行順の一覧の鍵を組むために持つ。 */
const GRAPH_ID_BASE = 1_000_000_000_000;

/** 検査結果。ok=false が1件でもあれば非ゼロ終了する。 */
const results = [];
/** console に出たエラーと CSP 拒否。 */
const consoleErrors = [];

function record(name, ok, detail) {
  results.push({ name, ok, detail });
  console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail === undefined ? "" : ` — ${detail}`}`);
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** ページ内で式を評価する。CSP は executeJavaScript の注入を妨げない。 */
function evaluate(win, expression) {
  return win.webContents.executeJavaScript(expression, true);
}

/** 式が真の値を返すまで待ち、その値を返す。時間内に成立しなければ例外を投げる。 */
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
  throw new Error(`時間内に成立しなかった: ${description}（最後の評価値 ${JSON.stringify(last)}）`);
}

/** data-testid で引いた要素を押す式。要素が現れるまで待つ用途を兼ねる。 */
function clickTestId(testId, inner) {
  const selector = `[data-testid=${JSON.stringify(testId)}]${inner === undefined ? "" : ` ${inner}`}`;
  return `(() => {
    const target = document.querySelector(${JSON.stringify(selector)});
    if (target === null) return false;
    target.click();
    return true;
  })()`;
}

/** 選択子に一致する要素の数を返す式。0 件のときは null を返し、待機の合図にする。 */
function countOf(selector) {
  return `(() => {
    const count = document.querySelectorAll(${JSON.stringify(selector)}).length;
    return count > 0 ? count : null;
  })()`;
}

/** 選択子に一致する要素の data-testid を並び順のまま返す式。 */
function testIdsOf(selector) {
  return `(() => {
    const ids = [...document.querySelectorAll(${JSON.stringify(selector)})].map((e) => e.dataset.testid);
    return ids.length > 0 ? ids : null;
  })()`;
}

/** 検査1: シェルの4領域。 */
async function checkShell(win) {
  const present = await waitUntil(
    win,
    `(() => {
      const ids = ['activitybar', 'sidepanel', 'editorarea', 'bottompanel'];
      const found = ids.filter((id) => document.querySelector('[data-testid="' + id + '"]') !== null);
      return found.length === ids.length ? found : null;
    })()`,
    "シェルの4領域",
  );
  record("シェルが4領域で組み上がる", present.length === 4, present.join(" / "));
}

/** 検査2: 資産フォルダの選択から解析、資産ツリーの表示まで。 */
async function checkAssetTree(win) {
  await waitUntil(win, clickTestId("select-folder"), "資産フォルダの選択");
  const rows = await waitUntil(win, countOf('[data-testid^="tree-"]'), "資産ツリーの表示");
  const badges = await evaluate(win, `document.querySelectorAll('[data-testid^="tree-"] .ci-badge').length`);
  record("資産ツリーが種別バッジ付きで並ぶ", rows > 0 && badges === 5, `行 ${rows} 件・バッジ ${badges} 件`);
}

/** 検査3: 資産を開いたときの Monaco の実描画。行が重なっていないことを y 座標で確かめる。 */
async function checkSourceTab(win) {
  await waitUntil(win, clickTestId("tree-cobol/SYK001.cbl"), "資産の押下");
  await waitUntil(win, `document.querySelector('[data-testid="tabpanel-source:cobol/SYK001.cbl"]') !== null`, "資産タブの表示");
  const lines = await waitUntil(
    win,
    `(() => {
      const tops = [...document.querySelectorAll('[data-testid="editorarea"] .view-line')]
        .map((line) => Math.round(line.getBoundingClientRect().top));
      return tops.length >= 5 ? tops : null;
    })()`,
    "Monaco の行描画",
  );
  const unique = new Set(lines);
  record(
    "Monaco の行が別々の y 座標に並ぶ",
    unique.size === lines.length && unique.size >= 5,
    `view-line ${lines.length} 本・異なる y ${unique.size} 個（y=${[...unique].slice(0, 4).join(",")}…）`,
  );

  const identification = await waitUntil(
    win,
    countOf(".ci-code__identification"),
    "識別欄の装飾",
  );
  record("識別欄(73〜80桁)の装飾が描かれる", identification > 0, `装飾 ${identification} 箇所`);
}

/**
 * 検査4: 本文への打鍵と未保存の印。面の実体は Monaco が持ち DOM からは辿れないため、
 * renderer が公開する ciMonaco から編集できる面を引いて打鍵する。
 */
async function checkEditing(win) {
  await waitUntil(
    win,
    `(() => {
      const monaco = window.ciMonaco;
      if (monaco === undefined) return false;
      const editors = monaco.editor.getEditors()
        .filter((editor) => !editor.getOption(monaco.editor.EditorOption.readOnly));
      if (editors.length === 0) return false;
      const editor = editors[editors.length - 1];
      editor.setPosition({ lineNumber: 11, column: 1 });
      editor.trigger('smoke', 'type', { text: '*' });
      return true;
    })()`,
    "本文への打鍵",
  );
  const marker = await waitUntil(
    win,
    countOf('[data-testid="dirty-source:cobol/SYK001.cbl"]'),
    "未保存の印",
  );
  record("打鍵するとタブに未保存の印が立つ", marker === 1, `印 ${marker} 個`);
}

/** 検査5: 指摘の表と、行から資産を開く経路。 */
async function checkFindings(win) {
  const rows = await waitUntil(win, countOf('[data-testid^="finding-"]'), "指摘表の行");
  await waitUntil(
    win,
    `(() => {
      const row = [...document.querySelectorAll('[data-testid^="finding-"]')]
        .find((element) => element.textContent.includes('cobol/SYK002.cbl'));
      if (row === undefined) return false;
      row.click();
      return true;
    })()`,
    "指摘の行の押下",
  );
  const opened = await waitUntil(
    win,
    `document.querySelector('[data-testid="tab-source:cobol/SYK002.cbl"]') !== null`,
    "指摘から開いたタブ",
  );
  record("指摘の行を押すとその資産のタブが開く", rows >= 3 && opened === true, `指摘 ${rows} 行`);
}

/** 検査6・7: Cytoscape の実描画と、実行順の一覧の並び。 */
async function checkGraph(win) {
  await waitUntil(win, clickTestId("activity-graph"), "呼出関係のアクティビティの押下");
  await waitUntil(win, countOf('[data-testid="graph-canvas"] canvas'), "Cytoscape の canvas 生成");

  // canvas へ実際に描かれたかを画素で確かめる。レイアウト(ELK)は非同期なので描画まで待つ。
  const painted = await waitUntil(
    win,
    `(() => {
      const canvases = [...document.querySelectorAll('[data-testid="graph-canvas"] canvas')];
      for (const canvas of canvases) {
        if (canvas.width === 0 || canvas.height === 0) continue;
        const context = canvas.getContext('2d', { willReadFrequently: true });
        if (context === null) continue;
        const data = context.getImageData(0, 0, canvas.width, canvas.height).data;
        let opaque = 0;
        for (let index = 3; index < data.length; index += 4) {
          if (data[index] !== 0) opaque += 1;
        }
        if (opaque > 0) return opaque;
      }
      return null;
    })()`,
    "canvas への描画",
  );
  record("Cytoscape がノードを描く", painted > 0, `不透明画素 ${painted} 個`);

  const job = `trace-job:${GRAPH_ID_BASE + 1}`;
  await waitUntil(win, countOf(`[data-testid="${job}"]`), "実行順の一覧の起点");
  // 節そのものを押すと資産が開く。開閉だけを起こすため、行の中の開閉ボタンを押す。
  await waitUntil(win, clickTestId(job, ".ci-trace__marker"), "起点の展開");
  const steps = await waitUntil(
    win,
    `(() => {
      const rows = [...document.querySelectorAll('[data-testid="trace-tree"] [role="treeitem"]')];
      const labels = rows.map((row) => row.querySelector('.ci-trace__label').textContent.trim());
      return labels.length >= 3 ? labels : null;
    })()`,
    "起点の下位の表示",
  );
  // engine が記録した seq のとおり、STEP010 → STEP020 の順で並ぶ。
  record(
    "実行順の一覧が seq のとおりに下位を並べる",
    steps[0] === "SYKD010" && steps[1] === "STEP010" && steps[2] === "STEP020",
    steps.slice(0, 3).join(" → "),
  );
}

/** 検査8: ルールの一覧と、有効・無効の切替の往復。 */
async function checkRules(win) {
  await waitUntil(win, clickTestId("activity-rules"), "ルールのアクティビティの押下");
  const switches = await waitUntil(win, countOf('[data-testid^="rule-switch-"]'), "ルールのトグル");
  const ids = await evaluate(win, testIdsOf('[data-testid^="rule-switch-"]'));
  record("ルールのタブがトグルを並べる", switches === 3, `${ids.join(" / ")}`);

  await waitUntil(win, clickTestId("rule-switch-R001"), "トグルの押下");
  const off = await waitUntil(
    win,
    `(() => {
      const toggle = document.querySelector('[data-testid="rule-switch-R001"]');
      return toggle !== null && toggle.getAttribute('aria-checked') === 'false' ? 'off' : null;
    })()`,
    "設定ファイル経由の反映",
  );
  record("トグルの切替が設定ファイル経由で往復する", off === "off", "R001 を無効にした");
}

/** 検査10: console のエラーと CSP 拒否。 */
function checkConsole() {
  record(
    "console にエラーと CSP 拒否が出ない",
    consoleErrors.length === 0,
    consoleErrors.length === 0 ? "0 件" : consoleErrors.slice(0, 5).join(" | "),
  );
}

/**
 * 検査9: ページ拡大でも横スクロールが出ないことを確かめる。シェルへ CSS ピクセルの幅の下限を
 * 無条件に敷くと、拡大でビューポートが縮んだときだけ横スクロールが出て、各ペインの縦スクロールと
 * 合わせて縦横 2 方向のスクロールになる(WCAG 1.4.10 の Reflow に反する)。
 */
async function checkZoomReflow(win) {
  const original = win.webContents.getZoomFactor();
  try {
    // 200% 拡大。1440px のウィンドウでビューポートは 720 CSS px 相当となり、下限 1120px を下回る。
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
      "200% 拡大でも横スクロールが出ない",
      overflow.scroll <= overflow.client,
      `scrollWidth ${overflow.scroll} / clientWidth ${overflow.client}`,
    );
  } finally {
    win.webContents.setZoomFactor(original);
    await delay(200);
  }
}

async function main() {
  if (!existsSync(RENDERER_HTML)) {
    console.error(`renderer のビルド成果物が無い: ${RENDERER_HTML}`);
    console.error("npm run build を実行してから smoke を走らせる。");
    app.exit(1);
    return;
  }

  const win = new BrowserWindow({
    // 本番の既定寸法(main の windowOptions)にそろえる。
    width: 1440,
    height: 900,
    show: false,
    webPreferences: {
      preload: FAKE_PRELOAD,
      contextIsolation: true,
      sandbox: true,
      nodeIntegration: false,
      // 表示環境に依存せず実際に描画させる(画素まで確かめるため)。
      offscreen: true,
    },
  });

  win.webContents.on("console-message", (...args) => {
    // Electron 33 は (event, level, message, line, sourceId)、以降は詳細オブジェクトを渡す。
    const detail = typeof args[1] === "object" && args[1] !== null ? args[1] : null;
    const level = detail === null ? args[1] : detail.level;
    const message = detail === null ? args[2] : detail.message;
    const isError = level === 3 || level === "error";
    if (isError || /Refused to /.test(String(message))) {
      consoleErrors.push(String(message));
    }
  });
  win.webContents.on("preload-error", (_event, path, error) => {
    consoleErrors.push(`preload の失敗 ${path}: ${error.message}`);
  });
  win.webContents.on("render-process-gone", (_event, details) => {
    consoleErrors.push(`renderer が停止した: ${details.reason}`);
  });

  try {
    await win.loadFile(RENDERER_HTML);
    await checkShell(win);
    await checkAssetTree(win);
    await checkSourceTab(win);
    await checkEditing(win);
    await checkFindings(win);
    await checkGraph(win);
    await checkRules(win);
    await checkZoomReflow(win);
    checkConsole();
  } catch (error) {
    record("smoke の進行", false, error instanceof Error ? error.message : String(error));
    checkConsole();
  }

  const failed = results.filter((result) => !result.ok);
  console.log(`\n実描画 smoke: ${results.length - failed.length} / ${results.length} 件が合格`);
  app.exit(failed.length === 0 ? 0 : 1);
}

app.commandLine.appendSwitch("disable-gpu");
app.disableHardwareAcceleration();
app.whenReady().then(main);
