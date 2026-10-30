import { describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { fireEvent } from "@testing-library/dom";
import type { CobolInsightApi, ImportSourceResult } from "../../../../shared/ipc";
import { text } from "../../i18n/text";
import { ProjectProvider, initialProjectState, useProject } from "../../state/projectStore";
import { ImportDialog } from "./ImportDialog";

const EDIT_SCREEN = [
  " EDIT       USER01.SRC.COBOL(PGM001) - 01.03                Columns 00001 00072",
  " Command ===>                                                  Scroll ===> CSR",
  " 000100        IDENTIFICATION DIVISION.",
].join("\n");

function install(results: readonly ImportSourceResult[]): ReturnType<typeof vi.fn> {
  const importSource = vi.fn();
  for (const result of results) {
    importSource.mockResolvedValueOnce(result);
  }
  Object.defineProperty(window, "cobolInsight", {
    value: { importSource } as unknown as CobolInsightApi,
    configurable: true,
  });
  return importSource;
}

/** The import call rejects with the message, as main's refusals reach the renderer. */
function installRejecting(message: string): ReturnType<typeof vi.fn> {
  const importSource = vi.fn().mockRejectedValue(new Error(message));
  Object.defineProperty(window, "cobolInsight", {
    value: { importSource } as unknown as CobolInsightApi,
    configurable: true,
  });
  return importSource;
}

/** What the project store holds of the writes, read out where the test can see it. */
function StoreProbe() {
  const project = useProject();
  return (
    <output data-testid="store">{JSON.stringify([project.structureStale, project.staleWrites])}</output>
  );
}

function renderDialog() {
  render(
    <ProjectProvider initial={{ ...initialProjectState, inputDir: "C:/assets" }}>
      <ImportDialog inputDir="C:/assets" onClose={vi.fn()} />
      <StoreProbe />
    </ProjectProvider>,
  );
}

function type(testId: string, value: string): void {
  fireEvent.change(screen.getByTestId(testId), { target: { value } });
}

/** The element an input names through aria-describedby. */
function describedBy(testId: string): HTMLElement | null {
  const id = screen.getByTestId(testId).getAttribute("aria-describedby");
  return id === null ? null : document.getElementById(id);
}

describe("ImportDialog", () => {
  describe("the fields of the dialog", () => {
    it("raises no error on an empty dialog", () => {
      renderDialog();

      for (const testId of ["import-destdir", "import-filename"]) {
        expect(screen.getByTestId(testId)).toHaveAttribute("aria-invalid", "false");
        expect(screen.getByTestId(testId)).not.toHaveAttribute("aria-describedby");
      }
      expect(screen.queryByText(text.import.destDirInvalid)).toBeNull();
      expect(screen.queryByText(text.import.fileNameInvalid)).toBeNull();
    });

    it("marks the folder field and names its own error when the folder cannot be used", () => {
      renderDialog();

      type("import-destdir", "../up");

      expect(screen.getByTestId("import-destdir")).toHaveAttribute("aria-invalid", "true");
      expect(describedBy("import-destdir")).toHaveTextContent(text.import.destDirInvalid);
      // The name has nothing wrong with it, so it carries no error of the folder's.
      expect(screen.getByTestId("import-filename")).toHaveAttribute("aria-invalid", "false");
      expect(screen.getByTestId("import-filename")).not.toHaveAttribute("aria-describedby");
    });

    it("marks the name field and names its own error when the name cannot be used", () => {
      renderDialog();

      type("import-filename", "sub/A.cbl");

      expect(screen.getByTestId("import-filename")).toHaveAttribute("aria-invalid", "true");
      expect(describedBy("import-filename")).toHaveTextContent(text.import.fileNameInvalid);
      expect(screen.getByTestId("import-destdir")).toHaveAttribute("aria-invalid", "false");
      expect(screen.getByTestId("import-destdir")).not.toHaveAttribute("aria-describedby");
    });

    it("asks for the name once there is text to save, and keeps 保存 off until it is given", () => {
      renderDialog();

      type("import-paste", "       IDENTIFICATION DIVISION.");

      expect(screen.getByTestId("import-name-required")).toHaveTextContent(text.import.fileNameRequired);
      expect(describedBy("import-filename")).toBe(screen.getByTestId("import-name-required"));
      expect(screen.getByTestId("import-filename")).toHaveAttribute("aria-invalid", "false");
      expect(screen.getByTestId("import-save")).toBeDisabled();

      type("import-filename", "PGM001.cbl");

      expect(screen.queryByTestId("import-name-required")).toBeNull();
      expect(screen.getByTestId("import-save")).toBeEnabled();
    });
  });

  describe("the columns", () => {
    it("shows both column inputs for text that no panel is recognised in", () => {
      renderDialog();

      type("import-paste", "       IDENTIFICATION DIVISION.");

      expect(screen.getByTestId("import-column-from")).toBeInTheDocument();
      expect(screen.getByTestId("import-column-to")).toBeInTheDocument();
    });

    it("leaves both column inputs out while a screen panel is recognised", () => {
      renderDialog();

      type("import-paste", EDIT_SCREEN);

      expect(screen.getByTestId("import-read")).toBeInTheDocument();
      expect(screen.queryByTestId("import-column-from")).toBeNull();
      expect(screen.queryByTestId("import-column-to")).toBeNull();
    });

    it("shows them again when the columns are asked for on a recognised panel", () => {
      renderDialog();

      type("import-paste", EDIT_SCREEN);
      type("import-kind", "manual");

      expect(screen.getByTestId("import-column-from")).toBeInTheDocument();
      expect(screen.getByTestId("import-column-to")).toBeInTheDocument();
    });
  });

  describe("saving", () => {
    function fill(): void {
      type("import-paste", "       IDENTIFICATION DIVISION.");
      type("import-filename", "A.cbl");
    }

    it("marks the asset stale and reports the file once it was written", async () => {
      const importSource = install([{ status: "written", relPath: "A.cbl", lineCount: 1 }]);
      renderDialog();
      fill();

      fireEvent.click(screen.getByTestId("import-save"));

      expect(await screen.findByTestId("import-saved")).toHaveTextContent(text.import.saved("A.cbl", 1));
      expect(importSource).toHaveBeenCalledWith({
        inputDir: "C:/assets",
        destDir: "",
        fileName: "A.cbl",
        lines: ["       IDENTIFICATION DIVISION."],
        overwrite: false,
      });
      expect(screen.getByTestId("store")).toHaveTextContent(JSON.stringify([["A.cbl"], 1]));
    });

    it("marks nothing stale while the file already exists and overwriting was not allowed", async () => {
      install([{ status: "exists", relPath: "A.cbl", lineCount: 0 }]);
      renderDialog();
      fill();

      fireEvent.click(screen.getByTestId("import-save"));

      expect(await screen.findByTestId("import-confirm")).toHaveTextContent(text.import.exists("A.cbl"));
      expect(screen.getByTestId("store")).toHaveTextContent(JSON.stringify([[], 0]));
    });

    it("writes over the file once the confirmation was answered, and marks the asset stale then", async () => {
      const importSource = install([
        { status: "exists", relPath: "A.cbl", lineCount: 0 },
        { status: "written", relPath: "A.cbl", lineCount: 1 },
      ]);
      renderDialog();
      fill();
      fireEvent.click(screen.getByTestId("import-save"));

      fireEvent.click(await screen.findByTestId("import-overwrite"));

      await waitFor(() => expect(screen.getByTestId("store")).toHaveTextContent(JSON.stringify([["A.cbl"], 1])));
      expect(importSource).toHaveBeenCalledTimes(2);
      expect(importSource.mock.calls[1][0]).toMatchObject({ overwrite: true });
    });

    it.each([
      ["folder", "import-destdir", "cobol"],
      ["name", "import-filename", "B.cbl"],
    ])("takes the confirmation away when the %s is edited after it was raised", async (_field, testId, value) => {
      const importSource = install([{ status: "exists", relPath: "A.cbl", lineCount: 0 }]);
      renderDialog();
      fill();
      fireEvent.click(screen.getByTestId("import-save"));
      await screen.findByTestId("import-confirm");

      type(testId, value);

      expect(screen.queryByTestId("import-confirm")).toBeNull();
      expect(screen.queryByTestId("import-overwrite")).toBeNull();
      expect(screen.getByTestId("import-save")).toBeEnabled();
      expect(importSource).toHaveBeenCalledTimes(1);
    });
  });

  describe("a rejected import", () => {
    function fill(): void {
      type("import-paste", "       IDENTIFICATION DIVISION.");
      type("import-filename", "A.cbl");
    }

    it("shows its message in the strip", async () => {
      installRejecting("「A.cbl」はリンクです。");
      renderDialog();
      fill();

      fireEvent.click(screen.getByTestId("import-save"));

      expect(await screen.findByRole("alert")).toHaveTextContent("「A.cbl」はリンクです。");
      expect(screen.getByTestId("import-save")).toBeEnabled();
      expect(screen.getByTestId("store")).toHaveTextContent(JSON.stringify([[], 0]));
    });

    it.each([
      ["folder", "import-destdir", "cobol"],
      ["name", "import-filename", "B.cbl"],
      ["pasted text", "import-paste", "       PROGRAM-ID. B."],
      ["screen kind", "import-kind", "manual"],
      ["first column", "import-column-from", "2"],
      ["last column", "import-column-to", "72"],
    ])("takes the strip away when the %s is edited", async (_field, testId, value) => {
      installRejecting("「A.cbl」はリンクです。");
      renderDialog();
      fill();
      fireEvent.click(screen.getByTestId("import-save"));
      await screen.findByRole("alert");

      type(testId, value);

      expect(screen.queryByRole("alert")).toBeNull();
    });
  });

  describe("the column fields", () => {
    it("can be emptied, which turns 保存 off and asks for a number", () => {
      renderDialog();
      type("import-paste", "       IDENTIFICATION DIVISION.");
      type("import-filename", "A.cbl");
      expect(screen.getByTestId("import-save")).toBeEnabled();

      type("import-column-to", "");

      expect((screen.getByTestId("import-column-to") as HTMLInputElement).value).toBe("");
      expect(screen.getByTestId("import-save")).toBeDisabled();
      expect(screen.getByText(text.import.columnsRequired(1, 200))).toBeInTheDocument();

      type("import-column-from", "");

      expect((screen.getByTestId("import-column-from") as HTMLInputElement).value).toBe("");
      expect(screen.getByTestId("import-save")).toBeDisabled();
    });

    it.each([
      ["first column", "import-column-from", "0"],
      ["last column", "import-column-to", "201"],
      ["first column", "import-column-from", "1.5"],
    ])("asks for a number of the range and turns 保存 off when the %s holds %s", (_field, testId, value) => {
      renderDialog();
      type("import-paste", "       IDENTIFICATION DIVISION.");
      type("import-filename", "A.cbl");

      type(testId, value);

      expect(screen.getByText(text.import.columnsRequired(1, 200))).toBeInTheDocument();
      expect(screen.getByTestId("import-save")).toBeDisabled();
    });

    it("says the last column has to reach the first and turns 保存 off when the first is greater", () => {
      renderDialog();
      type("import-paste", "       IDENTIFICATION DIVISION.");
      type("import-filename", "A.cbl");

      type("import-column-from", "30");
      type("import-column-to", "10");

      expect(screen.getByText(text.import.columnsInvalid)).toBeInTheDocument();
      expect(screen.getByTestId("import-save")).toBeDisabled();
    });

    it("turns 保存 on again once the field holds a number", () => {
      renderDialog();
      type("import-paste", "       IDENTIFICATION DIVISION.");
      type("import-filename", "A.cbl");
      type("import-column-to", "");

      type("import-column-to", "72");

      expect(screen.getByTestId("import-save")).toBeEnabled();
      expect(screen.queryByText(text.import.columnsRequired(1, 200))).toBeNull();
    });
  });

  describe("a screen kind named with nothing pasted", () => {
    it.each(["ispf-edit", "ispf-browse", "seu"])("shows no note of what was read for %s", (kind) => {
      renderDialog();

      type("import-kind", kind);

      expect(screen.queryByTestId("import-read")).toBeNull();
      expect(screen.queryByTestId("import-read-plain")).toBeNull();
    });
  });

  describe("the overwrite confirmation", () => {
    function fill(): void {
      type("import-paste", "       IDENTIFICATION DIVISION.");
      type("import-destdir", "cobol");
      type("import-filename", "A.cbl");
    }

    it("names the file as the folder holds it, and goes when the destination is edited", async () => {
      // The folder holds the file under another spelling than the one typed.
      install([{ status: "exists", relPath: "cobol/a.cbl", lineCount: 0 }]);
      renderDialog();
      fill();

      fireEvent.click(screen.getByTestId("import-save"));

      expect(await screen.findByTestId("import-confirm")).toHaveTextContent(text.import.exists("cobol/a.cbl"));
      expect(screen.getByTestId("import-overwrite")).toBeInTheDocument();

      type("import-destdir", "copy");

      expect(screen.queryByTestId("import-confirm")).toBeNull();
      expect(screen.queryByTestId("import-overwrite")).toBeNull();
    });
  });
});
