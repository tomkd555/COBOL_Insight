import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import type { CustomRule } from "../../../../shared/rulesFile";
import { text } from "../../i18n/text";
import { CustomRuleForm } from "./CustomRuleForm";

function show(rule: CustomRule, onChange = vi.fn()): typeof onChange {
  render(<CustomRuleForm rule={rule} problems={[]} engineErrors={[]} onChange={onChange} onRemove={vi.fn()} />);
  return onChange;
}

describe("CustomRuleForm", () => {
  describe("the form of a definition typed by hand", () => {
    it("sends a definition with values of another type to the JSON pane", () => {
      show({
        id: "U030",
        name: "READ",
        message: "m",
        targets: "COBOL",
        match: { kind: "statement", verb: "READ" },
      } as unknown as CustomRule);

      expect(screen.getByTestId("custom-json-only-U030")).toHaveTextContent(text.customRules.jsonOnly);
      expect(screen.queryByLabelText(text.customRules.fieldVerb)).toBeNull();
      expect(screen.getByTestId("custom-remove-U030")).toBeInTheDocument();
    });

    it("renders a definition whose id and name are objects", () => {
      show({ id: { a: 1 }, name: { toString: 1 }, message: [], match: "x" } as unknown as CustomRule);

      expect(screen.getByTestId("custom-json-only-")).toBeInTheDocument();
    });

    it("sends a detection of a kind it has no fields for to the JSON pane", () => {
      show({ id: "U031", name: "n", message: "m", match: { kind: "future" } } as unknown as CustomRule);

      expect(screen.getByTestId("custom-json-only-U031")).toBeInTheDocument();
    });

    it.each([
      ["a target spelled in lower case", { targets: ["cobol"] }],
      ["a command outside the options", { commands: ["REPORT"] }],
      ["a name with a line break", { name: "first\nsecond" }],
      ["a regular expression with a line break", { match: { kind: "line", regex: "A\nB" } }],
      ["a verb holding a comma", { match: { kind: "statement", verb: ["READ,WRITE"] } }],
      ["a data item holding 、", { match: { kind: "checked-after", checks: { dataItem: ["WS-A、WS-B"] } } }],
      ["an empty exclusion read from the file", { match: { kind: "line", regex: "A", excludeRegex: "" } }],
    ])("sends a definition with %s to the JSON pane", (_case, patch) => {
      show({ id: "U037", name: "n", message: "m", match: { kind: "line", regex: "A" }, ...patch } as unknown as CustomRule);

      expect(screen.getByTestId("custom-json-only-U037")).toHaveTextContent(text.customRules.jsonOnly);
      expect(screen.queryByLabelText(text.customRules.fieldName)).toBeNull();
    });

    it("shows the engine's complaints under a definition sent to the JSON pane", () => {
      render(
        <CustomRuleForm
          rule={{ id: "U038", name: "n", message: "m", match: { kind: "future" } } as unknown as CustomRule}
          problems={[]}
          engineErrors={["U038: kind future is unknown"]}
          onChange={vi.fn()}
          onRemove={vi.fn()}
        />,
      );

      expect(screen.getByTestId("custom-json-only-U038")).toBeInTheDocument();
      expect(screen.getByRole("alert")).toHaveTextContent("U038: kind future is unknown");
    });

    it.each([
      ["no detection", undefined],
      ["a null detection", null],
    ])("sends a definition with %s to the JSON pane", (_case, match) => {
      show({ id: "U039", name: "n", message: "m", match } as unknown as CustomRule);

      expect(screen.getByTestId("custom-json-only-U039")).toBeInTheDocument();
      expect(screen.queryByLabelText(text.customRules.fieldRegex)).toBeNull();
    });

    it("sends a definition read with its targets in another order to the JSON pane", () => {
      show({ id: "U040", name: "n", message: "m", targets: ["JCL", "COBOL"], match: { kind: "line", regex: "A" } });

      expect(screen.getByTestId("custom-json-only-U040")).toBeInTheDocument();
      expect(screen.queryByLabelText(text.rules.targetName("JCL"))).toBeNull();
    });

    it("renders a checked-after detection whose parts were left out", () => {
      show({ id: "U032", name: "n", message: "m", match: { kind: "checked-after" } } as unknown as CustomRule);

      expect(screen.getByLabelText(text.customRules.fieldAfterVerb)).toHaveValue("");
      expect(screen.getByLabelText(text.customRules.fieldDataItem)).toHaveValue("");
    });
  });

  describe("the check boxes of a list", () => {
    it("put a ticked command among the ticked ones in the order of the options", () => {
      const onChange = show({
        id: "U033",
        name: "n",
        message: "m",
        commands: ["LINT", "FIX"],
        match: { kind: "line", regex: "A" },
      });

      fireEvent.click(screen.getByLabelText(text.rules.commandName("SQL_LINT")));

      expect(onChange).toHaveBeenCalledWith(
        expect.objectContaining({ commands: ["LINT", "SQL_LINT", "FIX"] }),
      );
    });

    it("are named by their caption without being pressed by it", () => {
      const onChange = show({ id: "U034", name: "n", message: "m", match: { kind: "line", regex: "A" } });

      fireEvent.click(screen.getByText(text.customRules.fieldCommands));

      expect(onChange).not.toHaveBeenCalled();
      expect(screen.getByRole("group", { name: text.customRules.fieldCommands })).toBeInTheDocument();
    });
  });

  describe("the severity of a definition", () => {
    it("is shown whatever case it was typed in", () => {
      show({ id: "U035", name: "n", message: "m", severity: "low", match: { kind: "line", regex: "A" } });

      expect(screen.getByLabelText(text.customRules.fieldSeverity)).toHaveValue("LOW");
    });
  });

  describe("the data items of a checked-after rule", () => {
    it("are edited with the other members of checks left in place", () => {
      const onChange = show({
        id: "U036",
        name: "n",
        message: "m",
        match: { kind: "checked-after", after: { verb: "CALL" }, checks: { dataItem: ["RETURN-CODE"], flavour: ["x"] } },
      } as unknown as CustomRule);

      fireEvent.change(screen.getByLabelText(text.customRules.fieldDataItem), { target: { value: "RETURN-CODE, WS-STATUS" } });

      expect(onChange).toHaveBeenCalledWith(
        expect.objectContaining({
          match: expect.objectContaining({ checks: { dataItem: ["RETURN-CODE", "WS-STATUS"], flavour: ["x"] } }),
        }),
      );
    });
  });
});
