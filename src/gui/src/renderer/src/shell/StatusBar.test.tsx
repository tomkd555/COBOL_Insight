import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { text } from "../i18n/text";
import { EditorStatusProvider } from "../state/editorStatusStore";
import { ProjectProvider, initialProjectState } from "../state/projectStore";
import {
  WorkbenchProvider,
  initialWorkbenchState,
  sourceTab,
  sourceTabId,
  type WorkbenchState,
} from "../state/workbenchStore";
import { StatusBar } from "./StatusBar";

const PATH = "cobol/SYK001.cbl";

function renderStatusBar(draft: string | null): void {
  const workbench: WorkbenchState = {
    ...initialWorkbenchState,
    tabs: [sourceTab(PATH)],
    activeTabId: sourceTabId(PATH),
    drafts: draft === null ? {} : { [sourceTabId(PATH)]: draft },
  };
  render(
    <ProjectProvider initial={initialProjectState}>
      <WorkbenchProvider initial={workbench}>
        <EditorStatusProvider>
          <StatusBar />
        </EditorStatusProvider>
      </WorkbenchProvider>
    </ProjectProvider>,
  );
}

describe("StatusBar", () => {
  it("enables the codepage select while the active source tab holds no draft", () => {
    renderStatusBar(null);

    expect(screen.getByTestId("status-codepage")).toBeEnabled();
  });

  it("titles the codepage select with the reopen command while the tab holds no draft", () => {
    renderStatusBar(null);

    expect(screen.getByTestId("status-codepage")).toHaveAttribute("title", text.command.reopenWithEncoding);
  });

  it("disables the codepage select while the active source tab holds a draft", () => {
    renderStatusBar("edited text");

    expect(screen.getByTestId("status-codepage")).toBeDisabled();
  });

  it("titles the codepage select with the save notice while the tab holds a draft", () => {
    renderStatusBar("edited text");

    expect(screen.getByTestId("status-codepage")).toHaveAttribute("title", text.status.codepageAfterSave);
  });
});
