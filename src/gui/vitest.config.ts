import { resolve } from "node:path";
import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

/**
 * Vitest configuration, split into two projects: main-process code (argv assembly, path guards,
 * artefact readers) runs under node, and renderer code (reducers, pure models, components) runs
 * under jsdom.
 *
 * monaco-editor is aliased to a stub: Monaco needs a Web Worker and real DOM measurement, neither of
 * which jsdom provides, and loading the real ~10MB ESM bundle would slow every render test down.
 * Real Monaco rendering is covered by the offscreen Electron smoke (npm run smoke:render).
 *
 * The integration test (*.integration.test.ts) spawns the real engine and self-skips when the
 * installDist artefact or JAVA_HOME is missing.
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
    projects: [
      {
        extends: true,
        test: {
          name: "main",
          globals: true,
          environment: "node",
          include: ["src/main/**/*.{test,spec}.ts", "src/shared/**/*.{test,spec}.ts"],
        },
      },
      {
        extends: true,
        test: {
          name: "renderer",
          globals: true,
          environment: "jsdom",
          include: ["src/renderer/**/*.{test,spec}.{ts,tsx}"],
          setupFiles: ["./vitest.setup.ts"],
        },
      },
    ],
  },
});
