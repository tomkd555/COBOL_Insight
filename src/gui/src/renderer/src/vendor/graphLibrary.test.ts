import { describe, it, expect } from "vitest";
import { graphLibrary } from "./graphLibrary";

describe("graphLibrary(Cytoscape + ELK のローカル読込)", () => {
  it("elk レイアウトを登録した cytoscape を返す", () => {
    const cytoscape = graphLibrary();
    const cy = cytoscape({
      headless: true,
      elements: [
        { data: { id: "SYK001" } },
        { data: { id: "SYK002" } },
        { data: { id: "e1", source: "SYK001", target: "SYK002" } },
      ],
    });
    // 未登録のレイアウト名では cytoscape が例外を投げるため、これが登録の確認になる。
    expect(cy.layout({ name: "elk" })).toBeDefined();
    cy.destroy();
  });

  it("繰り返し呼んでも二重登録の例外を出さない", () => {
    expect(graphLibrary()).toBe(graphLibrary());
  });
});
