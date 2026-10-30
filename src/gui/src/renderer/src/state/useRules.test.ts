import { createElement, type ReactElement, type ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
import type { CobolInsightApi } from "../../../shared/ipc";
import type { RulesFile } from "../../../shared/rulesFile";
import { emptyRulesFile } from "../../../shared/rulesFile";
import { ProjectProvider, initialProjectState } from "./projectStore";
import { RulesProvider, initialRulesState } from "./rulesStore";
import { useRules } from "./useRules";

const RULES_PATH = "C:/data/rules.json";
const OUTPUT_PATHS = {
  db: "C:/data/p.db",
  sarif: "C:/data/lint.sarif",
  sqlSarif: "C:/data/sql.sarif",
  scopedSarif: "C:/data/scope.sarif",
  scopedSqlSarif: "C:/data/scope-sql.sarif",
  copyExpansion: "C:/data/copy.json",
  rules: RULES_PATH,
};

interface RulesApi {
  writeRules: ReturnType<typeof vi.fn>;
  rules: ReturnType<typeof vi.fn>;
}

/** The two members of the published API a rule write uses; `gate` holds the first write open. */
function fakeApi(gate: Promise<void> = Promise.resolve()): RulesApi {
  let writes = 0;
  const api: RulesApi = {
    writeRules: vi.fn(async () => {
      writes += 1;
      if (writes === 1) {
        await gate;
      }
    }),
    rules: vi.fn(async () => ({ rules: [], ruleErrors: [] })),
  };
  Object.defineProperty(window, "cobolInsight", {
    value: api as unknown as CobolInsightApi,
    configurable: true,
  });
  return api;
}

/** The hook over a store that holds a fresh file; the write queue starts from the file the store holds. */
function renderRules(loaded = true) {
  const file: RulesFile = emptyRulesFile();
  const wrapper = ({ children }: { children: ReactNode }): ReactElement =>
    createElement(
      ProjectProvider,
      {
        initial: { ...initialProjectState, outputPaths: OUTPUT_PATHS },
        children: createElement(RulesProvider, {
          initial: { ...initialRulesState, loaded, file },
          children,
        }),
      },
    );
  return renderHook(() => useRules(vi.fn()), { wrapper });
}

describe("useRules", () => {
  describe("writing the rule file", () => {
    it("writes two edits made back to back one after the other", async () => {
      let release: () => void = () => undefined;
      const gate = new Promise<void>((done) => {
        release = done;
      });
      const api = fakeApi(gate);
      const { result } = renderRules();

      await act(async () => {
        result.current.setRulesEnabled(["R001"], false);
        result.current.setRuleSeverity("R002", "LOW");
        await Promise.resolve();
      });

      // The second edit waits behind the first, which is still being written.
      expect(api.writeRules).toHaveBeenCalledTimes(1);

      await act(async () => {
        release();
      });

      expect(api.writeRules).toHaveBeenCalledTimes(2);
    });

    it("applies the second edit to the file the first one wrote", async () => {
      const api = fakeApi();
      const { result } = renderRules();

      await act(async () => {
        result.current.setRulesEnabled(["R001"], false);
        result.current.setRuleSeverity("R002", "LOW");
      });

      expect(api.writeRules).toHaveBeenCalledTimes(2);
      const first = api.writeRules.mock.calls[0];
      const second = api.writeRules.mock.calls[1];
      expect(first[0]).toBe(RULES_PATH);
      expect((first[1] as RulesFile).rules).toEqual({ R001: { enabled: false } });
      expect((second[1] as RulesFile).rules).toEqual({
        R001: { enabled: false },
        R002: { severity: "LOW" },
      });
    });
  });

  describe("writing the rule file before it is loaded", () => {
    it("writes nothing and asks the engine nothing", async () => {
      const api = fakeApi();
      const { result } = renderRules(false);

      await act(async () => {
        result.current.setRulesEnabled(["R001"], false);
        result.current.setRuleSeverity("R002", "LOW");
      });

      expect(api.writeRules).not.toHaveBeenCalled();
      expect(api.rules).not.toHaveBeenCalled();
    });
  });
});
