import { app, BrowserWindow } from "electron";
import { join } from "node:path";
import { registerEngineIpc, stopRunningEngine } from "./ipc";
import { ensureWritable, resolvePortableUserData } from "./portable";
import { buildWindowOptions } from "./windowOptions";

/**
 * 保存先をポータブル運用へ切り替える。app.whenReady より前に呼ぶ必要がある
 * (userData は sessionData・logs・crashDumps の既定の起点でもあるため)。
 */
function applyPortableUserData(): void {
  const dataDir = resolvePortableUserData({
    isPackaged: app.isPackaged,
    exePath: app.getPath("exe"),
    ensureWritable,
  });
  if (dataDir !== null) {
    app.setPath("userData", dataDir);
  }
}

/**
 * main プロセス。renderer(React)を contextIsolation・sandbox 有効の BrowserWindow へ読み込む。
 * ネットワーク通信は行わず、dev はローカル Vite サーバー、本番はローカルの index.html から描画する。
 *
 * renderer は取り込んだ COBOL 資産の本文と engine が生成したレポート HTML を描くため、Node へ
 * 到達させない。ファイル読取と engine の起動は preload が公開する IPC 経由で main だけが行う。
 */
function createWindow(): void {
  const mainWindow = new BrowserWindow(buildWindowOptions(join(__dirname, "../preload/index.js")));

  mainWindow.on("ready-to-show", () => {
    mainWindow.show();
  });

  const rendererUrl = process.env["ELECTRON_RENDERER_URL"];
  if (rendererUrl !== undefined) {
    void mainWindow.loadURL(rendererUrl);
  } else {
    void mainWindow.loadFile(join(__dirname, "../renderer/index.html"));
  }
}

applyPortableUserData();

app.whenReady().then(() => {
  console.log("[main] app whenReady reached");
  registerEngineIpc();
  createWindow();

  app.on("activate", () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
    }
  });
});

// 終了時に子プロセスを残さない。engine は main から切り離して起動しないため、止めないと
// アプリを閉じた後も解析が走り続ける。
app.on("before-quit", () => {
  stopRunningEngine();
});

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") {
    app.quit();
  }
});
