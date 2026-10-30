// Scratch: drives the real application against the real engine once. Deleted after the run.
const { app, dialog, BrowserWindow } = require("electron");
const { mkdtempSync, mkdirSync, writeFileSync, appendFileSync, cpSync } = require("node:fs");
const { join } = require("node:path");
const { tmpdir } = require("node:os");

const root = mkdtempSync(join(tmpdir(), "ci-real-"));
const assets = join(root, "assets");
mkdirSync(join(assets, "cobol"), { recursive: true });
mkdirSync(join(assets, "copybook"), { recursive: true });
writeFileSync(
  join(assets, "cobol", "SEED.cbl"),
  ["       IDENTIFICATION DIVISION.", "       PROGRAM-ID. SUBPGM1.", "       PROCEDURE DIVISION.", "       MAIN-PROC.", "           GOBACK.", ""].join("\n"),
);
writeFileSync(join(assets, "copybook", "ERRHNDL.cpy"), "           PERFORM CHECK-A.\n           COPY INNERC.\n");
writeFileSync(join(assets, "copybook", "INNERC.cpy"), "           CONTINUE.\n");
// A seeded sample that a rule with a fix reports on (R004), with the copybooks it names.
const seeded = join(__dirname, "..", "..", "samples", "seeded");
cpSync(join(seeded, "cobol", "SYK007.cbl"), join(assets, "cobol", "SYK007.cbl"));
cpSync(join(seeded, "copybook"), join(assets, "copybook"), { recursive: true });
app.setPath("userData", join(root, "userData"));
dialog.showOpenDialog = async () => ({ canceled: false, filePaths: [assets] });
require("./out/main/index.js");

const PROGRAM = [
  " IDENTIFICATION DIVISION.",
  " PROGRAM-ID. PASTE01.",
  " DATA DIVISION.",
  " WORKING-STORAGE SECTION.",
  " 01  WS-FLAG PIC X VALUE 'N'.",
  " 01  WS-N    PIC 9(4) VALUE 1.",
  " PROCEDURE DIVISION.",
  " MAIN-SECT SECTION.",
  " MAIN-START.",
  "     PERFORM CHECK-A THRU CHECK-EXIT.",
  "     IF WS-FLAG = 'Y'",
  "         GO TO MAIN-END",
  "     END-IF.",
  "     COPY ERRHNDL.",
  "     CALL 'SUBPGM1' USING WS-FLAG.",
  "     COMPUTE WS-N = WS-N * 2.",
  " MAIN-END.",
  "     STOP RUN.",
  " CHECK-SECT SECTION.",
  " CHECK-A.",
  "     MOVE 'Y' TO WS-FLAG.",
  " CHECK-EXIT.",
  "     EXIT.",
];
const screen = (from, to, top, bottom) => [
  " EDIT       USER01.SRC.COBOL(PASTE01) - 01.00               Columns 00007 00072",
  " Command ===>                                                  Scroll ===> CSR",
  ...(top ? [" ****** ***************************** Top of Data ******************************"] : []),
  ...PROGRAM.slice(from - 1, to).map((text, i) => ` ${String((from + i) * 100).padStart(6, "0")} ${text}`),
  ...(bottom ? [" ****** **************************** Bottom of Data ****************************"] : []),
];
// The second screen repeats two lines of the first, the second of them under a change flag.
const second = screen(12, 23, false, true);
second[3] = second[3].replace(/^ \d{6}/, " ==CHG>");
const PASTED = [...screen(1, 13, true, false), ...second].join("\r\n");

const delay = (ms) => new Promise((done) => setTimeout(done, ms));
const q = (testId) => `document.querySelector('[data-testid="${testId}"]')`;
const rows = (testId) =>
  `Array.from(document.querySelectorAll('[data-testid="${testId}"] [role="treeitem"]')).map((r) => r.textContent).join(" | ")`;

