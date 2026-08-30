import { describe, expect, it } from "vitest";
import type { CustomRule } from "../../../shared/rulesFile";
import {
  NOT_AN_ARRAY,
  emptyCustomRule,
  emptyMatch,
  engineErrorsFor,
  localProblems,
  matchOf,
  parse,
  pruned,
  serialise,
} from "./customRules";

const LINE_RULE: CustomRule = {
  id: "U001",
  name: "GO TO の使用",
  message: "GO TO を使っている: ${match}",
  severity: "LOW",
  targets: ["COBOL", "COPYBOOK"],
  commands: ["LINT", "REPORT"],
  match: {
    kind: "line",
    regex: "GO\\s+TO\\b",
    ignoreCase: true,
    area: "programArea",
    excludeRegex: "DEPENDING\\s+ON",
  },
};

const STATEMENT_RULE: CustomRule = {
  id: "U010",
  name: "READ の終端句なし",
  message: "READ に AT END も INVALID KEY もありません。",
  severity: "MEDIUM",
  match: {
    kind: "statement",
    verb: ["READ"],
    missingClause: ["AT END", "INVALID KEY"],
    inParagraph: "^FILE-",
  },
};

const CHECKED_AFTER_RULE: CustomRule = {
  id: "U020",
  name: "CALL 後の状態未検査",
  message: "CALL の後で RETURN-CODE を検査していません。",
  severity: "HIGH",
  match: {
    kind: "checked-after",
    after: { verb: "CALL", textRegex: "SUB" },
    checks: { dataItem: ["RETURN-CODE", "WS-STATUS"] },
    scope: "untilNextMatchingStatement",
    onEveryPath: true,
  },
};

describe("the round trip between the form and the raw pane", () => {
  it("returns every kind unchanged", () => {
    const rules = [LINE_RULE, STATEMENT_RULE, CHECKED_AFTER_RULE];
    expect(parse(serialise(rules))).toEqual({ rules, error: null });
  });

  it("reads an empty pane as no rules at all", () => {
    expect(parse("")).toEqual({ rules: [], error: null });
  });

  it("keeps the definitions when the text does not parse", () => {
    const parsed = parse("[{");
    expect(parsed.rules).toEqual([]);
    expect(parsed.error).not.toBeNull();
  });

  it("refuses text that is not a list of definitions", () => {
    expect(parse('{"id": "U001"}').error).toBe(NOT_AN_ARRAY);
    expect(parse("[1, 2]").error).toBe(NOT_AN_ARRAY);
  });
});

describe("pruning", () => {
  it("drops an empty exclusion rather than writing one that would match every line", () => {
    const written = pruned({ ...LINE_RULE, match: { kind: "line", regex: "A", excludeRegex: "" } });
    expect(written.match).not.toHaveProperty("excludeRegex");
  });

  it("drops the optional prose that was left blank", () => {
    const written = pruned({ ...LINE_RULE, category: "", rationale: "  ", remedy: "直します" });
    expect(written).not.toHaveProperty("category");
    expect(written).not.toHaveProperty("rationale");
    expect(written.remedy).toBe("直します");
  });

  it("drops the blank items of a list and the list that ends up empty", () => {
    const written = pruned({
      ...STATEMENT_RULE,
      match: { kind: "statement", verb: [" READ ", ""], missingClause: [""] },
    });
    expect(written.match).toEqual({ kind: "statement", verb: ["READ"] });
  });
});

describe("the local checks", () => {
  it("passes a well-formed definition", () => {
    expect(localProblems([LINE_RULE, STATEMENT_RULE, CHECKED_AFTER_RULE])).toEqual([]);
  });

  it("names an id that is not in the U form", () => {
    const problems = localProblems([{ ...LINE_RULE, id: "R001" }]);
    expect(problems.map((problem) => problem.code)).toEqual(["idFormat"]);
  });

  it("names the second of two definitions sharing an id", () => {
    const problems = localProblems([LINE_RULE, { ...LINE_RULE, name: "その2" }]);
    expect(problems).toEqual([{ code: "idDuplicate", index: 1, id: "U001" }]);
  });

  it("names the fields the engine requires, per kind", () => {
    const codes = (rule: CustomRule): string[] =>
      localProblems([rule]).map((problem) => problem.code);
    expect(codes({ ...LINE_RULE, name: "", message: "" })).toEqual([
      "nameRequired",
      "messageRequired",
    ]);
    expect(codes({ ...LINE_RULE, match: emptyMatch("line") })).toEqual(["regexRequired"]);
    expect(codes({ ...STATEMENT_RULE, match: emptyMatch("statement") })).toEqual(["verbRequired"]);
    expect(codes({ ...CHECKED_AFTER_RULE, match: emptyMatch("checked-after") })).toEqual([
      "afterVerbRequired",
      "dataItemRequired",
    ]);
  });

  it("does not judge a regular expression, which the engine reads in Java syntax", () => {
    // Valid in Java, rejected by this runtime's RegExp: a possessive quantifier.
    const rule = { ...LINE_RULE, match: { kind: "line" as const, regex: "A++" } };
    expect(localProblems([rule])).toEqual([]);
  });
});

describe("a definition typed by hand", () => {
  it("renders as a line match when it carries no usable match at all", () => {
    expect(matchOf({ id: "U001" } as unknown as CustomRule)).toEqual(emptyMatch("line"));
    expect(matchOf({ id: "U001", match: { kind: "other" } } as unknown as CustomRule).kind).toBe(
      "line",
    );
  });
});

describe("a new definition", () => {
  it("takes an id that is not already in use", () => {
    expect(emptyCustomRule([]).id).toBe("U001");
    expect(emptyCustomRule([LINE_RULE]).id).toBe("U002");
  });
});

describe("the engine's own complaints", () => {
  it("are shown beside the rule they name", () => {
    const errors = ["rules.json の custom[0]: id が無い", "U010: verb が無い"];
    expect(engineErrorsFor(errors, "U010")).toEqual(["U010: verb が無い"]);
    expect(engineErrorsFor(errors, "")).toEqual([]);
  });
});
