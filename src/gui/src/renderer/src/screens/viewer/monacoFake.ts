/**
 * テスト用の偽 Monaco。Monaco は Web Worker と実 DOM の寸法計測を要するため jsdom では動かない。
 * 描画ライブラリの入口(vendor/monacoEditor)をこの偽物へ差し替えると、CodePane の効果を実際に
 * 走らせたまま「どの行が強調されたか」「カーソル行の変化が画面へ伝わるか」を検証できる。
 *
 * 本文の面を出すタブ(資産・逐語対訳・修正案)が同じ差し替えを要するため、偽物の形を
 * ここ1箇所に持つ。テスト側は次の形で使う(vi.mock の工場は巻き上げられるため、記録先は
 * vi.hoisted で作る)。
 *
 *   const monacoStore = vi.hoisted(() => ({ editors: [] as FakeEditor[] }));
 *   vi.mock("../../vendor/monacoEditor", async () => {
 *     const { createMonacoFake } = await import("../viewer/monacoFake");
 *     return { monacoEditor: () => createMonacoFake(monacoStore) };
 *   });
 */

/** 偽エディタ1台へ渡った装飾1件。 */
export interface FakeDecoration {
  range: { startLineNumber: number };
  options: {
    className?: string;
    inlineClassName?: string;
    glyphMarginClassName?: string;
    hoverMessage?: { value: string };
    after?: { content: string };
  };
}

/** 差し込んだビューゾーン1件(COPY 展開)。 */
export interface FakeViewZone {
  id: string;
  afterLineNumber: number;
  heightInLines: number;
  domNode: HTMLElement;
  marginDomNode: HTMLElement;
}

/** 付いたマーカー1件(保存時の再パース検証)。 */
export interface FakeMarker {
  startLineNumber: number;
  message: string;
  severity: number;
}

/** 偽エディタ1台の観測結果。 */
export interface FakeEditor {
  language: string;
  ariaLabel: string;
  rulers: number[];
  glyphMargin: boolean;
  /** 編集できる面か(readOnly の裏返し)。 */
  editable: boolean;
  value: string;
  decorations: FakeDecoration[];
  zones: FakeViewZone[];
  markers: FakeMarker[];
  revealed: number[];
  disposed: boolean;
  cursorHandler: ((event: { position: { lineNumber: number } }) => void) | null;
  /** 本文を書き換える。試験から利用者の入力を模す入口である。 */
  type: (value: string) => void;
}

/** 生成したエディタの記録先。テストごとに作り直す。 */
export interface MonacoFakeStore {
  editors: FakeEditor[];
}

/** monaco.editor.create へ渡る設定のうち、観測する項目。 */
interface FakeCreateOptions {
  value?: string;
  language?: string;
  rulers?: number[];
  ariaLabel?: string;
  glyphMargin?: boolean;
  readOnly?: boolean;
}

/** ビューゾーンの操作口。 */
interface FakeZoneAccessor {
  addZone: (zone: Omit<FakeViewZone, "id">) => string;
  removeZone: (id: string) => void;
}

/** vendor/monacoEditor の monacoEditor() が返す面のうち、CodePane が触る範囲。 */
export interface MonacoFake {
  languages: {
    register: () => void;
    setMonarchTokensProvider: () => void;
    setLanguageConfiguration: () => void;
  };
  editor: {
    EditorOption: { fontInfo: string };
    defineTheme: () => void;
    create: (container: HTMLElement, options: FakeCreateOptions) => unknown;
    setModelMarkers: (model: FakeModel, owner: string, markers: FakeMarker[]) => void;
  };
}

/** エディタが持つモデル。マーカーの付け先としてだけ使う。 */
interface FakeModel {
  editor: FakeEditor;
}

/** 生成したエディタを store へ記録する偽 Monaco を組む。 */
export function createMonacoFake(store: MonacoFakeStore): MonacoFake {
  return {
    languages: {
      register: () => undefined,
      setMonarchTokensProvider: () => undefined,
      setLanguageConfiguration: () => undefined,
    },
    editor: {
      // 実測寸法の取得に使う列挙(monaco.editor.EditorOption)。値は本物と同じである必要がない。
      EditorOption: { fontInfo: "fontInfo" },
      defineTheme: () => undefined,
      setModelMarkers: (model: FakeModel, _owner: string, markers: FakeMarker[]) => {
        model.editor.markers = markers;
      },
      create: (_container: HTMLElement, options: FakeCreateOptions) => {
        let contentHandler: (() => void) | null = null;
        const editor: FakeEditor = {
          language: options.language ?? "",
          ariaLabel: options.ariaLabel ?? "",
          rulers: options.rulers ?? [],
          glyphMargin: options.glyphMargin ?? false,
          editable: options.readOnly !== true,
          value: options.value ?? "",
          decorations: [],
          zones: [],
          markers: [],
          revealed: [],
          disposed: false,
          cursorHandler: null,
          type: (value: string) => {
            editor.value = value;
            contentHandler?.();
          },
        };
        store.editors.push(editor);
        const model: FakeModel = { editor };
        let nextZoneId = 0;
        return {
          getValue: () => editor.value,
          getModel: () => model,
          setValue: (value: string) => {
            editor.value = value;
            contentHandler?.();
          },
          onDidChangeModelContent: (handler: () => void) => {
            contentHandler = handler;
            return {
              dispose: () => {
                contentHandler = null;
              },
            };
          },
          createDecorationsCollection: (initial: FakeDecoration[]) => {
            editor.decorations = initial;
            return {
              set: (next: FakeDecoration[]) => {
                editor.decorations = next;
              },
            };
          },
          onDidChangeCursorPosition: (
            handler: (event: { position: { lineNumber: number } }) => void,
          ) => {
            editor.cursorHandler = handler;
            return {
              dispose: () => {
                editor.cursorHandler = null;
              },
            };
          },
          // ビューゾーン(COPY 展開の差し込み)。追加・削除を記録し、行番号は消費しない。
          changeViewZones: (change: (accessor: FakeZoneAccessor) => void) => {
            change({
              addZone: (zone) => {
                const id = `zone-${(nextZoneId += 1)}`;
                editor.zones.push({ ...zone, id });
                return id;
              },
              removeZone: (id: string) => {
                editor.zones = editor.zones.filter((zone) => zone.id !== id);
              },
            });
          },
          // 桁見出しの位置合わせと展開行の字送りに使う実測寸法。jsdom では実寸を測れないため固定値を返す。
          getLayoutInfo: () => ({ contentLeft: 60 }),
          getOption: () => ({
            typicalHalfwidthCharacterWidth: 7,
            fontFamily: "'BIZ UDGothic',monospace",
            fontSize: 12,
            lineHeight: 19,
          }),
          getScrollLeft: () => 0,
          onDidLayoutChange: () => ({ dispose: () => undefined }),
          onDidScrollChange: () => ({ dispose: () => undefined }),
          revealLineInCenter: (line: number) => {
            editor.revealed.push(line);
          },
          dispose: () => {
            editor.disposed = true;
          },
        };
      },
    },
  };
}
