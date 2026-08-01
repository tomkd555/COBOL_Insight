import type { BrowserWindowConstructorOptions } from "electron";

/**
 * BrowserWindow の最小寸法。幅は styles/tokens.css の --ci-min-width と同じ 1280 を保つ
 * (これを下回るとタブバー・ステータスバーの固定幅要素が画面外へ出て横スクロールを生む)。
 * 高さはタイトルバー(44px)とステータスバー(24px)の固定領域に、タブバー・実行中インジケータ・
 * 一覧表示が成立する本体領域を加えた値とし、幅と同じく一般的なデスクトップの最小解像度である
 * 1280x720 に合わせる。
 */
export const MIN_WINDOW_WIDTH = 1280;
export const MIN_WINDOW_HEIGHT = 720;

/** createWindow が BrowserWindow へ渡すコンストラクタオプションを組み立てる。 */
export function buildWindowOptions(preloadPath: string): BrowserWindowConstructorOptions {
  return {
    width: 1280,
    height: 800,
    minWidth: MIN_WINDOW_WIDTH,
    minHeight: MIN_WINDOW_HEIGHT,
    show: false,
    title: "COBOL Insight",
    backgroundColor: "#ECEEF1",
    webPreferences: {
      preload: preloadPath,
      contextIsolation: true,
      sandbox: true,
      nodeIntegration: false,
    },
  };
}
