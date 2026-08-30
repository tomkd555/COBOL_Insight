import { describe, expect, it } from "vitest";
import { parseRuleCatalog } from "./ruleCatalog";

describe("parseRuleCatalog", () => {
  it("reads the metadata, the prose and the V2 fields the engine now supplies", () => {
    const catalog = parseRuleCatalog({
      rules: [
        {
          id: "R001",
          name: "reference to an uninitialised item",
          category: "data flow",
          severity: "HIGH",
          phase: "DATA_FLOW",
          hasFix: true,
          source: "builtin",
          enabled: false,
          defaultEnabled: true,
          commands: ["lint"],
          targets: ["COBOL"],
          needs: ["dataflow"],
          summary: "s",
          rationale: "r",
          detection: "d",
          remedy: "m",
          badExample: "b",
          goodExample: "g",
        },
      ],
      ruleErrors: ["U002: the pattern does not compile"],
    });
    expect(catalog.rules[0]).toMatchObject({
      id: "R001",
      enabled: false,
      defaultEnabled: true,
      commands: ["lint"],
      targets: ["COBOL"],
      needs: ["dataflow"],
      hasFix: true,
    });
    expect(catalog.ruleErrors).toEqual(["U002: the pattern does not compile"]);
  });

  it("treats a rule as enabled when an older engine reports neither flag", () => {
    const catalog = parseRuleCatalog({ rules: [{ id: "R001" }] });
    expect(catalog.rules[0].enabled).toBe(true);
    expect(catalog.rules[0].defaultEnabled).toBe(true);
  });

  it("marks a user-defined rule as such and everything else as built-in", () => {
    const catalog = parseRuleCatalog({
      rules: [{ id: "U001", source: "user" }, { id: "R001", source: "whatever" }],
    });
    expect(catalog.rules.map((rule) => rule.source)).toEqual(["user", "builtin"]);
  });

  it("drops an entry with no id, which nothing could address", () => {
    expect(parseRuleCatalog({ rules: [{ name: "no id" }, { id: "" }, { id: "R001" }] }).rules).toHaveLength(1);
  });

  it("filters non-string members out of the list fields", () => {
    expect(parseRuleCatalog({ rules: [{ id: "R001", targets: ["COBOL", 7] }] }).rules[0].targets).toEqual([
      "COBOL",
    ]);
  });

  it("throws when the engine returned no listing at all", () => {
    expect(() => parseRuleCatalog(null)).toThrow(/no rule listing/);
  });
});
