import { describe, it, expect } from "vitest";
import { SEVERITY_META, SEVERITY_ORDER, SEVERITY_BY_LABEL, type Severity } from "./severity";

describe("severity モデル", () => {
  it("4段階の重大度を高→中→低→警告の順で保持する", () => {
    expect(SEVERITY_ORDER).toEqual(["high", "medium", "low", "warning"]);
  });

  it.each([
    ["high", "高", "●", "var(--ci-sev-high)"],
    ["medium", "中", "◆", "var(--ci-sev-medium)"],
    ["low", "低", "■", "var(--ci-sev-low)"],
    ["warning", "警告", "▲", "var(--ci-sev-warning)"],
  ])("%s はラベル・記号・色トークンを二重符号化で対応させる", (sev, label, symbol, colorVar) => {
    const meta = SEVERITY_META[sev as Severity];
    expect(meta.label).toBe(label);
    expect(meta.symbol).toBe(symbol);
    expect(meta.colorVar).toBe(colorVar);
    expect(meta.modifier).toBe(sev);
  });

  it("日本語ラベルから Severity を逆引きできる", () => {
    expect(SEVERITY_BY_LABEL["高"]).toBe("high");
    expect(SEVERITY_BY_LABEL["警告"]).toBe("warning");
  });
});
