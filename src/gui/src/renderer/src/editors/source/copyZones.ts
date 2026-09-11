/**
 * The COPY expansions of one asset, drawn between the lines of the source.
 *
 * `scan --copy-expansion` records, for every COPY statement, the copybook lines that belong where the
 * statement sits, after REPLACING. They are shown as Monaco view zones — read-only strips of the
 * editor's own layout — rather than as text, because the copybook's lines are not part of the file
 * being edited and must never reach a save.
 *
 * The zones belong to the editor, not to the model, so they are torn down and rebuilt whenever the
 * editor is pointed at another tab. Within one tab they follow the edits: Monaco moves a zone and a
 * decoration with the lines above them, and the glyph decoration is what this module reads the COPY
 * statement's current line back from. The line the scan recorded is only ever the starting point —
 * after an insertion above it, it names the wrong statement.
 */

import { useEffect, useState } from "react";
import type * as monacoApi from "monaco-editor/editor/editor.api";
import type { CopyExpansion, CopyExpansionData } from "../../../../shared/ipc";
import { api } from "../../api";
import { text } from "../../i18n/text";
import { useProject } from "../../state/projectStore";
import { monacoEditor } from "../../vendor/monacoEditor";

/**
 * The COPY expansion table, keyed by its file path. Every source tab reads the same table on every
 * switch, and it changes only when a scan rewrites it, so one read per path is kept until then.
 */
const copyExpansionCache = new Map<string, Promise<CopyExpansionData>>();

/** Drops the cached tables. Called once a scan has finished, since only it can have rewritten them. */
export function clearCopyExpansionCache(): void {
  copyExpansionCache.clear();
}

function readCopyExpansionCached(tablePath: string): Promise<CopyExpansionData> {
  let pending = copyExpansionCache.get(tablePath);
  if (pending === undefined) {
    pending = api().readCopyExpansion(tablePath);
    copyExpansionCache.set(tablePath, pending);
    // A failed read must not poison the cache: the next tab switch should try again.
    pending.catch(() => copyExpansionCache.delete(tablePath));
  }
  return pending;
}

/** What the caller holds on to so the zones can be taken down again. */
export interface CopyZonesHandle {
  dispose(): void;
}

/** One expansion as it stands on screen: its own elements, and the zone Monaco lays out. */
interface Zone {
  readonly expansion: CopyExpansion;
  readonly toggle: HTMLButtonElement;
  readonly lines: HTMLElement;
  readonly delegate: monacoApi.editor.IViewZone;
  /** The id the view-zone accessor gave it, empty until it has been added. */
  id: string;
  collapsed: boolean;
}

/** The header line of a zone plus one line per copybook line. */
function zoneHeight(expansion: CopyExpansion, collapsed: boolean): number {
  return collapsed ? 1 : 1 + expansion.lines.length;
}

/**
 * Builds one expansion's elements once. Collapsing hides the lines rather than rebuilding them, so
 * the control the reader just pressed is still there — and still focused — afterwards.
 */
function buildZone(expansion: CopyExpansion, onToggle: () => void): Zone {
  const root = document.createElement("div");
  root.className = "ci-copy";
  root.dataset.testid = `copy-zone-${expansion.copyStatementLine}`;

  const header = document.createElement("div");
  header.className = "ci-copy__header";

  const toggle = document.createElement("button");
  toggle.type = "button";
  toggle.className = "ci-copy__toggle";
  toggle.setAttribute("aria-label", text.copyExpansion.toggleLabel(expansion.copybookName));
  toggle.dataset.testid = `copy-zone-toggle-${expansion.copyStatementLine}`;
  toggle.addEventListener("click", onToggle);
  header.appendChild(toggle);

  // The source line above the zone already reads "COPY <name>."; the header says only where it
  // resolved to, not the statement's name again.
  const path = document.createElement("span");
  path.className = "ci-copy__path";
  path.textContent = expansion.copybookPath;
  header.appendChild(path);

  const lines = document.createElement("div");
  lines.className = "ci-copy__lines";
  for (const line of expansion.lines) {
    const row = document.createElement("div");
    row.className = "ci-copy__line";
    const number = document.createElement("span");
    number.className = "ci-copy__number";
    number.textContent = String(line.copybookLine);
    const body = document.createElement("span");
    body.className = "ci-copy__text";
    body.textContent = line.text;
    row.appendChild(number);
    row.appendChild(body);
    lines.appendChild(row);
  }

  root.appendChild(header);
  root.appendChild(lines);

  const zone: Zone = {
    expansion,
    toggle,
    lines,
    delegate: {
      afterLineNumber: expansion.copyStatementLine,
      heightInLines: zoneHeight(expansion, false),
      domNode: root,
    },
    id: "",
    collapsed: false,
  };
  paint(zone);
  return zone;
}

/** Puts the zone's own state onto its elements. */
function paint(zone: Zone): void {
  zone.toggle.textContent = zone.collapsed ? text.copyExpansion.expand : text.copyExpansion.collapse;
  zone.toggle.setAttribute("aria-expanded", zone.collapsed ? "false" : "true");
  zone.lines.hidden = zone.collapsed;
}

