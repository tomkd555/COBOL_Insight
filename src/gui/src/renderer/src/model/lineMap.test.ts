import { describe, expect, it } from "vitest";
import type { LineMapEntry, TranspileGeneratedFile } from "../../../shared/ipc";
import { cobolLineFor, entriesOf, fileOf, generatedLineFor, languagesOf } from "./lineMap";

function entry(overrides: Partial<LineMapEntry>): LineMapEntry {
  return {
    cobolLineStart: 1,
    cobolLineEnd: 1,
    genFile: "syk001.py",
    genLineStart: 1,
    genLineEnd: 1,
    ...overrides,
  };
}

const MAP: LineMapEntry[] = [
  entry({ cobolLineStart: 7, cobolLineEnd: 7, genLineStart: 3, genLineEnd: 3 }),
  entry({ cobolLineStart: 9, cobolLineEnd: 11, genLineStart: 5, genLineEnd: 8 }),
  entry({ genFile: "SYK001.java", cobolLineStart: 7, cobolLineEnd: 7, genLineStart: 12, genLineEnd: 12 }),
];

describe("entriesOf", () => {
  it("keeps only the rows of one generated file", () => {
    expect(entriesOf(MAP, "syk001.py").map((row) => row.genLineStart)).toEqual([3, 5]);
    expect(entriesOf(MAP, "SYK001.java").map((row) => row.genLineStart)).toEqual([12]);
    expect(entriesOf(MAP, "absent.py")).toEqual([]);
  });
});

describe("generatedLineFor", () => {
  const python = entriesOf(MAP, "syk001.py");

  it("takes the start of the row covering the line", () => {
    expect(generatedLineFor(python, 7)).toBe(3);
    expect(generatedLineFor(python, 10)).toBe(5);
    expect(generatedLineFor(python, 11)).toBe(5);
  });

  it("reports no correspondence for a line no row covers", () => {
    expect(generatedLineFor(python, 8)).toBeNull();
    expect(generatedLineFor(python, 99)).toBeNull();
    expect(generatedLineFor([], 7)).toBeNull();
  });
});

describe("cobolLineFor", () => {
  const python = entriesOf(MAP, "syk001.py");

  it("takes the start of the row covering the generated line", () => {
    expect(cobolLineFor(python, 3)).toBe(7);
    expect(cobolLineFor(python, 8)).toBe(9);
  });

  it("reports no correspondence for a generated line no row covers", () => {
    expect(cobolLineFor(python, 4)).toBeNull();
  });
});

describe("the generated files", () => {
  const files: TranspileGeneratedFile[] = [
    { name: "SYK001.java", language: "java", text: "class A {}" },
    { name: "syk001.py", language: "python", text: "pass" },
    { name: "syk001_rec.py", language: "python", text: "pass" },
  ];

  it("lists each language once, in the order the files came in", () => {
    expect(languagesOf(files)).toEqual(["java", "python"]);
    expect(languagesOf([])).toEqual([]);
  });

  it("shows the first file of the chosen language", () => {
    expect(fileOf(files, "python")?.name).toBe("syk001.py");
    expect(fileOf(files, "java")?.name).toBe("SYK001.java");
    expect(fileOf([], "java")).toBeNull();
  });
});
