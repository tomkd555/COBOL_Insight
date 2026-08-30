import { describe, expect, it, vi } from "vitest";
import type * as monacoApi from "monaco-editor/editor/editor.api";
import { quickFixActions, setQuickFixTarget } from "./quickFix";

function marker(code: string | undefined, line = 10): monacoApi.editor.IMarkerData {
  return {
    startLineNumber: line,
    startColumn: 1,
    endLineNumber: line,
    endColumn: 80,
    message: `${code ?? "?"} の内容`,
    severity: 8,
    code,
  } as monacoApi.editor.IMarkerData;
}

describe("quickFixActions", () => {
  it("offers a fix only for the rules the engine can fix", () => {
    setQuickFixTarget(new Set(["R004"]), vi.fn());
    const actions = quickFixActions([marker("R001"), marker("R004")]);
    expect(actions).toHaveLength(1);
    expect(actions[0].command?.arguments).toEqual(["R004"]);
    expect(actions[0].kind).toBe("quickfix");
  });

  it("offers one action per rule, however many markers that rule left", () => {
    setQuickFixTarget(new Set(["R004"]), vi.fn());
    expect(quickFixActions([marker("R004", 10), marker("R004", 20)])).toHaveLength(1);
  });

  it("ignores a marker with no rule id, such as the byte-column warning", () => {
    setQuickFixTarget(new Set(["R004"]), vi.fn());
    expect(quickFixActions([marker(undefined)])).toEqual([]);
  });

  it("offers nothing while no rule has a fix", () => {
    setQuickFixTarget(new Set(), vi.fn());
    expect(quickFixActions([marker("R004")])).toEqual([]);
  });
});
