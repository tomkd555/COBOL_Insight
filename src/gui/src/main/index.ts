import { app, BrowserWindow, Menu, nativeTheme, screen } from "electron";
import { join } from "node:path";
import { registerIpc, settingsPath, stopRunningEngine } from "./ipc";
import { readSettings } from "./fs/settings";
import { userDataFileSystem } from "./nodeFs";
import { ensureWritable, resolvePortableUserData } from "./fs/portable";
import { buildWindowOptions } from "./window";

/**
 * Switches storage to the portable location. This must run before app.whenReady, because userData is
 * also the base for sessionData, logs and crashDumps.
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
 * The main process. It loads the renderer into a BrowserWindow with contextIsolation and sandbox on:
 * from the local Vite server in development and from the local index.html in a distribution. No
 * network traffic is involved either way.
 */
async function createWindow(): Promise<void> {
  // The frame is painted before the renderer resolves the theme, so the stored choice is read here.
  const { theme } = await readSettings(userDataFileSystem, settingsPath());
  const dark = theme === "dark" || (theme !== "light" && nativeTheme.shouldUseDarkColors);
  const window = new BrowserWindow(
    buildWindowOptions(
      join(__dirname, "../preload/index.js"),
      screen.getPrimaryDisplay().workAreaSize,
      dark,
    ),
  );

  window.on("ready-to-show", () => {
    window.show();
  });

  const rendererUrl = process.env["ELECTRON_RENDERER_URL"];
  if (rendererUrl !== undefined) {
    void window.loadURL(rendererUrl);
  } else {
    void window.loadFile(join(__dirname, "../renderer/index.html"));
  }
}

applyPortableUserData();

app.whenReady().then(() => {
  // Drop the default File/Edit/View menu: the GUI has no menu commands, its English items clash with
  // the Japanese interface, and it duplicates the in-app title bar. In development
  // (ELECTRON_RENDERER_URL is set) it stays, so DevTools remains reachable.
  if (process.env["ELECTRON_RENDERER_URL"] === undefined) {
    Menu.setApplicationMenu(null);
  }
  registerIpc();
  void createWindow();

  app.on("activate", () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      void createWindow();
    }
  });
});

// Leave no child process behind. The engine is not detached from main, so without this the analysis
// would keep running after the window is closed.
app.on("before-quit", () => {
  stopRunningEngine();
});

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") {
    app.quit();
  }
});
