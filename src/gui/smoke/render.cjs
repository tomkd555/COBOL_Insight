/*
 * 実描画 smoke。ビルド済みの renderer(out/renderer/index.html)を Electron の offscreen
 * レンダリングで実際に描かせ、jsdom では確かめられない次の点を検査する。
 *
 *   1. 9タブが列挙される(シェルが起動している)
 *   2. Cytoscape が canvas を作り、そこへノードを描く(空でない画素がある)
 *   3. Monaco が起動し、view-line が複数行それぞれ異なる y 座標に並ぶ
 *      (CSP の style-src に 'unsafe-inline' が無いと全行が同じ y へ重なる。この検査がそれを捕らえる)
 *   4. Monaco DiffEditor が左右2ペインで起動し、差分の装飾を描く
 *   5. レポート HTML が sandbox="" の iframe として実際に読み込まれる
 *   6. 設定のルール表が並び、説明を開くと検出条件と例が出て、利用者定義ルールが一覧に載る
 *   7. 端末取込が貼り付けた本文を桁で切り出し、桁定規付きのプレビューへ等幅で並べる
 *   8. 200% 拡大でも横スクロールが出ない(縦横 2 方向のスクロールにならない)
 *   9. console にエラーと CSP 拒否("Refused to ...")が出ない
 *
 * engine CLI は起動しない。preload を smoke/fake-preload.cjs へ差し替え、window.cobolInsight を
 * 固定データで満たして画面を results 状態まで進める。本番と同じ contextIsolation:true・sandbox:true で
 * 読み込む。失敗した検査があれば非ゼロで終了する。
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

/** 表示文字が一致するボタンを押す式。 */
function clickButton(label) {
  return `(() => {
    const target = [...document.querySelectorAll('button')].find((b) => b.textContent.trim() === ${JSON.stringify(label)});
    if (target === undefined) return false;
    target.click();
    return true;
  })()`;
}

/** 表示文字が一致するタブを押す式。 */
function clickTab(label) {
  return `(() => {
    const target = [...document.querySelectorAll('[role="tab"]')].find((b) => b.textContent.trim() === ${JSON.stringify(label)});
    if (target === undefined) return false;
    target.click();
    return true;
  })()`;
}

/**
 * select の値を変える式。React は value を追跡するため、プロトタイプの setter で値を入れてから
 * change を起こす(要素へ直接代入すると React が変更を検知しない)。
 */
function selectOption(selector, value) {
  return `(() => {
    const element = document.querySelector(${JSON.stringify(selector)});
    if (element === null) return false;
    const setter = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value').set;
    setter.call(element, ${JSON.stringify(value)});
    element.dispatchEvent(new Event('change', { bubbles: true }));
    return true;
  })()`;
}

/** 9タブの列挙(検査1)。 */
async function checkTabs(win) {
  const labels = await waitUntil(
    win,
    `(() => {
      const tabs = [...document.querySelectorAll('[role="tab"]')].map((t) => t.textContent.trim());
      return tabs.length === 9 ? tabs : null;
    })()`,
    "9タブの列挙",
  );
  record("9タブが列挙される", labels.length === 9, labels.join(" / "));
}

/** 取込を起点に解析(scan→lint→sql-advise)が進み results 状態になることを見る。 */
async function runAnalysis(win) {
  await waitUntil(win, clickButton("＋ インポート"), "インポートボタンの押下");
  // 取込だけで解析まで進む。解析実行ボタンは押さない(押さずに一覧が出ることがこの検査の主眼)。
  const rows = await waitUntil(
    win,
    `(() => {
      const count = document.querySelectorAll('.ci-asset-row__name').length;
      return count > 0 ? count : null;
    })()`,
    "資産一覧の表示",
  );
  record("取込がそのまま解析を走らせ資産一覧を表示する", rows > 0, `資産 ${rows} 件`);
}

