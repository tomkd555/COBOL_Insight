import { app, BrowserWindow } from "electron";
import * as path from "path";

/**
 * 配布構成の先行検証用の最小Electronアプリ。空ウィンドウに製品名を表示するのみで、
 * 画面本体(React)はM8で導入する。
 */
function createWindow(): void {
    const window = new BrowserWindow({
        width: 800,
        height: 600,
        title: "COBOL Insight"
    });
    window.loadFile(path.join(__dirname, "..", "..", "index.html"));
}

app.whenReady().then(createWindow);

app.on("window-all-closed", () => {
    if (process.platform !== "darwin") {
        app.quit();
    }
});
