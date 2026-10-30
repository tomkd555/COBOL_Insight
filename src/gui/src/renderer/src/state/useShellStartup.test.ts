import { createElement, type ReactElement, type ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import type { CobolInsightApi, EngineOutputPaths } from "../../../shared/ipc";
import type { RulesFile } from "../../../shared/rulesFile";
import { RULES_FILE_VERSION } from "../../../shared/rulesFile";
import { ProjectProvider, useProject } from "./projectStore";
import { RulesProvider, useRulesState } from "./rulesStore";
import { SettingsProvider } from "./settingsStore";
import { useShellStartup } from "./useShellStartup";
import { WorkbenchProvider } from "./workbenchStore";

const PATHS: EngineOutputPaths = {
  db: "C:/data/p.db",
  sarif: "C:/data/lint.sarif",
  sqlSarif: "C:/data/sql.sarif",
  scopedSarif: "C:/data/scope.sarif",
  scopedSqlSarif: "C:/data/scope-sql.sarif",
  copyExpansion: "C:/data/copy.json",
  rules: "C:/data/rules.json",
};

const FILE: RulesFile = {
  version: RULES_FILE_VERSION,
  rules: { R001: { enabled: false } },
  custom: [],
};

/** The members of the published API the start-up uses; `listing` is what the engine's rule listing does. */
function fakeApi(listing: () => Promise<unknown>): void {
  const api = {
    readSettings: vi.fn(() => new Promise<never>(() => undefined)),
    outputPaths: vi.fn(async () => PATHS),
    readRules: vi.fn(async () => FILE),
    rules: vi.fn(listing),
  };
  Object.defineProperty(window, "cobolInsight", {
    value: api as unknown as CobolInsightApi,
    configurable: true,
  });
}

function renderStartup(notify = vi.fn()) {
  const wrapper = ({ children }: { children: ReactNode }): ReactElement =>
    createElement(
      SettingsProvider,
      null,
      createElement(
        WorkbenchProvider,
        null,
        createElement(ProjectProvider, null, createElement(RulesProvider, null, children)),
      ),
    );
  const hook = renderHook(
    () => {
      useShellStartup(notify);
      return { rules: useRulesState(), project: useProject() };
    },
    { wrapper },
  );
  return { ...hook, notify };
}

describe("useShellStartup", () => {
  it("dispatches LOAD as soon as the file has been read, while the engine's listing is still pending", async () => {
    fakeApi(() => new Promise<never>(() => undefined));
    const { result } = renderStartup();

    await waitFor(() => expect(result.current.rules.loaded).toBe(true));

    expect(result.current.rules.file).toEqual(FILE);
    expect(result.current.project.rules.entries).toEqual([]);
  });

  it("dispatches LOAD when the engine's listing rejects, and reports the failure", async () => {
    fakeApi(async () => {
      throw new Error("the engine could not start");
    });
    const { result, notify } = renderStartup();

    await waitFor(() => expect(notify).toHaveBeenCalledWith("the engine could not start", true));

    expect(result.current.rules.loaded).toBe(true);
    expect(result.current.rules.file).toEqual(FILE);
    expect(result.current.project.rulesError).toBe("the engine could not start");
  });

  it("fills the catalog once the listing arrives", async () => {
    fakeApi(async () => ({ rules: [], ruleErrors: ["U001: bad"] }));
    const { result } = renderStartup();

    await waitFor(() => expect(result.current.project.ruleErrors).toEqual(["U001: bad"]));

    expect(result.current.rules.loaded).toBe(true);
  });
});
