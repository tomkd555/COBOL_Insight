import { beforeEach, describe, expect, it } from "vitest";
import type { DecodeResult } from "../../../shared/ipc";
import {
  forgetDocument,
  openDocument,
  rememberDocument,
  rememberStamp,
} from "./openDocuments";

const TAB = "source:cobol/A.cbl";

function decoded(text: string, mtimeMs = 1000): DecodeResult {
  return {
    text,
    codepage: "Shift_JIS",
    detected: true,
    soSiPresent: false,
    lines: [],
    stamp: { mtimeMs, byteSize: text.length },
    error: "",
  };
}

// The map is module state, so each test starts from a tab nothing knows about.
beforeEach(() => {
  forgetDocument(TAB);
});

describe("openDocuments", () => {
  it("knows nothing about a tab that was never decoded", () => {
    expect(openDocument(TAB)).toBeNull();
  });

  it("records what the decode reported", () => {
    rememberDocument(TAB, "cobol/A.cbl", decoded("first"));
    expect(openDocument(TAB)).toEqual({
      tabId: TAB,
      path: "cobol/A.cbl",
      codepage: "Shift_JIS",
      stamp: { mtimeMs: 1000, byteSize: 5 },
      text: "first",
    });
  });

  it("replaces what the tab held when it is decoded again", () => {
    rememberDocument(TAB, "cobol/A.cbl", decoded("first"));
    rememberDocument(TAB, "cobol/A.cbl", decoded("second", 2000));
    expect(openDocument(TAB)?.text).toBe("second");
    expect(openDocument(TAB)?.stamp.mtimeMs).toBe(2000);
  });

  it("moves the stamp and the text on after a save, keeping the rest", () => {
    rememberDocument(TAB, "cobol/A.cbl", decoded("first"));
    rememberStamp(TAB, { mtimeMs: 3000, byteSize: 7 }, "edited");
    expect(openDocument(TAB)).toEqual({
      tabId: TAB,
      path: "cobol/A.cbl",
      codepage: "Shift_JIS",
      stamp: { mtimeMs: 3000, byteSize: 7 },
      text: "edited",
    });
  });

  it("ignores a stamp for a tab it does not know", () => {
    rememberStamp(TAB, { mtimeMs: 3000, byteSize: 7 }, "edited");
    expect(openDocument(TAB)).toBeNull();
  });

  it("forgets a closed tab, and forgetting an unknown one is not an error", () => {
    rememberDocument(TAB, "cobol/A.cbl", decoded("first"));
    forgetDocument(TAB);
    expect(openDocument(TAB)).toBeNull();
    expect(() => forgetDocument(TAB)).not.toThrow();
  });
});
