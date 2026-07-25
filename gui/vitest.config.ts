import { resolve } from "node:path";
import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

/**
 * Vitest 設定。renderer(React)は jsdom、main(IPC/CLI 連携の純ロジック)は node 環境で走らせる。
 * electron-vite ビルドとは独立に、Java 側テストとも疎結合で走る。実 CLI を起動する統合テスト
 * (*.integration.test.ts)は Java 成果物を要するため、java が使えないときは自己 skip する。
 *
 * monaco-editor は alias でスタブへ差し替える。Monaco は Web Worker と実 DOM の寸法計測を要し
 * jsdom では動かないため、単体テストは vendor/monacoEditor をモックして動かす。実体を読み込むと
 * 画面を描くだけのテストまで極端に遅くなる(理由と代替の形はスタブ側に記す)。
 *
 * Monaco と Cytoscape の実描画(行の y 座標・canvas の画素・CSP 拒否の有無)は、Electron を
 * offscreen で起動する smoke が受け持つ(npm run smoke:render / smoke/render.cjs)。
 */
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: [
      {
        find: /^monaco-editor(\/.*)?$/,
        replacement: resolve(__dirname, "src/renderer/src/vendor/monaco-editor.stub.ts"),
      },
    ],
  },
  test: {
    globals: true,
    include: ["src/**/*.{test,spec}.{ts,tsx}"],
    environmentMatchGlobs: [
      ["src/renderer/**", "jsdom"],
      ["src/main/**", "node"],
    ],
    setupFiles: ["./vitest.setup.ts"],
  },
});
