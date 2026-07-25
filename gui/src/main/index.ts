import { app, BrowserWindow } from "electron";
import { join } from "node:path";
import { registerEngineIpc } from "./ipc";
import { ensureWritable, resolvePortableUserData } from "./portable";

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
 */
function createWindow(): void {
  const mainWindow = new BrowserWindow({
    width: 1280,
    height: 800,
    show: false,
    title: "COBOL Insight",
    backgroundColor: "#ECEEF1",
    webPreferences: {
      preload: join(__dirname, "../preload/index.js"),
      contextIsolation: true,
      sandbox: true,
      nodeIntegration: false,
    },
  });

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

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") {
    app.quit();
  }
});
