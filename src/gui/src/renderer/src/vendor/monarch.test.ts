import { describe, expect, it, vi } from "vitest";
import {
  COBOL_INSIGHT_THEME,
  LANGUAGE_ID,
  bmsLanguage,
  cobolInsightTheme,
  cobolLanguage,
  jclLanguage,
  jsonLanguage,
  languageIdFor,
  type MonarchRule,
} from "./monarch";
import { registerLanguages, type LanguageRegistrationTarget } from "./monacoLanguages";

/**
 * Monarch itself is not run here: it needs Monaco, which needs a worker and real layout. What is
 * checked is the part that can be wrong without either — that the rules match the lines they are
 * written for, in the order they are written in.
 */
function firstMatch(rules: readonly MonarchRule[], line: string): MonarchRule | undefined {
  return rules.find(([pattern]) => {
    // Monaco anchors a rule whose pattern begins with ^ to the start of a line; everywhere else it
    // matches from the current position, which for the first token is also the start.
    const match = pattern.exec(line);
    return match !== null && match.index === 0;
  });
}

describe("the COBOL fixed-format grammar", () => {
  const rules = cobolLanguage().tokenizer["root"];

  it("holds the reserved words, the division words and the figurative constants apart", () => {
    const language = cobolLanguage();
    expect(language.keywords).toContain("PERFORM");
    expect(language.divisions).toContain("PROCEDURE");
    expect(language.figurative).toContain("SPACES");
    // A spelling in two lists would make the word lookup ambiguous.
    const overlap = language.keywords?.filter((word) => language.figurative?.includes(word));
    expect(overlap).toEqual([]);
  });

  it("reads columns 1-6 as the sequence area and column 7 as the indicator", () => {
    const [, action] = firstMatch(rules, "000100 MOVE A TO B.") ?? [];
    expect(action).toEqual(["sequence", "white"]);
  });

  it("reads an asterisk or a slash in column 7 as a comment line", () => {
    expect(firstMatch(rules, "000100* a note")?.[1]).toEqual(["sequence", "comment", "comment"]);
    expect(firstMatch(rules, "000100/ a note")?.[1]).toEqual(["sequence", "comment", "comment"]);
  });

  it("tells the debug and continuation indicators apart", () => {
    expect(firstMatch(rules, "000100D DISPLAY X.")?.[1]).toEqual(["sequence", "debug"]);
    expect(firstMatch(rules, "000100-    'TEXT'")?.[1]).toEqual(["sequence", "continuation"]);
  });

  it("takes a line shorter than the sequence area as sequence", () => {
    expect(firstMatch(rules, "0001")?.[1]).toBe("sequence");
  });

  it("accepts a full-width user-defined name as one word", () => {
    const word = rules.find(([, action]) => typeof action === "object" && "cases" in action);
    expect(word?.[0].exec("受注番号")?.[0]).toBe("受注番号");
  });

  it("marks a literal that does not close on the line", () => {
    const rule = rules.find(([, action]) => action === "string.invalid");
    expect(rule?.[0].test("'unclosed")).toBe(true);
  });
});

describe("the JCL grammar", () => {
  const rules = jclLanguage().tokenizer["root"];

  it("reads //* as a comment statement", () => {
    expect(firstMatch(rules, "//* a note")?.[1]).toBe("comment");
  });

  it("reads a statement as marker, name and operation", () => {
    const action = firstMatch(rules, "//STEP010 EXEC PGM=SYK001") ?? [];
    expect(Array.isArray(action[1])).toBe(true);
    expect((action[1] as unknown[])[0]).toBe("keyword");
    expect((action[1] as unknown[])[1]).toBe("identifier.name");
  });

  it("knows the operations that can head a statement", () => {
    expect(jclLanguage().operations).toEqual(expect.arrayContaining(["JOB", "EXEC", "DD", "PROC"]));
  });

  it("reads a marker with an empty name field as a continuation", () => {
    expect(firstMatch(rules, "//         DSN=SYK.MASTER")?.[1]).toEqual(["keyword", "white"]);
  });

  it("reads a keyword parameter's name by the equals sign that follows it", () => {
    const rule = rules.find(([, action]) => action === "attribute.name");
    expect(rule?.[0].exec("PGM=SYK001")?.[0]).toBe("PGM");
    expect(rule?.[0].exec("SYK001")).toBeNull();
  });
});

describe("the BMS grammar", () => {
  const rules = bmsLanguage().tokenizer["root"];

  it("reads an asterisk in column 1 as a comment", () => {
    expect(firstMatch(rules, "* a note")?.[1]).toBe("comment");
  });

  it("reads a label in column 1 before the macro", () => {
    expect(firstMatch(rules, "SYKMAP1 DFHMSD TYPE=MAP")?.[1]).toEqual(["identifier.name", "white"]);
  });

  it("knows the macros", () => {
    expect(bmsLanguage().macros).toEqual(expect.arrayContaining(["DFHMSD", "DFHMDI", "DFHMDF"]));
  });
});

describe("languageIdFor", () => {
  it("picks the language from the extension and falls back to COBOL", () => {
    expect(languageIdFor("jcl/SYKD010.jcl")).toBe(LANGUAGE_ID.jcl);
    expect(languageIdFor("bms/SYKMAP1.bms")).toBe(LANGUAGE_ID.bms);
    expect(languageIdFor("data/rules.json")).toBe(LANGUAGE_ID.json);
    expect(languageIdFor("cobol/SYK001.cbl")).toBe(LANGUAGE_ID.cobol);
    expect(languageIdFor("copybook/SYKCPY1.cpy")).toBe(LANGUAGE_ID.cobol);
    expect(languageIdFor("NOEXTENSION")).toBe(LANGUAGE_ID.cobol);
  });
});

describe("the JSON grammar", () => {
  const rules = jsonLanguage().tokenizer["root"];

  it("tells a member name from a string value by the colon that follows it", () => {
    expect(firstMatch(rules, '"id": "U001"')?.[1]).toBe("attribute.name");
    expect(firstMatch(rules, '"U001", 1]')?.[1]).toBe("string");
  });

  it("reads the literals, the numbers and the punctuation", () => {
    expect(firstMatch(rules, "true, false")?.[1]).toBe("constant");
    expect(firstMatch(rules, "null}")?.[1]).toBe("constant");
    expect(firstMatch(rules, "-12.5e3")?.[1]).toBe("number");
    expect(firstMatch(rules, "{")?.[1]).toBe("delimiter");
  });

  it("marks a string the line ends in the middle of", () => {
    expect(firstMatch(rules, '"not closed')?.[1]).toBe("string.invalid");
  });
});

describe("the theme", () => {
  it("colours every token the grammars emit", () => {
    const tokens = new Set(cobolInsightTheme().rules.map((rule) => rule.token));
    for (const token of ["sequence", "indicator", "comment", "keyword", "identifier.name"]) {
      expect(tokens, token).toContain(token);
    }
  });
});

describe("registerLanguages", () => {
  it("registers each language once and defines the theme", () => {
    const target: LanguageRegistrationTarget = {
      languages: { register: vi.fn(), setMonarchTokensProvider: vi.fn() },
      editor: { defineTheme: vi.fn() },
    };
    registerLanguages(target);
    registerLanguages(target);
    expect(target.languages.register).toHaveBeenCalledTimes(4);
    expect(target.editor.defineTheme).toHaveBeenCalledWith(
      COBOL_INSIGHT_THEME,
      expect.objectContaining({ base: "vs-dark" }),
    );
  });
});
