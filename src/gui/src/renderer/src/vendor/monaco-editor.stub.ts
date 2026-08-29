/**
 * The monaco-editor stand-in used by Vitest. The alias in vitest.config.ts redirects every
 * monaco-editor subpath to this one file.
 *
 * There are two reasons. Monaco needs a Web Worker and real DOM measurement, neither of which jsdom
 * provides, and the real package is roughly 10MB of ESM whose worker import (?worker&inline) builds
 * a second bundle at test time, which would slow down even tests that merely render a screen.
 *
 * It is a fake, not a mock: enough of the API answers for a screen holding an editor to mount and
 * unmount without throwing, and no more. Nothing here highlights, lays out or measures anything, so
 * no test may assert on what Monaco draws — that is what the offscreen Electron smoke is for
 * (npm run smoke:render). Types come from the real declarations through tsc, so only the runtime
 * shape matters here.
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

/** What a disposable returns. Every listener and registration hands one of these back. */
const disposable = { dispose: (): void => undefined };

/** A text model: the text and its identity, with none of the tokenising. */
class StubModel {
  private value: string;
  private disposed = false;
  readonly uri = { toString: () => `stub:${(StubModel.counter += 1)}` };
  private static counter = 0;

  constructor(value: string) {
    this.value = value;
  }

  getValue(): string {
    return this.value;
  }

  setValue(next: string): void {
    this.value = next;
  }

  getLinesContent(): string[] {
    return this.value.split("\n");
  }

  getLineCount(): number {
    return this.getLinesContent().length;
  }

  isDisposed(): boolean {
    return this.disposed;
  }

  dispose(): void {
    this.disposed = true;
  }
}

/** A decorations collection that keeps what it was given and draws nothing. */
class StubDecorations {
  private items: unknown[] = [];

  set(next: unknown[]): void {
    this.items = next;
  }

  clear(): void {
    this.items = [];
  }

  length(): number {
    return this.items.length;
  }
}

/** A code editor: a model holder with the listeners and view operations turned into no-ops. */
class StubEditor {
  private model: StubModel | null = null;

  getModel(): StubModel | null {
    return this.model;
  }

  setModel(model: StubModel | null): void {
    this.model = model;
  }

  getValue(): string {
    return this.model?.getValue() ?? "";
  }

  getPosition(): { lineNumber: number; column: number } {
    return { lineNumber: 1, column: 1 };
  }

  setPosition(): void {
    /* no layout to move a caret in */
  }

  revealLineInCenter(): void {
    /* nothing is scrolled */
  }

  updateOptions(): void {
    /* the options change nothing that a test can see */
  }

  createDecorationsCollection(): StubDecorations {
    return new StubDecorations();
  }

  onDidChangeCursorPosition(): typeof disposable {
    return disposable;
  }

  onDidChangeModelContent(): typeof disposable {
    return disposable;
  }

  dispose(): void {
    this.model = null;
  }
}

/** A diff editor: the pair of models, and nothing else. */
class StubDiffEditor {
  private model: { original: StubModel; modified: StubModel } | null = null;

  getModel(): { original: StubModel; modified: StubModel } | null {
    return this.model;
  }

  setModel(model: { original: StubModel; modified: StubModel } | null): void {
    this.model = model;
  }

  dispose(): void {
    this.model = null;
  }
}

/** Stands in for the monaco.editor namespace. */
export const editor = {
  create: (): StubEditor => new StubEditor(),
  createDiffEditor: (): StubDiffEditor => new StubDiffEditor(),
  createModel: (value: string): StubModel => new StubModel(value),
  setModelMarkers: (): void => undefined,
  registerCommand: (): typeof disposable => disposable,
  defineTheme: (): void => undefined,
  setTheme: (): void => undefined,
  setModelLanguage: (): void => undefined,
  getModelMarkers: (): unknown[] => [],
  getEditors: (): unknown[] => [],
};

/** Stands in for the monaco.languages namespace. */
export const languages = {
  register: (): void => undefined,
  setMonarchTokensProvider: (): void => undefined,
  setLanguageConfiguration: (): void => undefined,
  registerCodeActionProvider: (): typeof disposable => disposable,
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
