/**
 * The COPY expansions of one asset, drawn between the lines of the source.
 *
 * `scan --copy-expansion` records, for every COPY statement, the copybook lines that belong where the
 * statement sits, after REPLACING. They are shown as Monaco view zones — read-only strips of the
 * editor's own layout, outside the text — because the copybook's lines lie outside the file being
 * edited and must never reach a save.
 *
 * The zones belong to the editor and the text model holds none of them, so they are torn down and
 * rebuilt whenever the editor is pointed at another tab and after a scan. Each is put under the COPY
 * statement of its copybook nearest the line the scan recorded, as the text now stands, since unsaved
 * edits above it move the statement. Within one tab they then follow the edits: Monaco moves a zone
 * and a decoration with the lines above them, and the glyph decoration is what this module reads the
 * COPY statement's current line back from. Which zones the reader collapsed is kept per tab, so a
 * rebuild draws them as the reader left them.
 */

import { useEffect, useRef, useState } from "react";
import type * as monacoApi from "monaco-editor/editor/editor.api";
import type { CopyExpansion, CopyExpansionData } from "../../../../shared/ipc";
import { api } from "../../api";
import { text } from "../../i18n/text";
import { structureState, useProject } from "../../state/projectStore";
import { monacoEditor } from "../../vendor/monacoEditor";
import { SEQUENCE_COLUMNS } from "../../model/columns";

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

/**
 * The zones the reader collapsed, per asset path: each by its copybook name and its place among the
 * expansions of the same name, which survive a rescan where the recorded lines move.
 */
const collapsedZones = new Map<string, Set<string>>();

/** The key a zone's collapsed state is kept under. */
function zoneKey(expansions: readonly CopyExpansion[], index: number): string {
  const name = expansions[index].copybookName.toUpperCase();
  const before = expansions
    .slice(0, index)
    .filter((each) => each.copybookName.toUpperCase() === name).length;
  return `${name}#${before}`;
}