async function main() {
  let win;
  while ((win = BrowserWindow.getAllWindows()[0]) === undefined) {
    await delay(100);
  }
  const run = (expression) => win.webContents.executeJavaScript(expression, true);
  const until = async (expression, what, timeout = 90000) => {
    const deadline = Date.now() + timeout;
    while (Date.now() < deadline) {
      const value = await run(expression).catch(() => null);
      if (value) return value;
      await delay(150);
    }
    throw new Error(`timed out: ${what}`);
  };
  const set = (testId, value) =>
    run(`(() => { const el = ${q(testId)};
      const proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      Object.getOwnPropertyDescriptor(proto, "value").set.call(el, ${JSON.stringify(value)});
      el.dispatchEvent(new Event("input", { bubbles: true })); return true; })()`);
  const shot = async (name) => {
    await delay(700);
    writeFileSync(join(root, `${name}.png`), (await win.webContents.capturePage()).toPNG());
  };

  win.unmaximize();
  win.setSize(1440, 900);
  await until(`document.querySelector(".ci-titlebar__folder") !== null`, "title bar");
  let t = Date.now();
  await run(`document.querySelector(".ci-titlebar__folder").click()`);
  await until(`${q("tree-cobol/SEED.cbl")} !== null`, "first scan lists the seed file");
  console.log("folder open -> tree ms:", Date.now() - t);

  await run(`${q("explorer-import")}.click()`);
  await until(`${q("import-paste")} !== null`, "import dialog");
  await set("import-paste", PASTED);
  await set("import-destdir", "cobol");
  await set("import-filename", "PASTE01.cbl");
  console.log("import-read:", await until(`${q("import-read")}?.textContent`, "result line"));
  t = Date.now();
  await run(`${q("import-save")}.click()`);
  await until(`${q("tree-cobol/PASTE01.cbl")} !== null`, "the pasted file appears after the automatic scan");
  console.log("save -> listed ms:", Date.now() - t);
  await run(`${q("import-close")}?.click()`);

  await run(`${q("tree-cobol/PASTE01.cbl")}.click()`);
  await until(`${q("paragraph-pane")} !== null`, "the pane beside the code");
  console.log("outline rows:", await until(rows("outline-tree"), "outline rows"));
  await run(`${q("outline-MAIN-SECT/MAIN-START")}.click()`);
  await run(`${q("pane-tab-trace")}.click()`);
  console.log("trace rows:", await until(rows("paragraph-trace-tree"), "trace rows"));
  await shot("1-trace");

  // A copybook written from outside the application, then the program saved again from inside it
  // would need the editor; the second scan is asked for through the import of a second file.
  appendFileSync(join(assets, "copybook", "ERRHNDL.cpy"), "           PERFORM CHECK-EXIT.\n");
  await run(`${q("explorer-import")}.click()`);
  await until(`${q("import-paste")} !== null`, "import dialog again");
  await set("import-paste", PASTED.replace(/PASTE01/g, "PASTE02"));
  await set("import-destdir", "cobol");
  await set("import-filename", "PASTE02.cbl");
  t = Date.now();
  await until(`${q("import-save")} && !${q("import-save")}.disabled`, "save enabled");
  await run(`${q("import-save")}.click()`);
  await until(`${q("tree-cobol/PASTE02.cbl")} !== null`, "the second file appears after the incremental scan");
  console.log("second save -> listed ms:", Date.now() - t);
  await run(`${q("import-close")}?.click()`);
  await delay(1500);
  console.log("trace rows after the copybook changed:", await run(rows("paragraph-trace-tree")));
  await shot("2-after");

  // The nested COPY: the expansion of ERRHNDL names INNERC over the lines it brings in.
  await run(`${q("tab-source:cobol/PASTE01.cbl")}?.click()`);
  await until(`document.querySelector(".ci-copy__toggle") !== null`, "the COPY statement's toggle");
  console.log(
    "copy zone path rows:",
    await until(
      `Array.from(document.querySelectorAll(".ci-copy__line--path")).map((r) => r.textContent).join(" | ")`,
      "the path rows of the nested COPY",
    ),
  );
  const zone = `(() => { const t = document.querySelector(".ci-copy__toggle"); const l = document.querySelector(".ci-copy__lines"); return JSON.stringify({ expanded: t?.getAttribute("aria-expanded"), hidden: l?.hidden, label: t?.textContent, lines: l?.textContent.slice(0, 80) }); })()`;
  console.log("zone right after the press:", await run(zone));
  await delay(2500);
  console.log("zone 2.5 s later:", await run(zone));
  await shot("3-copy");

  // The analysis, then a finding with a fix, the fix tab's write-out, the report and the translation.
  t = Date.now();
  await run(`${q("run-analysis-all")}.click()`);
  await until(`${q("run-stage")} !== null`, "the run to start", 15000).catch(() => console.log("run-stage never shown"));
  await until(`${q("run-stage")} === null`, "the run to end");
  console.log("status bar:", await run(`document.querySelector(".ci-statusbar")?.textContent`));
  await run(`${q("panel-tab-output")}?.click()`);
  await delay(300);
  console.log("output:", await run(`${q("panelview-output")}?.textContent.slice(-600)`));
  await run(`${q("panel-tab-problems")}?.click()`);
  await until(`document.querySelectorAll('[data-testid^="finding-"]').length > 0`, "findings", 20000);
  console.log("analysis ms:", Date.now() - t);
  console.log(
    "findings:",
    await run(`Array.from(document.querySelectorAll('[data-testid^="finding-"]')).filter((r) => !r.dataset.testid.startsWith("finding-graph")).map((r) => r.textContent.slice(0, 60)).join(" || ")`),
  );
  const keys = await run(`Array.from(document.querySelectorAll('[data-testid^="finding-"]')).map((r) => r.dataset.testid).filter((id) => !id.startsWith("finding-graph"))`);
  let fixRow = null;
  for (const key of keys) {
    await run(`${q(key)}.click()`);
    await delay(250);
    if (await run(`${q("problem-detail-fix")} !== null`)) {
      fixRow = key;
      break;
    }
  }
  console.log("row with a fix:", fixRow);
  await delay(300);
  if (await run(`${q("problem-detail-fix")} !== null`)) {
    await run(`${q("problem-detail-fix")}.click()`);
    await until(`${q("fix-apply")} !== null && !${q("fix-apply")}.disabled`, "the fix tab's write-out button");
    await shot("4-fix");
    await run(`${q("fix-apply")}.click()`);
    console.log("fix toast:", await until(`Array.from(document.querySelectorAll(".ci-toast")).map((n) => n.textContent).find((x) => x.includes("書き出しました"))`, "the write-out notice"));
  }
  await run(`${q("activity-report")}.click()`);
  await until(`${q("report-generate-html")} !== null && !${q("report-generate-html")}.disabled`, "the report button");
  await run(`${q("report-generate-html")}.click()`);
  await until(`${q("report-html")} !== null`, "the report preview");
  console.log("report frame present:", await run(`${q("report-html")} !== null`));
  await shot("5-report");
  await run(`${q("tree-cobol/PASTE01.cbl")}.click()`);
  await until(`${q("paragraph-pane")} !== null`, "the source tab in front again");
  console.log("front tab:", await run(`document.querySelector('[role="tab"][aria-selected="true"][data-testid^="tab-"]')?.dataset.testid`), "transpile disabled:", await run(`${q("activity-transpile")}.disabled`));
  await run(`${q("activity-transpile")}.click()`);

  console.log(
    "generated code lines:",
    await until(`document.querySelectorAll('[data-testid="transpile-generated"] .view-line').length`, "the generated code"),
  );
  await shot("6-transpile");
  console.log("screenshots in:", root);
}

app.whenReady().then(() =>
  main()
    .catch((error) => {
      console.log("FAILED:", error.message);
      process.exitCode = 1;
    })
    .finally(() => app.quit()),
);
