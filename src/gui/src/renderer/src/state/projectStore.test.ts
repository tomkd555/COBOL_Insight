import { describe, expect, it } from "vitest";
import type { SarifFinding } from "../../../shared/ipc";
import {
  artifactCount,
  artifactItems,
  initialProjectState,
  projectReducer,
  structureScanDue,
  structureState,
  type ArtifactState,
  type ProjectAction,
  type ProjectState,
} from "./projectStore";

function apply(state: ProjectState, ...actions: ProjectAction[]): ProjectState {
  return actions.reduce(projectReducer, state);
}

function finding(file: string, ruleId = "R001"): SarifFinding {
  return { ruleId, level: "warning", message: "m", file, startLine: 1, startColumn: 1 };
}

describe("projectReducer", () => {
  describe("the run lifecycle", () => {
    it("discards the previous findings when a run starts", () => {
      const withResults = apply(
        initialProjectState,
        { type: "SET_INVENTORY", result: { status: "ready", items: [] }, dbPath: "C:/p.db" },
        { type: "SET_FINDINGS", result: { status: "ready", items: [finding("a.cbl")] } },
        { type: "START_RUN" },
      );
      expect(withResults.mode).toBe("running");
      expect(withResults.runStage).toBe(1);
      expect(withResults.findings.status).toBe("none");
    });

    it("keeps the inventory and project file path when a run starts", () => {
      const withResults = apply(
        initialProjectState,
        { type: "SET_INVENTORY", result: { status: "ready", items: [] }, dbPath: "C:/p.db" },
        { type: "SET_FINDINGS", result: { status: "ready", items: [finding("a.cbl")] } },
        { type: "START_RUN" },
      );
      // The tree keeps its listing while the folder is scanned again, and after a cancelled scan.
      expect(withResults.inventory.status).toBe("ready");
      // The project file path survives: it names where the next run writes, so it belongs to the
      // folder and outlives each run's results.
      expect(withResults.dbPath).toBe("C:/p.db");
    });

    it("clears the project file when the scan wrote none", () => {
      const crashed = apply(
        initialProjectState,
        { type: "SET_INVENTORY", result: { status: "ready", items: [] }, dbPath: "C:/p.db" },
        { type: "SET_INVENTORY", result: { status: "error", message: "読めません" }, dbPath: null },
      );
      // The graph and the transpile view read this path; keeping it would draw the previous run's rows.
      expect(crashed.dbPath).toBeNull();
    });

    it("drops everything read from the previous folder when another one is chosen", () => {
      const switched = apply(
        initialProjectState,
        { type: "SET_INPUT_DIR", inputDir: "C:/a" },
        { type: "SET_INVENTORY", result: { status: "ready", items: [] }, dbPath: "C:/p.db" },
        { type: "SET_FINDINGS", result: { status: "ready", items: [finding("a.cbl")] } },
        { type: "FINISH_RUN", failed: false, lint: "whole" },
        { type: "SET_INPUT_DIR", inputDir: "C:/b" },
      );
      expect(switched.inputDir).toBe("C:/b");
      expect(switched.dbPath).toBeNull();
      expect(switched.inventory.status).toBe("none");
      expect(switched.findings.status).toBe("none");
      // The SARIF pair the report is generated from was written from the previous folder.
      expect(switched.lastWholeFolderRunId).toBe(0);
      expect(switched.lastScopedRunId).toBe(0);
    });

    it("goes to results or to error according to what the run reported", () => {
      expect(apply(initialProjectState, { type: "FINISH_RUN", failed: false }).mode).toBe("results");
      expect(apply(initialProjectState, { type: "FINISH_RUN", failed: true }).mode).toBe("error");
    });

    it("clears the stale-rules note only after a successful whole-folder lint", () => {
      const changed = apply(initialProjectState, { type: "RULES_WRITTEN" });
      const failed = projectReducer(changed, { type: "FINISH_RUN", failed: true, lint: "whole" });
      expect(failed.rulesChangedAfterRun).toBe(true);
      // A scoped run left everything outside its scope as the previous rules found it.
      const scoped = projectReducer(changed, { type: "FINISH_RUN", failed: false, lint: "scoped" });
      expect(scoped.rulesChangedAfterRun).toBe(true);
      const whole = projectReducer(changed, { type: "FINISH_RUN", failed: false, lint: "whole" });
      expect(whole.rulesChangedAfterRun).toBe(false);
    });

    it("keeps the findings through a scan that only brings the structure up to date", () => {
      const refreshed = apply(
        initialProjectState,
        { type: "SET_FINDINGS", result: { status: "ready", items: [finding("a.cbl")] } },
        { type: "SET_SAVE_FINDINGS", path: "a.cbl", findings: [finding("a.cbl", "parse-failure")] },
        { type: "START_RUN", keepFindings: true },
      );
      expect(refreshed.mode).toBe("running");
      expect(artifactItems(refreshed.findings)).toHaveLength(1);
      expect(refreshed.saveFindings).toHaveLength(1);
    });

    it("marks written assets stale until a later scan has read the folder", () => {
      const saved = apply(
        initialProjectState,
        { type: "SOURCE_SAVED", path: "cobol/a.cbl" },
        { type: "SOURCE_SAVED", path: "cobol/a.cbl" },
        { type: "SOURCE_SAVED", path: "cobol/b.cbl" },
      );
      expect(saved.structureStale).toEqual(["cobol/a.cbl", "cobol/b.cbl"]);
      // The views drawn from the folder are redrawn by the scan that follows a write.
      expect(saved.runId).toBe(0);

      expect(structureScanDue(saved)).toBe(true);

      // b was written while the scan that started after the first two writes was running: that scan
      // clears nothing and another is due; the one that starts after all three clears the list.
      const scanned = projectReducer(saved, { type: "STRUCTURE_SCANNED", writes: 2, ok: true });
      expect(scanned.structureStale).toEqual(["cobol/a.cbl", "cobol/b.cbl"]);
      expect(scanned.runId).toBe(1);
      expect(structureScanDue(scanned)).toBe(true);
      const level = projectReducer(saved, { type: "STRUCTURE_SCANNED", writes: 3, ok: true });
      expect(level.structureStale).toEqual([]);
      expect(level.runId).toBe(1);
      expect(structureScanDue(level)).toBe(false);

      const switched = apply(saved, { type: "SET_INPUT_DIR", inputDir: "C:/other" });
      expect(switched.structureStale).toEqual([]);
    });

    it("keeps the assets stale after a failed scan", () => {
      const saved = apply(initialProjectState, { type: "SOURCE_SAVED", path: "cobol/a.cbl" });
      const failed = projectReducer(saved, { type: "STRUCTURE_SCANNED", writes: 1, ok: false });
      expect(failed.structureStale).toEqual(["cobol/a.cbl"]);
      // STRUCTURE_SCANNED with ok false leaves the epoch alone; the views redraw on the FINISH_RUN
      // that reports the failure.
      expect(failed.runId).toBe(saved.runId);
    });

    it("makes a scan due again after a failed scan only once the next write arrives", () => {
      const saved = apply(initialProjectState, { type: "SOURCE_SAVED", path: "cobol/a.cbl" });
      const failed = projectReducer(saved, { type: "STRUCTURE_SCANNED", writes: 1, ok: false });
      expect(structureScanDue(failed)).toBe(false);
      expect(structureScanDue(apply(failed, { type: "SOURCE_SAVED", path: "cobol/a.cbl" }))).toBe(true);
    });

    it("drops the codepage overrides when another folder is opened", () => {
      const opened = apply(
        initialProjectState,
        { type: "SET_INPUT_DIR", inputDir: "C:/assets" },
        { type: "SET_CODEPAGE", path: "cobol/a.cbl", charset: "IBM930" },
      );
      expect(apply(opened, { type: "SET_INPUT_DIR", inputDir: "C:/other" }).codepageOverrides).toEqual({});
    });

    it("keeps the codepage overrides when the same folder is opened again", () => {
      const opened = apply(
        initialProjectState,
        { type: "SET_INPUT_DIR", inputDir: "C:/assets" },
        { type: "SET_CODEPAGE", path: "cobol/a.cbl", charset: "IBM930" },
      );
      expect(apply(opened, { type: "SET_INPUT_DIR", inputDir: "C:/assets" }).codepageOverrides).toEqual({
        "cobol/a.cbl": "IBM930",
      });
    });

    it("reports written assets as reading until the scan after them ends", () => {
      expect(structureState(initialProjectState)).toBeNull();
      const saved = apply(initialProjectState, { type: "SOURCE_SAVED", path: "cobol/a.cbl" });
      expect(structureState(saved)).toBe("reading");
      expect(structureState(apply(saved, { type: "START_RUN", keepFindings: true }))).toBe("reading");
      expect(structureState(apply(saved, { type: "STRUCTURE_SCANNED", writes: 1, ok: true }))).toBeNull();
    });

    it("reports written assets as unread once the scan failed", () => {
      const saved = apply(initialProjectState, { type: "SOURCE_SAVED", path: "cobol/a.cbl" });
      const failed = apply(saved, { type: "STRUCTURE_SCANNED", writes: 1, ok: false });
      expect(structureState(failed)).toBe("unread");
    });

    it("leaves a cancelled run in results, so the finished stages stay on screen", () => {
      expect(apply(initialProjectState, { type: "START_RUN" }, { type: "CANCEL_RUN" }).mode).toBe(
        "results",
      );
    });
  });

  describe("cancelling a run", () => {
    it("keeps the artefact epoch where the scan of that run left it", () => {
      const running = apply(initialProjectState, { type: "START_RUN" });
      // A run stopped in its first stage leaves the epoch where it was: no scan reported reading the
      // folder.
      expect(projectReducer(running, { type: "CANCEL_RUN" }).runId).toBe(running.runId);

      // A run stopped in its lint had its scan finish: the views read the project file again.
      const scanned = projectReducer(running, { type: "STRUCTURE_SCANNED", writes: 0, ok: true });
      expect(projectReducer(scanned, { type: "CANCEL_RUN" }).runId).toBe(running.runId + 1);
    });
  });

  describe("artefacts", () => {
    it("keeps a failure distinct from an empty result", () => {
      const failed = apply(initialProjectState, {
        type: "SET_FINDINGS",
        result: { status: "error", message: "the SARIF is unreadable" },
      });
      expect(artifactCount(failed.findings)).toBeNull();
      expect(artifactItems(failed.findings)).toEqual([]);

      const clean = apply(initialProjectState, {
        type: "SET_FINDINGS",
        result: { status: "ready", items: [] },
      });
      expect(artifactCount(clean.findings)).toBe(0);
    });

    it("replaces findings inside the run scope, keeping those outside", () => {
      const before = apply(initialProjectState, {
        type: "SET_FINDINGS",
        result: {
          status: "ready",
          items: [finding("cobol/a.cbl", "R001"), finding("cobol/b.cbl", "R002"), finding("jcl/c.jcl", "R003")],
        },
      });

      // One asset analysed again: its finding is the new one, and the other two stand.
      const file = projectReducer(before, {
        type: "SET_FINDINGS",
        result: { status: "ready", items: [finding("cobol/a.cbl", "R009")] },
        scope: "cobol/a.cbl",
      });
      expect(artifactItems(file.findings).map((item) => `${item.file} ${item.ruleId}`)).toEqual([
        "cobol/b.cbl R002",
        "jcl/c.jcl R003",
        "cobol/a.cbl R009",
      ]);

      // A folder analysed again: every finding under it goes, including the one the run no longer
      // reports, and the assets outside the folder are untouched.
      const folder = projectReducer(before, {
        type: "SET_FINDINGS",
        result: { status: "ready", items: [finding("cobol/b.cbl", "R009")] },
        scope: "cobol",
      });
      expect(artifactItems(folder.findings).map((item) => `${item.file} ${item.ruleId}`)).toEqual([
        "jcl/c.jcl R003",
        "cobol/b.cbl R009",
      ]);
    });

    it("replaces everything when the run covered the whole folder", () => {
      const before = apply(initialProjectState, {
        type: "SET_SQL_FINDINGS",
        result: { status: "ready", items: [finding("cobol/a.cbl"), finding("jcl/c.jcl")] },
      });
      const whole = projectReducer(before, {
        type: "SET_SQL_FINDINGS",
        result: { status: "ready", items: [finding("cobol/a.cbl")] },
      });
      expect(artifactItems(whole.sqlFindings)).toHaveLength(1);
    });

    it("replaces everything when the run failed", () => {
      const before = apply(initialProjectState, {
        type: "SET_SQL_FINDINGS",
        result: { status: "ready", items: [finding("cobol/a.cbl"), finding("jcl/c.jcl")] },
      });
      const failed = projectReducer(before, {
        type: "SET_SQL_FINDINGS",
        result: { status: "error", message: "壊れた SARIF" },
      });
      expect(failed.sqlFindings.status).toBe("error");
    });

    it("keeps every finding when a scoped run fails, since it analysed nothing", () => {
      const before = apply(initialProjectState, {
        type: "SET_FINDINGS",
        result: { status: "ready", items: [finding("cobol/a.cbl"), finding("jcl/c.jcl")] },
      });
      const failed = projectReducer(before, {
        type: "SET_FINDINGS",
        result: { status: "error", message: "見つかりませんでした" },
        scope: "cobol/a.cbl",
      });
      expect(artifactItems(failed.findings)).toHaveLength(2);
    });

    it("lists a finding the scoped run reports again only once, copybooks included", () => {
      const before = apply(initialProjectState, {
        type: "SET_FINDINGS",
        result: {
          status: "ready",
          items: [finding("cobol/a.cbl", "R001"), finding("copybook/c.cpy", "R026")],
        },
      });
      // A copybook an in-scope program expands is reported again although it is outside the scope.
      const scoped = { status: "ready", items: [finding("copybook/c.cpy", "R026")] } as const;
      const once = projectReducer(before, {
        type: "SET_FINDINGS",
        result: scoped,
        scope: "cobol/a.cbl",
      });
      const twice = projectReducer(once, {
        type: "SET_FINDINGS",
        result: scoped,
        scope: "cobol/a.cbl",
      });
      expect(artifactItems(twice.findings).map((item) => `${item.file} ${item.ruleId}`)).toEqual([
        "copybook/c.cpy R026",
      ]);
    });

    it("keeps save reparse findings until the scoped run re-checks them", () => {
      const saved = apply(
        initialProjectState,
        { type: "SET_FINDINGS", result: { status: "ready", items: [finding("cobol/a.cbl")] } },
        { type: "SET_SAVE_FINDINGS", path: "cobol/a.cbl", findings: [finding("cobol/a.cbl", "R002")] },
        { type: "SET_SAVE_FINDINGS", path: "cobol/b.cbl", findings: [finding("cobol/b.cbl", "R003")] },
        { type: "START_RUN", scope: "cobol/a.cbl" },
      );
      expect(artifactItems(saved.findings)).toHaveLength(1);
      // Nothing has been re-checked yet, so the parse error the save reported is still the last word.
      expect(saved.saveFindings.map((entry) => entry.file)).toEqual(["cobol/a.cbl", "cobol/b.cbl"]);

      const failed = projectReducer(saved, {
        type: "SET_FINDINGS",
        result: { status: "error", message: "解析できませんでした" },
        scope: "cobol/a.cbl",
      });
      expect(failed.saveFindings.map((entry) => entry.file)).toEqual(["cobol/a.cbl", "cobol/b.cbl"]);

      const finished = projectReducer(saved, {
        type: "SET_FINDINGS",
        result: { status: "ready", items: [] },
        scope: "cobol/a.cbl",
      });
      // The run re-checked its own scope; the reparse error of an asset it never touched stands.
      expect(finished.saveFindings.map((entry) => entry.file)).toEqual(["cobol/b.cbl"]);
    });

    it("drops the reparse findings of a save on the code result alone", () => {
      const saved = apply(initialProjectState, {
        type: "SET_SAVE_FINDINGS",
        path: "cobol/a.cbl",
        findings: [finding("cobol/a.cbl", "R002")],
      });
      // A save is reparsed as a program, so a scoped run whose code SARIF could not be read has
      // re-checked nothing, whatever its SQL SARIF says.
      const half = apply(
        saved,
        { type: "SET_FINDINGS", result: { status: "error", message: "読めませんでした" }, scope: "cobol/a.cbl" },
        { type: "SET_SQL_FINDINGS", result: { status: "ready", items: [] }, scope: "cobol/a.cbl" },
      );
      expect(half.saveFindings.map((entry) => entry.file)).toEqual(["cobol/a.cbl"]);
    });

    it("remembers which lint each run finished", () => {
      const state = apply(
        initialProjectState,
        { type: "FINISH_RUN", failed: false, lint: "whole" },
        { type: "FINISH_RUN", failed: false, lint: "scoped" },
      );
      expect(state.lastScopedRunId).toBeGreaterThan(state.lastWholeFolderRunId);
      // A run over the whole folder puts the report back level with what is on screen.
      const level = projectReducer(state, { type: "FINISH_RUN", failed: false, lint: "whole" });
      expect(level.lastWholeFolderRunId).toBeGreaterThan(level.lastScopedRunId);
      // A run that failed wrote no SARIF pair and left the screen alone, so neither id moves.
      const crashed = projectReducer(level, { type: "FINISH_RUN", failed: true, lint: "scoped" });
      expect(crashed.lastScopedRunId).toBe(level.lastScopedRunId);
      expect(crashed.lastWholeFolderRunId).toBe(level.lastWholeFolderRunId);
    });

    it("replaces the earlier verification result when an asset is saved again", () => {
      const state = apply(
        initialProjectState,
        { type: "SET_SAVE_FINDINGS", path: "a.cbl", findings: [finding("a.cbl", "R001")] },
        { type: "SET_SAVE_FINDINGS", path: "b.cbl", findings: [finding("b.cbl", "R002")] },
        { type: "SET_SAVE_FINDINGS", path: "a.cbl", findings: [finding("a.cbl", "R003")] },
      );
      expect(state.saveFindings.map((entry) => entry.ruleId)).toEqual(["R002", "R003"]);
    });
  });

  describe("the rule index", () => {
    it("indexes the catalog by rule id with the interface's severity", () => {
      const state = apply(initialProjectState, {
        type: "SET_RULES",
        entries: [
          {
            id: "R001",
            name: "n",
            category: "c",
            severity: "ADVISORY",
            hasFix: false,
            source: "builtin",
            enabled: true,
            commands: [],
            targets: [],
            summary: "",
            rationale: "",
            detection: "",
            remedy: "",
            badExample: "",
            goodExample: "",
          },
        ],
        ruleErrors: ["bad rule"],
      });
      expect(state.rules.byId.get("R001")?.severity).toBe("warning");
    });

    it("stores the rule errors of the catalog", () => {
      const state = apply(initialProjectState, {
        type: "SET_RULES",
        entries: [
          {
            id: "R001",
            name: "n",
            category: "c",
            severity: "ADVISORY",
            hasFix: false,
            source: "builtin",
            enabled: true,
            commands: [],
            targets: [],
            summary: "",
            rationale: "",
            detection: "",
            remedy: "",
            badExample: "",
            goodExample: "",
          },
        ],
        ruleErrors: ["bad rule"],
      });
      expect(state.ruleErrors).toEqual(["bad rule"]);
    });
  });

  describe("the run log", () => {
    it("timestamps each log line", () => {
      const state = apply(
        initialProjectState,
        { type: "LOG", text: "started" },
        { type: "LOG", text: "failed", failed: true },
      );
      expect(state.runLog).toHaveLength(2);
      expect(state.runLog[0].time).toMatch(/^\d{2}:\d{2}:\d{2}$/);
      expect(state.runLog[1].id).toBeGreaterThan(state.runLog[0].id);
    });

    it("marks a failed log line", () => {
      const state = apply(
        initialProjectState,
        { type: "LOG", text: "started" },
        { type: "LOG", text: "failed", failed: true },
      );
      expect(state.runLog[1].failed).toBe(true);
    });

    it("drops the oldest lines beyond the limit", () => {
      let state = initialProjectState;
      for (let index = 0; index < 250; index += 1) {
        state = projectReducer(state, { type: "LOG", text: `line ${index}` });
      }
      expect(state.runLog).toHaveLength(200);
      expect(state.runLog[state.runLog.length - 1].text).toBe("line 249");
    });

    it("clears the log on request", () => {
      expect(
        apply(initialProjectState, { type: "LOG", text: "x" }, { type: "CLEAR_LOG" }).runLog,
      ).toEqual([]);
    });
  });
});

describe("artifactItems", () => {
  it("returns the same empty list for every empty state, so a memo keyed on it settles", () => {
    const failed: ArtifactState<SarifFinding> = { status: "error", message: "unreadable" };
    expect(artifactItems(failed)).toBe(artifactItems({ status: "none" }));
  });
});
