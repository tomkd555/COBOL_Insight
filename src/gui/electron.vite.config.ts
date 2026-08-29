import { resolve } from "node:path";
import { defineConfig, externalizeDepsPlugin } from "electron-vite";
import react from "@vitejs/plugin-react";

/**
 * electron-vite configuration: three separate builds (main, preload, renderer).
 *
 * The renderer uses locally bundled assets only and never references a CDN or a remote font.
 * The main build leaves its dependencies (sql.js and its wasm/emscripten payload) unbundled and
 * resolves them from node_modules at runtime; the renderer bundles everything, React included.
 */
export default defineConfig({
  main: {
    plugins: [externalizeDepsPlugin()],
    build: {
      outDir: "out/main",
      rollupOptions: { input: { index: resolve(__dirname, "src/main/index.ts") } },
    },
  },
  preload: {
    plugins: [externalizeDepsPlugin()],
    build: {
      outDir: "out/preload",
      rollupOptions: { input: { index: resolve(__dirname, "src/preload/index.ts") } },
    },
  },
  renderer: {
    root: "src/renderer",
    build: {
      outDir: "out/renderer",
      rollupOptions: { input: { index: resolve(__dirname, "src/renderer/index.html") } },
    },
    plugins: [react()],
  },
});