/** Characters a regular expression would read as syntax. */
function escaped(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/**
 * The line of the COPY statement of the named copybook nearest the recorded line, in the text as it
 * now stands; the recorded line where the text names that copybook nowhere. Comment lines (an
 * asterisk or a slash in the indicator column) are passed over.
 */
export function copyStatementLine(lines: readonly string[], name: string, recorded: number): number {
  const pattern = new RegExp(`(^|[\\s])COPY\\s+["']?${escaped(name)}["']?(?=[\\s.]|$)`, "i");
  let best = recorded;
  let distance = Number.POSITIVE_INFINITY;
  lines.forEach((line, index) => {
    const indicator = line.charAt(SEQUENCE_COLUMNS);
    if (indicator === "*" || indicator === "/" || !pattern.test(line.slice(SEQUENCE_COLUMNS + 1))) {
      return;
    }
    const away = Math.abs(index + 1 - recorded);
    if (away < distance) {
      distance = away;
      best = index + 1;
    }
  });
  return best;
}

/** What the caller holds on to so the zones can be taken down again. */
export interface CopyZonesHandle {
  /** Puts a note in every zone's header, or takes it away with null. */
  setNote(note: string | null): void;
  dispose(): void;
}

/** One expansion as it stands on screen: its own elements, and the zone Monaco lays out. */
interface Zone {
  readonly expansion: CopyExpansion;
  readonly toggle: HTMLButtonElement;
  readonly lines: HTMLElement;
  readonly numbers: HTMLElement;
  /** Says, beside the path, when what was written is still to be read into the table. */
  readonly note: HTMLElement;
  readonly delegate: monacoApi.editor.IViewZone;
  /** The id the view-zone accessor gave it, empty until it has been added. */
  id: string;
  collapsed: boolean;
}

/** The copybook a line comes from: its own where a nested COPY brought it in. */
function pathOf(expansion: CopyExpansion, line: CopyExpansion["lines"][number]): string {
  return line.copybookPath ?? expansion.copybookPath;
}

/**
 * The header line of a zone, one line per copybook line, and one more wherever the lines pass from
 * one copybook to another: a nested COPY's lines are numbered in their own copybook, so each such
 * run is headed by the path its numbers count in.
 */
function zoneHeight(expansion: CopyExpansion, collapsed: boolean): number {
  if (collapsed) {
    return 1;
  }
  let rows = 1;
  let current = expansion.copybookPath;
  for (const line of expansion.lines) {
    const path = pathOf(expansion, line);
    if (path !== current) {
      rows += 1;
      current = path;
    }
    rows += 1;
  }
  return rows;
}

/**
 * Builds one expansion's elements once. Collapsing only hides the lines, so
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
  // resolved to.
  const path = document.createElement("span");
  path.className = "ci-copy__path";
  path.textContent = expansion.copybookPath;
  header.appendChild(path);

  const note = document.createElement("span");
  note.className = "ci-copy__note";
  note.dataset.testid = `copy-zone-note-${expansion.copyStatementLine}`;
  note.hidden = true;
  header.appendChild(note);

  // The zone's own node starts at the content's left edge, so the copybook's line numbers go in the
  // margin node beside it, under the source's line numbers, and its text lines up with the source.
  const margin = document.createElement("div");
  margin.className = "ci-copy-margin";
  const head = document.createElement("div");
  head.className = "ci-copy-margin__head";
  const numbers = document.createElement("div");

  const lines = document.createElement("div");
  lines.className = "ci-copy__lines";
  let current = expansion.copybookPath;
  for (const line of expansion.lines) {
    const from = pathOf(expansion, line);
    if (from !== current) {
      current = from;
      const gap = document.createElement("div");
      gap.className = "ci-copy__number";
      gap.textContent = "\u00a0";
      numbers.appendChild(gap);
      const label = document.createElement("div");
      label.className = "ci-copy__line ci-copy__line--path";
      label.textContent = from;
      lines.appendChild(label);
    }
    const number = document.createElement("div");
    number.className = "ci-copy__number";
    number.textContent = String(line.copybookLine);
    numbers.appendChild(number);
    const row = document.createElement("div");
    row.className = "ci-copy__line";
    const body = document.createElement("span");
    body.className = "ci-copy__text";
    body.textContent = line.text;
    row.appendChild(body);
    lines.appendChild(row);
  }

  root.appendChild(header);
  root.appendChild(lines);
  margin.appendChild(head);
  margin.appendChild(numbers);

  const zone: Zone = {
    expansion,
    toggle,
    lines,
    numbers,
    note,
    delegate: {
      afterLineNumber: expansion.copyStatementLine,
      heightInLines: zoneHeight(expansion, false),
      domNode: root,
      marginDomNode: margin,
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
  zone.numbers.hidden = zone.collapsed;
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
  collapsed: Set<string> = new Set(),
): CopyZonesHandle {
  const monaco = monacoEditor();
  let disposed = false;

  const zones: Zone[] = expansions.map((expansion, index) => {
    const zone = buildZone(expansion, () => toggleAt(index));
    if (collapsed.has(zoneKey(expansions, index))) {
      zone.collapsed = true;
      paint(zone);
      zone.delegate.heightInLines = zoneHeight(expansion, true);
    }
    return zone;
  });

  // Where each COPY statement stands in the text now: unsaved edits above it can have moved it off
  // the line the scan recorded.
  const current = editor.getModel()?.getLinesContent() ?? [];
  const startLines = expansions.map((expansion) =>
    copyStatementLine(current, expansion.copybookName, expansion.copyStatementLine),
  );

  /*
   * The COPY statement's line as it stands now. The decorations collection moves its ranges with the
   * edits, so it says where a zone belongs from here on.
   */
  const glyphs = editor.createDecorationsCollection(
    startLines.map((line) => ({
      range: {
        startLineNumber: line,
        startColumn: 1,
        endLineNumber: line,
        endColumn: 1,
      },
      options: {
        glyphMarginClassName: "ci-copy__glyph",
      },
    })),
  );

  const lineOf = (index: number): number =>
    glyphs.getRange(index)?.startLineNumber ?? startLines[index];

  const toggleAt = (index: number): void => {
    const zone = zones[index];
    if (disposed || zone === undefined) {
      return;
    }
    zone.collapsed = !zone.collapsed;
    const key = zoneKey(expansions, index);
    if (zone.collapsed) {
      collapsed.add(key);
    } else {
      collapsed.delete(key);
    }
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

  // The gutter widens with the line count and the folding controls, so it is read on every layout.
  const host = editor.getContainerDomNode();
  const align = (): void => {
    const layout = editor.getLayoutInfo();
    host.style.setProperty("--ci-copy-content-left", `${layout.contentLeft}px`);
    host.style.setProperty(
      "--ci-copy-numbers-end",
      `${layout.lineNumbersLeft + layout.lineNumbersWidth}px`,
    );
  };
  align();
  const layoutChange = editor.onDidLayoutChange(align);

  return {
    setNote: (note: string | null): void => {
      for (const zone of zones) {
        zone.note.textContent = note ?? "";
        zone.note.hidden = note === null;
      }
    },
    dispose: (): void => {
      disposed = true;
      layoutChange.dispose();
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
 * The table is read per asset, because this is the only screen that reads it, and the file is
 * written once per scan. `path` is carried in the state so that a tab switch cannot leave
 * the previous asset's zones drawn over the new one while its own read is still in flight.
 */
export function useCopyZones(
  editorRef: { current: monacoApi.editor.IStandaloneCodeEditor | null },
  path: string,
  ready: boolean,
): void {
  const project = useProject();
  const tablePath = project.outputPaths?.copyExpansion ?? "";
  // The table comes from the last scan, so the zones say when a write is still to be read into it.
  const structure = structureState(project);
  const note =
    structure === null
      ? null
      : structure === "reading"
        ? text.copyExpansion.structureReading
        : text.copyExpansion.structureUnread;
  const noteRef = useRef(note);
  noteRef.current = note;
  const handleRef = useRef<CopyZonesHandle | null>(null);
  // The artefact epoch moves after every scan that read the folder, which is when the table can have
  // been rewritten (useAnalysis empties the cache first), so the tab in front reads it again then.
  const epoch = project.runId;
  const [loaded, setLoaded] = useState<{ path: string; expansions: readonly CopyExpansion[] }>({
    path: "",
    expansions: [],
  });

  useEffect(() => {
    // The same asset keeps its zones until the read answers; another asset starts with none.
    setLoaded((current) => (current.path === path ? current : { path, expansions: [] }));
    if (tablePath === "") {
      return;
    }
    let cancelled = false;
    readCopyExpansionCached(tablePath)
      .then((table) => {
        if (cancelled) return;
        const expansions =
          table.programs.find((candidate) => candidate.path === path)?.expansions ?? [];
        // An unchanged table leaves the zones as drawn; a changed one draws them again, each
        // collapsed or open as the reader left it.
        setLoaded((current) =>
          current.path === path && current.expansions === expansions ? current : { path, expansions },
        );
      })
      .catch(() => {
        // A failed read leaves the zones as drawn; another asset starts with none. The table is
        // absent until a scan has written one, which is a normal state.
        if (!cancelled) {
          setLoaded((current) => (current.path === path ? current : { path, expansions: [] }));
        }
      });
    return () => {
      cancelled = true;
    };
  }, [tablePath, path, epoch]);

  useEffect(() => {
    const editor = editorRef.current;
    if (editor === null || !ready || loaded.path !== path || loaded.expansions.length === 0) {
      return;
    }
    let collapsed = collapsedZones.get(path);
    if (collapsed === undefined) {
      collapsed = new Set();
      collapsedZones.set(path, collapsed);
    }
    const handle = showCopyZones(editor, loaded.expansions, collapsed);
    handle.setNote(noteRef.current);
    handleRef.current = handle;
    return () => {
      handleRef.current = null;
      handle.dispose();
    };
  }, [editorRef, loaded, path, ready]);

  useEffect(() => {
    handleRef.current?.setNote(note);
  }, [note]);
}
