/**
 * The COPY expansions of one asset, drawn between the lines of the source.
 *
 * `scan --copy-expansion` records, for every COPY statement, the copybook lines that belong where the
 * statement sits, after REPLACING. They are shown as Monaco view zones — read-only strips of the
 * editor's own layout — rather than as text, because the copybook's lines are not part of the file
 * being edited and must never reach a save.
 *
 * The zones belong to the editor, not to the model, so they are torn down and rebuilt whenever the
 * editor is pointed at another tab. Within one tab Monaco moves a zone with the lines above it, so an
 * edit does not need to rebuild them.
 */

import { useEffect, useState } from "react";
import type * as monacoApi from "monaco-editor/editor/editor.api";
import type { CopyExpansion } from "../../../../shared/ipc";
import { api } from "../../api";
import { text } from "../../text";
import { useProject } from "../../state/projectStore";

/** What the caller holds on to so the zones can be taken down again. */
export interface CopyZonesHandle {
  dispose(): void;
}

/** The header line of a zone plus one line per copybook line. */
function zoneHeight(expansion: CopyExpansion, collapsed: boolean): number {
  return collapsed ? 1 : 1 + expansion.lines.length;
}

/** The zone's contents: the toggle, the copybook's name and path, and the expanded lines. */
function zoneNode(
  expansion: CopyExpansion,
  collapsed: boolean,
  onToggle: () => void,
): HTMLElement {
  const root = document.createElement("div");
  root.className = "ci-copy";
  root.dataset.testid = `copy-zone-${expansion.copyStatementLine}`;

  const header = document.createElement("div");
  header.className = "ci-copy__header";

  const toggle = document.createElement("button");
  toggle.type = "button";
  toggle.className = "ci-copy__toggle";
  toggle.textContent = collapsed ? text.copyExpansion.expand : text.copyExpansion.collapse;
  toggle.setAttribute("aria-expanded", collapsed ? "false" : "true");
  toggle.setAttribute("aria-label", text.copyExpansion.toggleLabel(expansion.copybookName));
  toggle.dataset.testid = `copy-zone-toggle-${expansion.copyStatementLine}`;
  toggle.addEventListener("click", onToggle);
  header.appendChild(toggle);

  const name = document.createElement("span");
  name.className = "ci-copy__name";
  name.textContent = text.copyExpansion.heading(expansion.copybookName, expansion.lines.length);
  header.appendChild(name);

  const path = document.createElement("span");
  path.className = "ci-copy__path";
  path.textContent = expansion.copybookPath;
  header.appendChild(path);

  root.appendChild(header);

  if (!collapsed) {
    const list = document.createElement("div");
    list.className = "ci-copy__lines";
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
      list.appendChild(row);
    }
    root.appendChild(list);
  }
  return root;
}

/**
 * Draws one asset's expansions into the editor and returns the handle that removes them again.
 *
 * A zone can be collapsed either from its own button or from the glyph margin of the COPY line it
 * hangs under, which is where a reader's eye already is.
 */
export function showCopyZones(
  editor: monacoApi.editor.IStandaloneCodeEditor,
  expansions: readonly CopyExpansion[],
): CopyZonesHandle {
  const collapsed = new Set<number>();
  let zoneIds: string[] = [];
  let disposed = false;

  const render = (): void => {
    if (disposed) {
      return;
    }
    editor.changeViewZones((accessor) => {
      for (const id of zoneIds) {
        accessor.removeZone(id);
      }
      zoneIds = expansions.map((expansion) => {
        const isCollapsed = collapsed.has(expansion.copyStatementLine);
        return accessor.addZone({
          afterLineNumber: expansion.copyStatementLine,
          heightInLines: zoneHeight(expansion, isCollapsed),
          domNode: zoneNode(expansion, isCollapsed, () => toggle(expansion.copyStatementLine)),
        });
      });
    });
  };

  const toggle = (line: number): void => {
    if (collapsed.has(line)) {
      collapsed.delete(line);
    } else {
      collapsed.add(line);
    }
    render();
  };

  const copyLines = new Set(expansions.map((expansion) => expansion.copyStatementLine));
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
        glyphMarginHoverMessage: { value: text.copyExpansion.glyphHint },
      },
    })),
  );

  const mouse = editor.onMouseDown((event) => {
    const line = event.target.position?.lineNumber;
    // MouseTargetType.GUTTER_GLYPH_MARGIN is 2; naming it would mean importing Monaco's runtime.
    if (event.target.type === 2 && line !== undefined && copyLines.has(line)) {
      toggle(line);
    }
  });

  render();

  return {
    dispose: (): void => {
      mouse.dispose();
      glyphs.clear();
      editor.changeViewZones((accessor) => {
        for (const id of zoneIds) {
          accessor.removeZone(id);
        }
      });
      zoneIds = [];
      disposed = true;
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
    api()
      .readCopyExpansion(tablePath)
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
