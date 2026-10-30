import type { ReactElement } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { text } from "../i18n/text";
import { ErrorBoundary } from "./ErrorBoundary";

function Child({ broken }: { broken: boolean }): ReactElement {
  if (broken) {
    throw new Error("cannot draw this tab");
  }
  return <p>drawn</p>;
}

function Tree({ resetKey, broken }: { resetKey: string; broken: boolean }): ReactElement {
  return (
    <ErrorBoundary resetKey={resetKey} message={text.editor.failed}>
      <Child broken={broken} />
    </ErrorBoundary>
  );
}

describe("ErrorBoundary", () => {
  // React reports a render failure on the console even when a boundary holds it.
  beforeEach(() => {
    vi.spyOn(console, "error").mockImplementation(() => undefined);
  });
  afterEach(() => vi.restoreAllMocks());

  it("shows the failure text and the error's message when a child throws while rendering", () => {
    render(<Tree resetKey="a" broken />);

    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent(text.editor.failed);
    expect(alert).toHaveTextContent("cannot draw this tab");
    expect(screen.queryByText("drawn")).toBeNull();
  });

  it("renders the children again once the reset key changes", () => {
    const view = render(<Tree resetKey="a" broken />);
    expect(screen.getByRole("alert")).toBeInTheDocument();

    // The same key keeps the failure on screen even though the child would draw now.
    view.rerender(<Tree resetKey="a" broken={false} />);
    expect(screen.getByRole("alert")).toBeInTheDocument();

    view.rerender(<Tree resetKey="b" broken={false} />);
    expect(screen.getByText("drawn")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("draws the children again when the retry button is pressed", () => {
    let failing = true;
    function Flaky(): ReactElement {
      if (failing) {
        throw new Error("cannot draw this tab");
      }
      return <p>drawn</p>;
    }
    render(
      <ErrorBoundary resetKey="a" message={text.editor.failed}>
        <Flaky />
      </ErrorBoundary>,
    );
    expect(screen.getByRole("alert")).toBeInTheDocument();

    failing = false;
    fireEvent.click(screen.getByRole("button", { name: text.editor.retry }));

    expect(screen.getByText("drawn")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("keeps the reason inside a details element", () => {
    render(<Tree resetKey="a" broken />);

    const details = screen.getByText("cannot draw this tab").closest("details");
    expect(details).not.toBeNull();
    expect(details).toContainElement(screen.getByText(text.editor.failedReason));
    expect(screen.getByText(text.editor.failed).closest("details")).toBeNull();
  });
});