/**
 * Draws one asset's expansions into the editor and returns the handle that removes them again.
 *
 * A zone can be collapsed from its own button, from the glyph margin of the COPY line it hangs
 * under, or from the keyboard: Monaco takes every keystroke through a hidden textarea and will not
 * hand focus to a control inside a view zone, so the toggle is also registered as an editor action
 * that acts on the line the caret is on.
 */
export function showCopyZones(
  editor: monacoApi.editor.IStandaloneCodeEditor,
  expansions: readonly CopyExpansion[],
): CopyZonesHandle {
  const monaco = monacoEditor();
  let disposed = false;

  const zones: Zone[] = expansions.map((expansion, index) =>
    buildZone(expansion, () => toggleAt(index)),
  );

  /*
   * The COPY statement's line as it stands now. The decorations collection moves its ranges with the
   * edits, so it — not the number the scan recorded — is what says where a zone belongs.
   */
  const glyphs = editor.createDecorationsCollection(
    expansions.map((expansion) => ({
      range: {
        startLineNumber: expansion.copyStatementLine,
        startColumn: 1,
        endLineNumber: expansion.copyStatementLine,
        endColumn: 1,
      },
      options: {
        glyphMarginClassName: "ci-copy__glyph",
      },
    })),
  );

  const lineOf = (index: number): number =>
    glyphs.getRange(index)?.startLineNumber ?? zones[index].expansion.copyStatementLine;

  const toggleAt = (index: number): void => {
    const zone = zones[index];
    if (disposed || zone === undefined) {
      return;
    }
    zone.collapsed = !zone.collapsed;
    paint(zone);
    zone.delegate.afterLineNumber = lineOf(index);
    zone.delegate.heightInLines = zoneHeight(zone.expansion, zone.collapsed);
    // Only the one zone is laid out again; rebuilding them all would drop the focused control.
    editor.changeViewZones((accessor) => accessor.layoutZone(zone.id));
  };

  editor.changeViewZones((accessor) => {
    zones.forEach((zone, index) => {
      zone.delegate.afterLineNumber = lineOf(index);
      zone.id = accessor.addZone(zone.delegate);
    });
  });

  const mouse = editor.onMouseDown((event) => {
    const line = event.target.position?.lineNumber;
    // MouseTargetType.GUTTER_GLYPH_MARGIN is 2; naming it would mean importing Monaco's runtime.
    if (event.target.type !== 2 || line === undefined) {
      return;
    }
    const index = zones.findIndex((_zone, at) => lineOf(at) === line);
    if (index >= 0) {
      toggleAt(index);
    }
  });

  const action = editor.addAction({
    id: "cobolInsight.toggleCopyExpansion",
    label: text.copyExpansion.action,
    keybindings: [monaco.KeyMod.Alt | monaco.KeyCode.KeyC],
    run: (target) => {
      const line = target.getPosition()?.lineNumber;
      const index = zones.findIndex((_zone, at) => lineOf(at) === line);
      if (index >= 0) {
        toggleAt(index);
      }
    },
  });

  return {
    dispose: (): void => {
      disposed = true;
      action.dispose();
      mouse.dispose();
      glyphs.clear();
      editor.changeViewZones((accessor) => {
        for (const zone of zones) {
          if (zone.id !== "") {
            accessor.removeZone(zone.id);
          }
        }
      });
    },
  };
}

/**
 * Keeps the open asset's expansions on the editor.
 *
 * The table is read per asset rather than held in a store: it is the only screen that reads it, and
 * the file is written once per scan. `path` is carried in the state so that a tab switch cannot leave
 * the previous asset's zones drawn over the new one while its own read is still in flight.
 */
export function useCopyZones(
  editorRef: { current: monacoApi.editor.IStandaloneCodeEditor | null },
  path: string,
  ready: boolean,
): void {
  const project = useProject();
  const tablePath = project.outputPaths?.copyExpansion ?? "";
  const [loaded, setLoaded] = useState<{ path: string; expansions: readonly CopyExpansion[] }>({
    path: "",
    expansions: [],
  });

  useEffect(() => {
    setLoaded({ path, expansions: [] });
    if (tablePath === "") {
      return;
    }
    let cancelled = false;
    readCopyExpansionCached(tablePath)
      .then((table) => {
        if (cancelled) return;
        const program = table.programs.find((candidate) => candidate.path === path);
        setLoaded({ path, expansions: program?.expansions ?? [] });
      })
      .catch(() => {
        // The table is absent until a scan has written one; that is not a failure worth reporting.
        if (!cancelled) setLoaded({ path, expansions: [] });
      });
    return () => {
      cancelled = true;
    };
  }, [tablePath, path]);

  useEffect(() => {
    const editor = editorRef.current;
    if (editor === null || !ready || loaded.path !== path || loaded.expansions.length === 0) {
      return;
    }
    const handle = showCopyZones(editor, loaded.expansions);
    return () => handle.dispose();
  }, [editorRef, loaded, path, ready]);
}