/** Cytoscape の実描画(検査2)。 */
async function checkGraph(win) {
  await waitUntil(win, clickTab("呼出関係図"), "呼出関係図タブの押下");
  const canvases = await waitUntil(
    win,
    `(() => {
      const count = document.querySelectorAll('.ci-graph__canvas canvas').length;
      return count > 0 ? count : null;
    })()`,
    "Cytoscape の canvas 生成",
  );
  record("Cytoscape が canvas を作る", canvases > 0, `canvas ${canvases} 枚`);

  const nodes = await waitUntil(
    win,
    `(() => {
      const count = document.querySelectorAll('.ci-graph__nodes-list [role="option"]').length;
      return count > 0 ? count : null;
    })()`,
    "ノード一覧の生成",
  );

  // canvas へ実際に描かれたかを画素で確かめる。レイアウト(ELK)は非同期なので描画まで待つ。
  const painted = await waitUntil(
    win,
    `(() => {
      const canvases = [...document.querySelectorAll('.ci-graph__canvas canvas')];
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
  record("Cytoscape がノードを描く", painted > 0, `ノード一覧 ${nodes} 件・不透明画素 ${painted} 個`);

  // 図と一覧の同期(キーボードからノードを選べること)を、選択の反映で確かめる。
  await waitUntil(
    win,
    `(() => {
      const option = document.querySelector('.ci-graph__nodes-list [role="option"]');
      if (option === null) return false;
      option.click();
      return true;
    })()`,
    "ノード一覧からの選択",
  );
  const selected = await waitUntil(
    win,
    `(() => {
      const option = document.querySelector('.ci-graph__nodes-list [role="option"][aria-selected="true"]');
      const detail = document.querySelector('.ci-graph-detail__name');
      return option !== null && detail !== null ? detail.textContent.trim() : null;
    })()`,
    "詳細ペインへの反映",
  );
  record("ノード一覧の選択が詳細ペインへ届く", selected.length > 0, `選択ノード ${selected}`);
}

/** Monaco の実描画(検査3)。行が重なっていないことを y 座標で確かめる。 */
async function checkViewer(win) {
  await waitUntil(win, clickTab("ソースビューア"), "ソースビューアタブの押下");
  await waitUntil(win, selectOption("#ci-viewer-file", "cobol/SYK001.cbl"), "表示ファイルの選択");
  const lines = await waitUntil(
    win,
    `(() => {
      const tops = [...document.querySelectorAll('.view-line')].map((line) => Math.round(line.getBoundingClientRect().top));
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

  const editors = await evaluate(win, `document.querySelectorAll('.ci-code .monaco-editor').length`);
  record("Monaco が両ペインで起動する", editors >= 2, `エディタ ${editors} 台`);

  const identification = await waitUntil(
    win,
    `(() => {
      const count = document.querySelectorAll('.ci-code__identification').length;
      return count > 0 ? count : null;
    })()`,
    "識別欄の装飾",
  );
  record("識別欄(73〜80桁)の装飾が描かれる", identification > 0, `装飾 ${identification} 箇所`);
}

/** Monaco DiffEditor の実描画(検査4)。差分の行が重なっていないことを y 座標で確かめる。 */
async function checkDiff(win) {
  await waitUntil(win, clickTab("修正案の差分"), "修正案の差分タブの押下");
  const candidates = await waitUntil(
    win,
    `(() => {
      const count = document.querySelectorAll('.ci-fix-list__items [role="option"]').length;
      return count > 0 ? count : null;
    })()`,
    "修正案一覧の生成",
  );
  record("修正案一覧が並ぶ", candidates > 0, `修正案 ${candidates} 件`);

  const editors = await waitUntil(
    win,
    `(() => {
      const count = document.querySelectorAll('.ci-diff__editor .monaco-editor').length;
      return count >= 2 ? count : null;
    })()`,
    "DiffEditor の左右ペイン起動",
  );
  const lines = await waitUntil(
    win,
    `(() => {
      const tops = [...document.querySelectorAll('.ci-diff__editor .view-line')].map((line) => Math.round(line.getBoundingClientRect().top));
      return tops.length >= 5 ? tops : null;
    })()`,
    "DiffEditor の行描画",
  );
  const unique = new Set(lines);
  record(
    "DiffEditor の行が別々の y 座標に並ぶ",
    unique.size >= 5 && unique.size * 2 >= lines.length,
    `エディタ ${editors} 台・view-line ${lines.length} 本・異なる y ${unique.size} 個`,
  );

  // 差分の装飾(行の挿入・変更)が実際に描かれるかを確かめる。
  const decorations = await waitUntil(
    win,
    `(() => {
      const count = document.querySelectorAll('.ci-diff__editor .line-insert, .ci-diff__editor .char-insert, .ci-diff__editor .line-delete, .ci-diff__editor .char-delete').length;
      return count > 0 ? count : null;
    })()`,
    "差分の装飾",
  );
  record("差分の装飾が描かれる", decorations > 0, `装飾 ${decorations} 箇所`);

  // コピー句の修正案は engine が修正後ソースを書かないため、unified diff で示す。
  await waitUntil(
    win,
    `(() => {
      const options = [...document.querySelectorAll('.ci-fix-list__items [role="option"]')];
      const target = options.find((option) => option.textContent.includes('コピー句'));
      if (target === undefined) return false;
      target.click();
      return true;
    })()`,
    "コピー句の修正案の選択",
  );
  const unified = await waitUntil(
    win,
    `(() => {
      const body = document.querySelector('.ci-diff__unified-body');
      return body !== null && body.textContent.includes('PIC X(12)') ? body.textContent.length : null;
    })()`,
    "unified diff の表示",
  );
  record("コピー句の修正案が unified diff を出す", unified > 0, `本文 ${unified} 文字`);
}

/** レポート HTML の sandbox iframe 描画(検査5)。 */
async function checkReport(win) {
  await waitUntil(win, clickTab("レポート出力"), "レポート出力タブの押下");
  await waitUntil(win, clickButton("レポートを書き出す"), "レポート書き出しの押下");
  const metrics = await waitUntil(
    win,
    `(() => {
      const count = document.querySelectorAll('.ci-report-preview__metric').length;
      return count > 0 ? count : null;
    })()`,
    "レポート指標の表示",
  );
  const framed = await waitUntil(
    win,
    `(() => {
      const frame = document.querySelector('.ci-report-preview__frame');
      return frame !== null && frame.getAttribute('srcdoc') !== null ? frame.getAttribute('sandbox') === '' : null;
    })()`,
    "sandbox iframe の生成",
  );
  // 子フレームが実際に読み込まれたかは main プロセス側のフレーム木で確かめる(sandbox="" の
  // 不透明オリジンのため、renderer からは中身を参照できない)。
  const frames = win.webContents.mainFrame.framesInSubtree.length;
  record(
    "レポート HTML が sandbox iframe で描かれる",
    metrics > 0 && framed === true && frames >= 2,
    `指標 ${metrics} 件・sandbox=""・フレーム ${frames} 枚`,
  );
}

/** 設定画面のルール表・説明・利用者定義ルール(検査6)。 */
async function checkSettings(win) {
  await waitUntil(win, clickTab("設定"), "設定タブの押下");
  const rows = await waitUntil(
    win,
    `(() => {
      const count = document.querySelectorAll('.ci-rules__row').length;
      return count > 0 ? count : null;
    })()`,
    "ルール表の生成",
  );
  // 件数は偽 preload が返すカタログ(組み込み2件・利用者定義1件)と一致させる。
  record("設定がルール表を並べる", rows === 3, `ルール ${rows} 件`);

  // 開いていなければ押す形にし、待機の再評価でトグルが往復しないようにする。
  const items = await waitUntil(
    win,
    `(() => {
      const toggle = document.querySelector('.ci-rules__detail-toggle');
      if (toggle === null) return null;
      if (toggle.getAttribute('aria-expanded') !== 'true') {
        toggle.click();
        return null;
      }
      const count = document.querySelectorAll('.ci-rule-detail__item').length;
      return count > 0 ? count : null;
    })()`,
    "ルール説明の展開",
  );
  record("ルールの説明が4項目を並べる", items === 4, `説明の項目 ${items} 件`);

  const examples = await win.webContents.executeJavaScript(
    "document.querySelectorAll('.ci-rule-detail__code').length",
  );
  record("説明が該当例と修正例を並べる", examples === 2, `例 ${examples} 件`);

  const userRules = await waitUntil(
    win,
    `(() => {
      const count = document.querySelectorAll('.ci-user-rules__item').length;
      return count > 0 ? count : null;
    })()`,
    "利用者定義ルールの一覧",
  );
  record("利用者定義ルールが一覧に並ぶ", userRules === 1, `利用者定義 ${userRules} 件`);
}

/**
 * 端末取込のプレビュー(検査7)。React は textarea の value を追跡するため、プロトタイプの
 * setter で値を入れてから input を起こす(要素へ直接代入すると React が変更を検知しない)。
 */
async function checkImport(win) {
  await waitUntil(win, clickTab("端末取込"), "端末取込タブの押下");
  await waitUntil(
    win,
    `(() => {
      const area = document.querySelector('.ci-import__paste');
      if (area === null) return false;
      const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set;
      setter.call(area, '000100 IDENTIFICATION DIVISION.\\n000200 PROGRAM-ID. SYK001.');
      area.dispatchEvent(new Event('input', { bubbles: true }));
      return true;
    })()`,
    "本文の貼り付け",
  );
  await waitUntil(
    win,
    `(() => {
      const field = document.querySelector('#ci-import-col-from');
      if (field === null) return false;
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
      setter.call(field, '8');
      field.dispatchEvent(new Event('input', { bubbles: true }));
      return true;
    })()`,
    "開始桁の指定",
  );
  const shown = await waitUntil(
    win,
    `(() => {
      const texts = [...document.querySelectorAll('.ci-import-preview__text')].map((e) => e.textContent);
      const ruler = document.querySelector('.ci-import-preview__ruler');
      if (texts.length !== 2 || ruler === null) return null;
      return texts[0] === 'IDENTIFICATION DIVISION.' && ruler.textContent.includes('1234567890')
        ? texts.length
        : null;
    })()`,
    "取込プレビューの表示",
  );
  record("端末取込が桁を切り出してプレビューへ並べる", shown === 2, `プレビュー ${shown} 行`);
}

/** console のエラーと CSP 拒否(検査8)。 */
function checkConsole() {
  record(
    "console にエラーと CSP 拒否が出ない",
    consoleErrors.length === 0,
    consoleErrors.length === 0 ? "0 件" : consoleErrors.slice(0, 5).join(" | "),
  );
}

/**
 * ページ拡大でも横スクロールが出ないことを確かめる。シェルへ CSS ピクセルの幅の下限を
 * 無条件に敷くと、拡大でビューポートが縮んだときだけ横スクロールが出て、各ペインの縦スクロールと
 * 合わせて縦横 2 方向のスクロールになる(WCAG 1.4.10 の Reflow に反する)。
 */
async function checkZoomReflow(win) {
  const original = win.webContents.getZoomFactor();
  try {
    // 200% 拡大。1400px のウィンドウでビューポートは 700 CSS px 相当となり、下限 1280px を下回る。
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
    width: 1400,
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
    await checkTabs(win);
    await runAnalysis(win);
    await checkGraph(win);
    await checkViewer(win);
    await checkDiff(win);
    await checkReport(win);
    await checkSettings(win);
    await checkImport(win);
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
