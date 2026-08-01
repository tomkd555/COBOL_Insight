import type { BrowserWindowConstructorOptions } from "electron";

/**
 * BrowserWindow の最小寸法。幅は styles/tokens.css の --ci-min-width と同じ 1120 を保つ
 * (これを下回るとタブバー・ステータスバーの固定幅要素が画面外へ出て横スクロールを生む)。
 * 1120px の根拠は各画面の最小の内訳で、ソースビューアが対訳 300 + ハンドル 6 + 原本 600 +
 * 余白 16 = 922px、資産一覧が 657 + 6 + 400 = 1063px であり、いずれもこの幅に収まる。
 * 高さはタイトルバー(34px)とステータスバー(24px)の固定領域に、タブバー・実行中インジケータ・
 * 一覧とコード面が同時に成立する本体領域を加えた値とする。
 */
export const MIN_WINDOW_WIDTH = 1120;
export const MIN_WINDOW_HEIGHT = 700;

/** ウィンドウの寸法(画素)。 */
export interface WindowSize {
  readonly width: number;
  readonly height: number;
}

/** 既定の寸法。一覧とコード面を同時に出しても双方が役目を果たす広さを初期値とする。 */
const PREFERRED_SIZE: WindowSize = { width: 1440, height: 900 };

/**
 * 希望する寸法を作業領域(タスクバー等を除いた画面の大きさ)へ収める。作業領域より大きな窓は
 * 端が画面の外へ出て、タイトルバーやステータスバーへ届かなくなる。
 * 作業領域が最小寸法より狭い場合は最小寸法を返す(minWidth/minHeight が効くため、これ以上は
 * 縮められない)。
 */
export function clampWindowSize(preferred: WindowSize, workArea: WindowSize): WindowSize {
  return {
    width: Math.max(MIN_WINDOW_WIDTH, Math.min(preferred.width, workArea.width)),
    height: Math.max(MIN_WINDOW_HEIGHT, Math.min(preferred.height, workArea.height)),
  };
}

/**
 * createWindow が BrowserWindow へ渡すコンストラクタオプションを組み立てる。
 * workArea を渡すと既定の寸法をその中へ収める(渡さない場合は既定の寸法をそのまま用いる)。
 */
export function buildWindowOptions(
  preloadPath: string,
  workArea?: WindowSize,
): BrowserWindowConstructorOptions {
  const size = workArea === undefined ? PREFERRED_SIZE : clampWindowSize(PREFERRED_SIZE, workArea);
  return {
    width: size.width,
    height: size.height,
    minWidth: MIN_WINDOW_WIDTH,
    minHeight: MIN_WINDOW_HEIGHT,
    center: true,
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
