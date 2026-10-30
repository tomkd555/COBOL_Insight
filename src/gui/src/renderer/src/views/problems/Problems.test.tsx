import type { ReactElement } from "react";
import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { fireEvent } from "@testing-library/dom";
import type { SarifFinding } from "../../../../shared/ipc";
import { text } from "../../i18n/text";
import { ProjectProvider, initialProjectState } from "../../state/projectStore";
import { SettingsProvider } from "../../state/settingsStore";
import { WorkbenchProvider, useWorkbench } from "../../state/workbenchStore";
import { Problems } from "./Problems";

const FINDING: SarifFinding = {
  ruleId: "R026",
  level: "error",
  message: "パスワードの直書き",
  file: "cobol/SYK001.cbl",
  startLine: 10,
  startColumn: 1,
};

/** What the workbench holds, as the tab strip would draw it. */
function Tabs(): ReactElement {
  const { tabs, activeTabId } = useWorkbench();
  return (
    <ul data-testid="tabs" data-active={activeTabId ?? ""}>
      {tabs.map((tab) => (
        <li key={tab.id} data-testid={`tab-${tab.id}`} data-focus={tab.path ?? ""}>
          {tab.kind}
        </li>
      ))}
    </ul>
  );
}

function renderProblems(): void {
  render(
    <SettingsProvider>
      <ProjectProvider
        initial={{
          ...initialProjectState,
          mode: "results",
          findings: { status: "ready", items: [FINDING] },
          sqlFindings: { status: "ready", items: [] },
        }}
      >
        <WorkbenchProvider>
          <Problems onOpenAsset={vi.fn()} />
          <Tabs />
        </WorkbenchProvider>
      </ProjectProvider>
    </SettingsProvider>,
  );
}

describe("Problems", () => {
  it("shows no finding detail while no row is chosen", () => {
    renderProblems();

    expect(screen.getByTestId(/^finding-lint:/)).toBeInTheDocument();
    expect(screen.queryByTestId("problem-detail")).toBeNull();
  });

  it("opens the graph tab on the finding's program from the detail's graph button", () => {
    renderProblems();
    fireEvent.click(screen.getByTestId(/^finding-lint:/));

    fireEvent.click(screen.getByTestId("problem-detail-graph"));

    expect(screen.getByTestId("tabs")).toHaveAttribute("data-active", "graph");
    expect(screen.getByTestId("tab-graph")).toHaveAttribute("data-focus", "SYK001");
    expect(screen.getByRole("button", { name: text.command.showGraph })).toBe(
      screen.getByTestId("problem-detail-graph"),
    );
  });
});
