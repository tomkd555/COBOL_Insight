import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";
import type { CobolInsightApi } from "../../../shared/ipc";
import { emptyAppSettings } from "../../../shared/settings";
import { emptyRulesFile } from "../../../shared/rulesFile";
import { text } from "../i18n/text";
import { App } from "../App";

// A view that throws while it renders; the flags say which one does in the case at hand.
const failing = vi.hoisted(() => ({ explorer: false, problems: false, report: false }));

vi.mock("../views/explorer/Explorer", () => ({
  Explorer: () => {
    if (failing.explorer) throw new Error("the explorer broke");
    return <div data-testid="explorer-drawn" />;
  },
}));

vi.mock("../editors/report/ReportEditor", () => ({
  ReportEditor: () => {
    if (failing.report) throw new Error("the report broke");
    return <div data-testid="report-drawn" />;
  },
}));

vi.mock("../views/problems/Problems", () => ({
  Problems: () => {
    if (failing.problems) throw new Error("the problems table broke");
    return <div data-testid="problems-drawn" />;
  },
}));

const OUTPUT_PATHS = {
  db: "C:/data/p.db",
  sarif: "C:/data/lint.sarif",
  sqlSarif: "C:/data/sql.sarif",
  scopedSarif: "C:/data/scope.sarif",
  scopedSqlSarif: "C:/data/scope-sql.sarif",
  copyExpansion: "C:/data/copy.json",
  rules: "C:/data/rules.json",
};

beforeEach(() => {
  failing.explorer = false;
  failing.problems = false;
  failing.report = false;
  // React reports a render failure on the console even when a boundary holds it.
  vi.spyOn(console, "error").mockImplementation(() => undefined);
  const api = {
    readSettings: vi.fn(async () => emptyAppSettings()),
    writeSettings: vi.fn(async () => undefined),
    outputPaths: vi.fn(async () => OUTPUT_PATHS),
    rules: vi.fn(async () => ({ rules: [], ruleErrors: [] })),
    readRules: vi.fn(async () => emptyRulesFile()),
  };
  Object.defineProperty(window, "cobolInsight", { value: api as unknown as CobolInsightApi, configurable: true });
});

afterEach(() => vi.restoreAllMocks());

describe("App", () => {
  describe("a view that throws while it renders", () => {
    it("leaves the rest of the shell on screen when the side bar view throws", async () => {
      failing.explorer = true;
      render(<App />);

      const side = await screen.findByTestId("sidepanel");
      expect(within(side).getByRole("alert")).toHaveTextContent(text.editor.viewFailed);
      expect(within(side).getByRole("alert")).toHaveTextContent("the explorer broke");
      expect(screen.getByTestId("titlebar")).toBeInTheDocument();
      expect(screen.getByTestId("activitybar")).toBeInTheDocument();
      expect(screen.getByTestId("editorarea")).toBeInTheDocument();
      expect(screen.getByTestId("statusbar")).toBeInTheDocument();
      expect(within(screen.getByTestId("bottompanel")).getByTestId("problems-drawn")).toBeInTheDocument();
    });

    it("leaves the shell working when the editor in front throws", async () => {
      failing.report = true;
      render(<App />);

      fireEvent.click(await screen.findByTestId("activity-report"));

      const editor = screen.getByTestId("editorarea");
      expect(within(editor).getByRole("alert")).toHaveTextContent(text.editor.failed);
      expect(within(editor).getByRole("alert")).toHaveTextContent("the report broke");
      expect(within(screen.getByTestId("sidepanel")).getByTestId("explorer-drawn")).toBeInTheDocument();
      expect(within(screen.getByTestId("bottompanel")).getByTestId("problems-drawn")).toBeInTheDocument();
      // The shell still answers: a chord hides the side bar, and the activity bar opens another editor.
      fireEvent.keyDown(window, { key: "b", ctrlKey: true });
      expect(screen.queryByTestId("sidepanel")).toBeNull();
      fireEvent.click(screen.getByTestId("activity-graph"));
      expect(within(screen.getByTestId("editorarea")).queryByRole("alert")).toBeNull();
    });

    it("leaves the rest of the shell on screen when the panel view throws", async () => {
      failing.problems = true;
      render(<App />);

      const panel = await screen.findByTestId("bottompanel");
      expect(within(panel).getByRole("alert")).toHaveTextContent(text.editor.viewFailed);
      expect(within(panel).getByRole("alert")).toHaveTextContent("the problems table broke");
      expect(screen.getByTestId("titlebar")).toBeInTheDocument();
      expect(screen.getByTestId("activitybar")).toBeInTheDocument();
      expect(screen.getByTestId("editorarea")).toBeInTheDocument();
      expect(screen.getByTestId("statusbar")).toBeInTheDocument();
      expect(within(screen.getByTestId("sidepanel")).getByTestId("explorer-drawn")).toBeInTheDocument();
    });
  });
});
