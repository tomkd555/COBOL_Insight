/**
 * The monaco-editor stand-in used by Vitest. The alias in vitest.config.ts redirects every
 * monaco-editor subpath to this one file.
 *
 * There are two reasons. Monaco needs a Web Worker and real DOM measurement, neither of which jsdom
 * provides, and the real package is roughly 10MB of ESM whose worker import (?worker&inline) builds
 * a second bundle at test time, which would slow down even tests that merely render a screen.
 *
 * It is a fake: enough of the API answers for a screen holding an editor to mount and
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

  private version = 1;

  getValue(): string {
    return this.value;
  }

  /** Replaces the text. As in Monaco, the content listeners of every editor showing the model run. */
  setValue(next: string): void {
    this.value = next;
    this.version += 1;
    for (const each of editors) {
      if (each.getModel() === this) {
        each.notifyContentChange();
      }
    }
  }

  getVersionId(): number {
    return this.version;
  }

  getLinesContent(): string[] {
    return this.value.split(/\r\n|\r|\n/);
  }

  /**
   * What typing does: the text changes and the content listeners of every editor showing this model
   * run, as Monaco's do. A test reaches it through the model of a tab (vendor/monacoModels).
   */
  edit(next: string): void {
    this.setValue(next);
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

/** The editors that have not been disposed, which `StubModel.edit` notifies. */
const editors = new Set<StubEditor>();

/**
 * The editors that have not been disposed, in creation order, so a test can reach the one a screen
 * created and run its actions.
 */
export function liveEditors(): StubEditor[] {
  return [...editors];
}

/** A code editor: a model holder with the view operations turned into no-ops. */
class StubEditor {
  private model: StubModel | null = null;
  private readonly contentListeners = new Set<() => void>();
  private position = { lineNumber: 1, column: 1 };
  /** The actions registered on this editor, by id. */
  private readonly actions = new Map<string, (editor: StubEditor) => void>();

  constructor() {
    editors.add(this);
  }

  notifyContentChange(): void {
    for (const listener of [...this.contentListeners]) {
      listener();
    }
  }

  getModel(): StubModel | null {
    return this.model;
  }

  setModel(model: StubModel | null): void {
    this.model = model;
  }

  getValue(): string {
    return this.model?.getValue() ?? "";
  }

  /** The caret, as the last setPosition left it. Nothing is laid out, so nothing else moves it. */
  getPosition(): { lineNumber: number; column: number } {
    return { ...this.position };
  }

  setPosition(position: { lineNumber: number; column: number }): void {
    this.position = { lineNumber: position.lineNumber, column: position.column };
  }

  revealLineInCenter(): void {
    /* nothing is scrolled */
  }

  getTopForLineNumber(): number {
    return 0;
  }

  setScrollTop(): void {
    /* nothing is scrolled */
  }

  getScrollLeft(): number {
    return 0;
  }

  setScrollLeft(): void {
    /* nothing is scrolled */
  }

  focus(): void {
    /* there is no text area to focus */
  }

  getSelection(): null {
    return null;
  }

  /** No font is measured and nothing is laid out, so every metric is zero. */
  getOption(): { typicalHalfwidthCharacterWidth: number } {
    return { typicalHalfwidthCharacterWidth: 0 };
  }

  getLayoutInfo(): { contentLeft: number; verticalScrollbarWidth: number } {
    return { contentLeft: 0, verticalScrollbarWidth: 0 };
  }

  onDidLayoutChange(): typeof disposable {
    return disposable;
  }

  onDidChangeConfiguration(): typeof disposable {
    return disposable;
  }

  onMouseUp(): typeof disposable {
    return disposable;
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

  onDidChangeModelContent(listener: () => void): typeof disposable {
    this.contentListeners.add(listener);
    return { dispose: (): void => void this.contentListeners.delete(listener) };
  }

  onDidChangeCursorSelection(): typeof disposable {
    return disposable;
  }

  onDidScrollChange(): typeof disposable {
    return disposable;
  }

  onMouseDown(): typeof disposable {
    return disposable;
  }

  addAction(action: { id: string; run: (editor: StubEditor) => void }): typeof disposable {
    this.actions.set(action.id, action.run);
    return { dispose: (): void => void this.actions.delete(action.id) };
  }

  /** What a keystroke bound to the action does: runs it on this editor. */
  runAction(id: string): void {
    this.actions.get(id)?.(this);
  }

  /** View zones need a layout to sit in, so the callback is handed an accessor that keeps none. */
  changeViewZones(callback: (accessor: StubViewZoneAccessor) => void): void {
    callback(new StubViewZoneAccessor());
  }

  dispose(): void {
    this.model = null;
    editors.delete(this);
  }
}

/** A view-zone accessor that hands out ids and forgets the zones. */
class StubViewZoneAccessor {
  private next = 0;

  addZone(): string {
    return `stub-zone:${(this.next += 1)}`;
  }

  removeZone(): void {
    /* nothing was kept to remove */
  }

  layoutZone(): void {
    /* nothing is laid out */
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
  EditorOption: { fontInfo: 59 },
  MouseTargetType: { CONTENT_TEXT: 6 },
  TrackedRangeStickiness: { NeverGrowsWhenTypingAtEdges: 1 },
};

/** Stands in for the monaco.languages namespace. */
export const languages = {
  register: (): void => undefined,
  setMonarchTokensProvider: (): void => undefined,
  setLanguageConfiguration: (): void => undefined,
  registerCodeActionProvider: (): typeof disposable => disposable,
  registerFoldingRangeProvider: (): typeof disposable => disposable,
  FoldingRangeKind: { Comment: { value: "comment" } },
};

/** Stands in for the key enums an editor action's keybinding is built from. */
/** Stands in for monaco-editor/base/common/codiconsUtil.js. */
export function getCodiconFontCharacters(): Record<string, number> {
  return {};
}

export const KeyMod = { CtrlCmd: 2048, Shift: 1024, Alt: 512, WinCtrl: 256 };

export const KeyCode = { KeyC: 33, F12: 70 };

/** Stands in for the monaco.Range constructor. */
export class Range {
  constructor(
    readonly startLineNumber: number,
    readonly startColumn: number,
    readonly endLineNumber: number,
    readonly endColumn: number,
  ) {}
}
