/**
 * The monaco-editor stub used by Vitest. The alias in vitest.config.ts redirects every monaco-editor
 * subpath to this one file.
 *
 * There are two reasons. Monaco needs a Web Worker and real DOM measurement, neither of which jsdom
 * provides, so unit tests mock vendor/monacoEditor rather than run the real thing. And the real
 * package is roughly 10MB of ESM whose worker import (?worker&inline) builds a second bundle at test
 * time, which would slow down even tests that merely render a screen.
 *
 * The stub is therefore loadable but not usable: anything actually called throws with the reason.
 * Types come from the real declarations through tsc, so only the runtime shape matters here.
 */

function unavailable(name: string): never {
  throw new Error(`Monaco's ${name} is unavailable under Vitest; mock vendor/monacoEditor instead.`);
}

/** Stands in for the default export of monaco-editor/editor/editor.worker?worker&inline. */
export default class StubWorker {
  constructor() {
    unavailable("Worker");
  }
}

/** Stands in for the monaco.editor namespace. */
export const editor = {
  create: () => unavailable("editor.create"),
  createDiffEditor: () => unavailable("editor.createDiffEditor"),
  defineTheme: () => undefined,
  setTheme: () => undefined,
  setModelLanguage: () => undefined,
  getEditors: () => [],
};

/** Stands in for the monaco.languages namespace. */
export const languages = {
  register: () => undefined,
  setMonarchTokensProvider: () => undefined,
  setLanguageConfiguration: () => undefined,
};

/** Stands in for the monaco.Range constructor. */
export class Range {
  constructor(
    readonly startLineNumber: number,
    readonly startColumn: number,
    readonly endLineNumber: number,
    readonly endColumn: number,
  ) {}
}
