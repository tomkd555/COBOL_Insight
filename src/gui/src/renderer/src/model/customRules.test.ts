import { describe, expect, it } from "vitest";
import type { CustomRule } from "../../../shared/rulesFile";
import {
  CUSTOM_COMMANDS,
  NOT_AN_ARRAY,
  asWritten,
  emptyCustomRule,
  emptyMatch,
  engineErrorsFor,
  formEditable,
  formEdited,
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
  commands: ["LINT", "FIX"],
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

  it("returns no definitions for text that does not parse", () => {
    const parsed = parse("[{");
    expect(parsed.rules).toEqual([]);
  });

  it("names the parser's error for text that does not parse", () => {
    const parsed = parse("[{");
    expect(parsed.error).not.toBeNull();
  });

  it("refuses any text other than a list of definitions", () => {
    expect(parse('{"id": "U001"}').error).toBe(NOT_AN_ARRAY);
    expect(parse("[1, 2]").error).toBe(NOT_AN_ARRAY);
  });
});

describe("localProblems", () => {
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

describe("matchOf", () => {
  it("returns an empty line match for a definition with no usable match", () => {
    expect(matchOf({ id: "U001" } as unknown as CustomRule)).toEqual(emptyMatch("line"));
    expect(matchOf({ id: "U001", match: { kind: "other" } } as unknown as CustomRule).kind).toBe(
      "line",
    );
  });
});

describe("a definition typed by hand with values of the wrong type", () => {
  const RAW = `[{
    "id": "U030",
    "name": 1,
    "category": 5,
    "targets": "COBOL",
    "message": "m",
    "match": { "kind": "statement", "verb": "READ" }
  }]`;
  const hand = (): CustomRule[] => JSON.parse(RAW) as CustomRule[];

  it("is read, written and checked without throwing", () => {
    const parsed = parse(RAW);
    expect(parsed.error).toBeNull();
    expect(() => serialise(parsed.rules)).not.toThrow();
    expect(() => pruned(hand()[0])).not.toThrow();
    expect(() => localProblems(hand())).not.toThrow();
  });

  it("is pruned with each value written as it was written", () => {
    const written = pruned(hand()[0]) as unknown as Record<string, unknown>;
    expect(written["name"]).toBe(1);
    expect(written["category"]).toBe(5);
    expect(written["targets"]).toBe("COBOL");
    expect((written["match"] as { verb: unknown }).verb).toBe("READ");
  });

  it("survives a round trip through the raw text", () => {
    const text = serialise(hand());
    const again = parse(text);
    expect(again.error).toBeNull();
    expect(again.rules).toEqual(hand());
    expect(serialise(again.rules)).toBe(text);
  });

  it("flags only the missing name, reading a string verb as a one-item list", () => {
    // The engine takes `"verb": "READ"` as the list ["READ"], so only the name is missing.
    expect(localProblems(hand()).map((problem) => problem.code)).toEqual(["nameRequired"]);
  });

  it("is left to the JSON pane, since the form has no field of those types", () => {
    expect(formEditable(hand()[0])).toBe(false);
    expect(formEditable(LINE_RULE)).toBe(true);
    expect(formEditable(STATEMENT_RULE)).toBe(true);
    expect(formEditable(CHECKED_AFTER_RULE)).toBe(true);
  });
});

describe("a detection of a known kind typed by hand", () => {
  const written = (match: Record<string, unknown>): Record<string, unknown> =>
    (pruned({ id: "U040", name: "n", message: "m", match } as unknown as CustomRule) as unknown as {
      match: Record<string, unknown>;
    }).match;

  it("keeps the values the form has no control for, as they were written", () => {
    expect(written({ kind: "line", regex: "A", ignoreCase: "true", area: "wholeline", note: 1 })).toEqual({
      kind: "line",
      regex: "A",
      ignoreCase: "true",
      area: "wholeline",
      note: 1,
    });
    expect(written({ kind: "statement", verb: ["READ", 1, " "], extra: true })).toEqual({
      kind: "statement",
      verb: ["READ", 1],
      extra: true,
    });
    expect(
      written({ kind: "checked-after", after: { verb: "READ", x: 1 }, checks: "WS-STATUS", onEveryPath: "yes" }),
    ).toEqual({
      kind: "checked-after",
      after: { verb: "READ", x: 1 },
      checks: "WS-STATUS",
      scope: "untilNextMatchingStatement",
      onEveryPath: "yes",
    });
  });

  it("keeps the text \"true\" of a form-edited ignoreCase as that text", () => {
    const typed = { ...LINE_RULE, match: { ...LINE_RULE.match, ignoreCase: "true" } } as unknown as CustomRule;
    const written = JSON.parse(serialise([formEdited(typed)])) as { match: { ignoreCase: unknown } }[];
    expect(written[0].match.ignoreCase).toBe("true");
    expect(serialise([formEdited(typed)])).not.toBe(serialise([formEdited(LINE_RULE)]));
  });
});

describe("what a newer engine or a collaborator added", () => {
  const FUTURE = { id: "U031", name: "n", message: "m", match: { kind: "future", x: 1 }, owner: "team-a" };

  it("comes out of pruning unchanged for an unknown kind and member", () => {
    expect(pruned(FUTURE as unknown as CustomRule)).toEqual(FUTURE);
  });

  it("raises no match problem for the unknown kind", () => {
    expect(localProblems([FUTURE as unknown as CustomRule])).toEqual([]);
  });
});

describe("emptyCustomRule", () => {
  it("takes an id that is not already in use", () => {
    expect(emptyCustomRule([]).id).toBe("U001");
    expect(emptyCustomRule([LINE_RULE]).id).toBe("U002");
  });
});

describe("engineErrorsFor", () => {
  it("returns the errors that name the rule", () => {
    const errors = ["rules.json の custom[0]: id が無い", "U010: verb が無い"];
    expect(engineErrorsFor(errors, "U010")).toEqual(["U010: verb が無い"]);
  });

  it("returns no errors for an empty rule id", () => {
    const errors = ["rules.json の custom[0]: id が無い", "U010: verb が無い"];
    expect(engineErrorsFor(errors, "")).toEqual([]);
  });

  it("matches the rule id as a whole token", () => {
    const errors = ["U010: verb が無い", "custom[1] (U01): name が無い", "U01-X: regex が無い"];
    expect(engineErrorsFor(errors, "U01")).toEqual(["custom[1] (U01): name が無い"]);
    expect(engineErrorsFor(errors, "U010")).toEqual(["U010: verb が無い"]);
  });
});

describe("which definitions the form prunes", () => {
  // A fresh object per call: formEdited marks the object it is given.
  const edited = (): CustomRule => ({ ...LINE_RULE, id: "U001", targets: [], match: { kind: "line", regex: "A", excludeRegex: "" } });
  const READ_RAW = `[{
    "id": "U002",
    "name": "read from the file",
    "message": "m",
    "targets": [],
    "match": { "kind": "line", "regex": "B", "excludeRegex": "" }
  }]`;

  it("writes a file-read definition as stored while another is form-edited", () => {
    const read = parse(READ_RAW).rules[0];
    const written = JSON.parse(serialise([formEdited(edited()), read])) as Record<string, unknown>[];
    expect(written[1]).toEqual(JSON.parse(READ_RAW)[0]);
    expect(written[1]["targets"]).toEqual([]);
    expect(written[1]["match"]).toHaveProperty("excludeRegex", "");
  });

  it("leaves to the JSON pane a definition with values the form would drop", () => {
    const read = parse(READ_RAW).rules[0];
    // The form would drop both, so the definition is edited in the JSON pane alone.
    expect(formEditable(read)).toBe(false);
  });

  it("prunes a definition marked with formEdited, which the form keeps editing", () => {
    const marked = formEdited(edited());
    expect(formEditable(marked)).toBe(true);
    const written = JSON.parse(serialise([marked])) as Record<string, unknown>[];
    expect(written[0]).not.toHaveProperty("targets");
    expect(written[0]["match"]).not.toHaveProperty("excludeRegex");
  });

  it("hands back an unmarked definition itself", () => {
    const unmarked = edited();
    expect(asWritten(unmarked)).toBe(unmarked);
  });
});

describe("pruned", () => {
  describe("blank values", () => {
    it("drops an empty exclusion, which would match every line if written", () => {
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

  describe("a member named __proto__ inside a definition", () => {
    const RAW =
      '{"id":"U050","name":"n","message":"m","match":{"kind":"line","regex":"A","__proto__":{"inner":1}},"__proto__":{"owner":"team-a"}}';

    it("keeps __proto__ as an own member when pruning", () => {
      const written = pruned(JSON.parse(RAW) as CustomRule);
      expect(Object.getPrototypeOf(written)).toBe(Object.prototype);
      expect(Object.getOwnPropertyDescriptor(written, "__proto__")?.value).toEqual({ owner: "team-a" });
      const match = written.match as unknown as object;
      expect(Object.getPrototypeOf(match)).toBe(Object.prototype);
      expect(Object.getOwnPropertyDescriptor(match, "__proto__")?.value).toEqual({ inner: 1 });
    });
  });
});

describe("the values a definition can declare", () => {
  it("is form-editable only when the severity is in the list", () => {
    expect(formEditable({ ...LINE_RULE, severity: "fatal" })).toBe(false);
    expect(formEditable({ ...LINE_RULE, severity: "low" })).toBe(true);
  });

  it("lists FIX among the commands a rule can run under", () => {
    expect(CUSTOM_COMMANDS).toContain("FIX");
  });
});

describe("formEditable", () => {
  describe("a definition read from the file with a value an edit would drop or trim", () => {
    const read = (definition: Record<string, unknown>): CustomRule =>
      parse(JSON.stringify([{ id: "U060", name: "n", message: "m", match: { kind: "line", regex: "X" }, ...definition }]))
        .rules[0];

    it.each([
      ["an empty excludeRegex", { match: { kind: "line", regex: "X", excludeRegex: "" } }],
      ["empty targets", { targets: [] }],
      ["an empty missingClause", { match: { kind: "statement", verb: ["READ"], missingClause: [] } }],
      ["an empty category", { category: "" }],
      ["a blank summary", { summary: "  " }],
      ["verbs with blanks around one and a blank one", { match: { kind: "statement", verb: [" READ ", ""] } }],
      ["a data item with a blank at its end", { match: { kind: "checked-after", checks: { dataItem: ["WS-A "] } } }],
      ["targets in another order than the options", { targets: ["JCL", "COBOL"] }],
      ["a target listed twice", { targets: ["COBOL", "COBOL"] }],
      ["commands in another order than the options", { commands: ["FIX", "LINT"] }],
    ])("is edited in the JSON pane alone for %s", (_case, definition) => {
      expect(formEditable(read(definition))).toBe(false);
    });

    it("is form-editable when its values have no blanks around them", () => {
      expect(formEditable(read({ category: "c", targets: ["COBOL"], match: { kind: "statement", verb: ["READ"] } }))).toBe(
        true,
      );
    });

    it.each([
      ["a missing detection", (rule: Record<string, unknown>) => ({ ...rule, match: undefined })],
      ["a null detection", (rule: Record<string, unknown>) => ({ ...rule, match: null })],
    ])("is edited in the JSON pane alone for %s, marked by formEdited or read", (_case, without) => {
      const bare = without({ id: "U061", name: "n", message: "m" }) as unknown as CustomRule;
      expect(formEditable(bare)).toBe(false);
      expect(formEditable(formEdited({ ...bare }))).toBe(false);
    });

    it("stays form-editable once marked by formEdited, even with an empty category", () => {
      expect(formEditable(formEdited(read({ category: "" })))).toBe(true);
    });
  });

  describe("a definition the form cannot show as it is", () => {
    const editable = (patch: Record<string, unknown>): boolean =>
      formEditable({ ...LINE_RULE, ...patch } as unknown as CustomRule);
    const withMatch = (match: Record<string, unknown>): boolean => editable({ match });

    it("is left to the JSON pane for a target or a command outside the options", () => {
      expect(editable({ targets: ["COBOL", "PL1"] })).toBe(false);
      expect(editable({ commands: ["LINT", "REPORT"] })).toBe(false);
    });

    it("is left to the JSON pane when a target or command differs from its option", () => {
      expect(editable({ targets: ["cobol"] })).toBe(false);
      expect(editable({ commands: [" LINT"] })).toBe(false);
    });

    it("stays in the form for targets and commands spelled as the options are", () => {
      expect(editable({ targets: ["COBOL", "COPYBOOK", "JCL", "BMS"], commands: ["LINT", "SQL_LINT", "FIX"] })).toBe(true);
    });

    it.each(["id", "name", "message", "category", "summary", "rationale", "remedy"])(
      "is left to the JSON pane where %s holds a line break",
      (member) => {
        expect(editable({ [member]: "first\nsecond" })).toBe(false);
        expect(editable({ [member]: "first\r\nsecond" })).toBe(false);
      },
    );

    it("is left to the JSON pane where a text of the detection holds a line break", () => {
      expect(withMatch({ kind: "line", regex: "A\nB" })).toBe(false);
      expect(withMatch({ kind: "line", regex: "A", excludeRegex: "B\r" })).toBe(false);
      expect(withMatch({ kind: "statement", verb: ["READ"], inParagraph: "^A\n" })).toBe(false);
      expect(withMatch({ kind: "checked-after", after: { verb: "CALL\n" } })).toBe(false);
      expect(withMatch({ kind: "checked-after", after: { verb: "CALL", textRegex: "S\nB" } })).toBe(false);
    });

    it.each([",", "、", "\n", "\r"])("is left to the JSON pane where a list item holds %j", (separator) => {
      expect(withMatch({ kind: "statement", verb: [`READ${separator}WRITE`] })).toBe(false);
      expect(withMatch({ kind: "statement", verb: ["READ"], missingClause: [`AT END${separator}X`] })).toBe(false);
      expect(withMatch({ kind: "checked-after", checks: { dataItem: [`WS-A${separator}WS-B`] } })).toBe(false);
    });

    it("stays in the form for list items with no separator in them", () => {
      expect(withMatch({ kind: "statement", verb: ["READ", "WRITE"], missingClause: ["AT END"] })).toBe(true);
      expect(withMatch({ kind: "checked-after", checks: { dataItem: ["WS-A", "WS-B"] } })).toBe(true);
    });
  });
});
