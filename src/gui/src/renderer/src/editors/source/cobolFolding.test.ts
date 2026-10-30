import { describe, expect, it, vi } from "vitest";
import type * as monacoApi from "monaco-editor/editor/editor.api";
import { outlineOf, registerCobolFolding, setOutlineCodepage } from "./cobolFolding";

const LINES = ["000100 PROCEDURE DIVISION.", "000200 MAIN-PROC.", "000300     EXIT."];

/** A model that has the two members the outline reads, with a version a test moves by hand. */
function fakeModel(lines: string[] = LINES) {
  const model = { version: 1, getVersionId: () => model.version, getLinesContent: vi.fn(() => lines) };
  return model as typeof model & monacoApi.editor.ITextModel;
}

describe("outlineOf", () => {
  it("scans a model once per version of the text", () => {
    const model = fakeModel();

    const first = outlineOf(model);

    expect(outlineOf(model)).toBe(first);
    expect(model.getLinesContent).toHaveBeenCalledTimes(1);
    expect(first.nodes.map((node) => node.key)).toEqual(["PROCEDURE DIVISION", "MAIN-PROC"]);
  });

  it("scans again after the version changes", () => {
    const model = fakeModel();
    const first = outlineOf(model);

    model.version = 2;

    expect(outlineOf(model)).not.toBe(first);
    expect(model.getLinesContent).toHaveBeenCalledTimes(2);
  });

  it("keeps one outline per model", () => {
    const one = fakeModel();
    const other = fakeModel();

    expect(outlineOf(one)).not.toBe(outlineOf(other));
    expect(outlineOf(one)).toBe(outlineOf(one));
  });

  it("keeps the outline when the codepage is set again to the same value", () => {
    const model = fakeModel();
    setOutlineCodepage(model, "Shift_JIS");
    const first = outlineOf(model);

    setOutlineCodepage(model, "Shift_JIS");

    expect(outlineOf(model)).toBe(first);
  });

  it("scans again once the model is given another codepage", () => {
    const model = fakeModel();
    setOutlineCodepage(model, "Shift_JIS");
    const first = outlineOf(model);

    setOutlineCodepage(model, "IBM930");

    expect(outlineOf(model)).not.toBe(first);
  });
});

describe("registerCobolFolding", () => {
  // The module registers its provider once for the whole process, so both tests read the same registration.
  let registered: ReturnType<typeof vi.fn> | undefined;
  function registerTwice() {
    if (registered) return registered;
    const registerFoldingRangeProvider = vi.fn();
    const monaco = {
      languages: { registerFoldingRangeProvider, FoldingRangeKind: { Comment: { value: "comment" } } },
    } as unknown as typeof monacoApi;

    registerCobolFolding(monaco);
    registerCobolFolding(monaco);
    registered = registerFoldingRangeProvider;
    return registerFoldingRangeProvider;
  }

  it("registers the folding provider once", () => {
    expect(registerTwice()).toHaveBeenCalledTimes(1);
  });

  it("answers with the folds of the outline", () => {
    const registerFoldingRangeProvider = registerTwice();
    const provider = registerFoldingRangeProvider.mock.calls[0][1] as monacoApi.languages.FoldingRangeProvider;
    const model = fakeModel();
    const ranges = provider.provideFoldingRanges(model, {} as never, {} as never);
    expect(ranges).toEqual(outlineOf(model).folds.map((fold) => expect.objectContaining({ start: fold.start, end: fold.end })));
    expect(ranges).not.toHaveLength(0);
  });
});
