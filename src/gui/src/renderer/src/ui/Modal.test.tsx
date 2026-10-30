import { useState, type ReactElement } from "react";
import { afterEach, describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { fireEvent } from "@testing-library/dom";
import { Modal } from "./Modal";

/** One function for every render, so the dialog's Tab key meets only the DOM as it stands. */
const dismiss = (): void => undefined;

/** A dialog whose last action is disabled until the box in its body is ticked, as the import dialog's is. */
function Gated(): ReactElement {
  const [ready, setReady] = useState(false);
  return (
    <Modal
      title="dialog"
      onDismiss={dismiss}
      actions={
        <>
          <button type="button" data-testid="cancel">
            cancel
          </button>
          <button type="button" data-testid="confirm" disabled={!ready}>
            confirm
          </button>
        </>
      }
    >
      <input type="checkbox" data-testid="gate" onChange={(event) => setReady(event.target.checked)} />
    </Modal>
  );
}

/** A button that opens a dialog, so there is a control that had the focus before it opened. */
function Opener(): ReactElement {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button type="button" data-testid="opener" onClick={() => setOpen(true)}>
        open
      </button>
      {open ? (
        <Modal
          title="dialog"
          onDismiss={() => setOpen(false)}
          actions={
            <button type="button" data-testid="close" onClick={() => setOpen(false)}>
              close
            </button>
          }
        />
      ) : null}
    </>
  );
}

describe("Modal", () => {
  describe("the Tab cycle of a dialog", () => {
    it("takes in a control that became enabled after the dialog opened", () => {
      render(<Gated />);
      // With the last action disabled, the focus starts on the nearest enabled one.
      expect(screen.getByTestId("cancel")).toHaveFocus();

      fireEvent.click(screen.getByTestId("gate"));
      screen.getByTestId("confirm").focus();
      fireEvent.keyDown(screen.getByTestId("confirm"), { key: "Tab" });

      // The enabled action is now the last control, so Tab from it wraps to the first.
      expect(screen.getByTestId("gate")).toHaveFocus();
    });

    it("wraps from the first control to the last on Shift+Tab", () => {
      render(<Gated />);
      screen.getByTestId("gate").focus();

      fireEvent.keyDown(screen.getByTestId("gate"), { key: "Tab", shiftKey: true });

      expect(screen.getByTestId("cancel")).toHaveFocus();
    });

    it("skips a control with tabindex -1, which is out of the Tab order", () => {
      render(
        <Modal
          title="dialog"
          onDismiss={dismiss}
          actions={
            <>
              <button type="button" data-testid="last">
                last
              </button>
              <button type="button" tabIndex={-1} data-testid="skipped">
                skipped
              </button>
            </>
          }
        >
          <input data-testid="first" />
        </Modal>,
      );
      // The last control in the Tab order takes the focus on opening, which leaves the one after it out.
      expect(screen.getByTestId("last")).toHaveFocus();

      fireEvent.keyDown(screen.getByTestId("last"), { key: "Tab" });
      expect(screen.getByTestId("first")).toHaveFocus();

      fireEvent.keyDown(screen.getByTestId("first"), { key: "Tab", shiftKey: true });
      expect(screen.getByTestId("last")).toHaveFocus();
    });
  });

  describe("the Tab stops a dialog counts", () => {
    const firsts: [string, ReactElement][] = [
      [
        "the summary of a details element",
        <details key="details">
          <summary data-testid="first">reason</summary>
        </details>,
      ],
      ["an element given tabindex 0", <div key="region" tabIndex={0} data-testid="first" />],
      [
        "an editable region",
        <div key="editable" contentEditable="true" suppressContentEditableWarning data-testid="first" />,
      ],
    ];

    it.each(firsts)("wraps between the last action and %s", (_name, first) => {
      render(
        <Modal
          title="dialog"
          onDismiss={dismiss}
          actions={
            <button type="button" data-testid="last">
              last
            </button>
          }
        >
          {first}
        </Modal>,
      );
      expect(screen.getByTestId("last")).toHaveFocus();

      fireEvent.keyDown(screen.getByTestId("last"), { key: "Tab" });
      expect(screen.getByTestId("first")).toHaveFocus();

      fireEvent.keyDown(screen.getByTestId("first"), { key: "Tab", shiftKey: true });
      expect(screen.getByTestId("last")).toHaveFocus();
    });
  });

  describe("the Tab stops a dialog leaves out", () => {
    afterEach(() => {
      delete (Element.prototype as { checkVisibility?: unknown }).checkVisibility;
    });

    it("passes over an element that is not rendered", () => {
      // What a browser answers for an element inside a closed details element.
      Element.prototype.checkVisibility = function (this: Element): boolean {
        return this.getAttribute("data-testid") !== "unrendered";
      };
      render(
        <Modal
          title="dialog"
          onDismiss={dismiss}
          actions={
            <button type="button" data-testid="last">
              last
            </button>
          }
        >
          <button type="button" data-testid="unrendered">
            unrendered
          </button>
          <input data-testid="first" />
        </Modal>,
      );

      fireEvent.keyDown(screen.getByTestId("last"), { key: "Tab" });

      expect(screen.getByTestId("first")).toHaveFocus();
    });
  });

  /** A dialog open from the start, with the focus on the document: no control opened it. */
  function Unopened({ target }: { target: () => HTMLElement | null }): ReactElement {
    const [open, setOpen] = useState(true);
    return (
      <>
        <button type="button" data-testid="target">
          target
        </button>
        {open ? (
          <Modal
            title="dialog"
            onDismiss={() => setOpen(false)}
            fallbackFocus={target}
            actions={
              <button type="button" data-testid="close" onClick={() => setOpen(false)}>
                close
              </button>
            }
          />
        ) : null}
      </>
    );
  }

  describe("the focus after a dialog no control can take it back for", () => {
    it("goes to the place the caller names", () => {
      render(<Unopened target={() => screen.getByTestId("target")} />);
      expect(screen.getByTestId("close")).toHaveFocus();

      fireEvent.click(screen.getByTestId("close"));

      expect(screen.getByTestId("target")).toHaveFocus();
    });
  });

  describe("the Escape key in a dialog", () => {
    it("dismisses the dialog", () => {
      render(<Opener />);
      fireEvent.click(screen.getByTestId("opener"));
      expect(screen.getByTestId("close")).toBeInTheDocument();

      fireEvent.keyDown(screen.getByTestId("close"), { key: "Escape" });

      expect(screen.queryByTestId("close")).toBeNull();
    });
  });

  describe("the name of a dialog", () => {
    it("is its title, through aria-labelledby", () => {
      render(<Modal title="Delete the file" onDismiss={dismiss} actions={<button type="button">ok</button>} />);

      const dialog = screen.getByRole("dialog", { name: "Delete the file" });
      const titleId = dialog.getAttribute("aria-labelledby");
      expect(titleId).not.toBeNull();
      expect(document.getElementById(titleId ?? "")).toHaveTextContent("Delete the file");
    });
  });

  describe("the focus around a dialog", () => {
    it("goes back to the control that had it before the dialog opened", () => {
      render(<Opener />);
      screen.getByTestId("opener").focus();
      fireEvent.click(screen.getByTestId("opener"));
      expect(screen.getByTestId("close")).toHaveFocus();

      fireEvent.click(screen.getByTestId("close"));

      expect(screen.queryByTestId("close")).toBeNull();
      expect(screen.getByTestId("opener")).toHaveFocus();
    });
  });
});
