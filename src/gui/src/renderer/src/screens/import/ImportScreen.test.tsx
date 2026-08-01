import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { ImportScreen } from "./ImportScreen";
import { AppStateProvider } from "../../state/AppStateContext";
import { initialState, type AppState } from "../../state/appState";
import type { CobolInsightApi, ImportSourceRequest } from "../../../../shared/engine-api";

const INPUT_DIR = "C:\\資産\\SYK";

/** 端末の画面から複写した本文の模擬。左に6桁の行番号欄と1桁の空白が付く。 */
const PASTED = ["000100 IDENTIFICATION DIVISION.", "000200 PROGRAM-ID. SYK001.", "      "].join(
  "\r\n",
);

let importSource: ReturnType<typeof vi.fn>;
let selectInputFolder: ReturnType<typeof vi.fn>;

beforeEach(() => {
  importSource = vi
    .fn()
    .mockResolvedValue({ status: "written", relPath: "cobol/SYK001.cbl", lineCount: 2 });
  selectInputFolder = vi.fn().mockResolvedValue(INPUT_DIR);
  window.cobolInsight = { importSource, selectInputFolder } as unknown as CobolInsightApi;
});

afterEach(() => {
  delete (window as { cobolInsight?: CobolInsightApi }).cobolInsight;
});

/** 資産フォルダを選び終えた状態。 */
function importState(overrides: Partial<AppState> = {}): AppState {
  return {
    ...initialState,
    screen: "import",
    project: { inputDir: INPUT_DIR, dbPath: null, copybookPaths: [] },
    ...overrides,
  };
}

function renderImport(seed: AppState = importState()): void {
  render(
    <AppStateProvider initialState={seed}>
      <ImportScreen />
    </AppStateProvider>,
  );
}

/** 直近の取込要求。 */
function lastRequest(): ImportSourceRequest {
  return importSource.mock.calls[importSource.mock.calls.length - 1][0] as ImportSourceRequest;
}

describe("ImportScreen(端末取込)", () => {
  it("資産フォルダが無いときは選択へ誘導し、選ぶと取込のフォームを出す", async () => {
    renderImport(importState({ project: { inputDir: null, dbPath: null, copybookPaths: [] } }));
    expect(screen.getByRole("region", { name: "保存先の資産フォルダが選ばれていません" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "資産フォルダを選ぶ" }));
    await waitFor(() => {
      expect(screen.getByLabelText("端末から複写した本文")).toBeInTheDocument();
    });
    expect(selectInputFolder).toHaveBeenCalledTimes(1);
  });

  it("貼り付けた本文を指定した桁で切り出し、プレビューへ出す", () => {
    renderImport();
    fireEvent.change(screen.getByLabelText("端末から複写した本文"), { target: { value: PASTED } });
    fireEvent.change(screen.getByLabelText("開始桁"), { target: { value: "8" } });

    const preview = screen.getByRole("region", { name: "取込内容のプレビュー" });
    // 行番号欄(1〜7桁)を除いた本文だけが残り、末尾の空行は落ちる。
    expect(preview).toHaveTextContent("IDENTIFICATION DIVISION.");
    expect(preview).toHaveTextContent("PROGRAM-ID. SYK001.");
    expect(preview).not.toHaveTextContent("000100");
    expect(preview).toHaveTextContent("取込内容のプレビュー（2 行）");
  });

  it("種別とファイル名から保存先の相対パスを示す", () => {
    renderImport();
    fireEvent.change(screen.getByLabelText("ファイル名"), { target: { value: "SYKCPY1" } });
    fireEvent.click(screen.getByRole("button", { name: "コピー句" }));
    expect(screen.getByText("保存先: copy/SYKCPY1.cpy")).toBeInTheDocument();
  });

  it("本文とファイル名がそろうまで保存させない", () => {
    renderImport();
    const save = screen.getByRole("button", { name: "資産フォルダへ保存する" });
    expect(save).toBeDisabled();

    fireEvent.change(screen.getByLabelText("端末から複写した本文"), { target: { value: PASTED } });
    expect(save).toBeDisabled();

    fireEvent.change(screen.getByLabelText("ファイル名"), { target: { value: "SYK001" } });
    expect(save).toBeEnabled();
  });

  it("終了桁が開始桁より小さいときは保存させない", () => {
    renderImport();
    fireEvent.change(screen.getByLabelText("端末から複写した本文"), { target: { value: PASTED } });
    fireEvent.change(screen.getByLabelText("ファイル名"), { target: { value: "SYK001" } });
    fireEvent.change(screen.getByLabelText("終了桁"), { target: { value: "1" } });
    fireEvent.change(screen.getByLabelText("開始桁"), { target: { value: "8" } });

    expect(screen.getByText("終了桁は開始桁以上にする。")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "資産フォルダへ保存する" })).toBeDisabled();
  });

  it("切り出した行と種別を main へ渡して保存し、保存後は入力を空へ戻す", async () => {
    renderImport();
    fireEvent.change(screen.getByLabelText("端末から複写した本文"), { target: { value: PASTED } });
    fireEvent.change(screen.getByLabelText("開始桁"), { target: { value: "8" } });
    fireEvent.change(screen.getByLabelText("ファイル名"), { target: { value: "SYK001" } });
    fireEvent.click(screen.getByRole("button", { name: "資産フォルダへ保存する" }));

    await waitFor(() => expect(importSource).toHaveBeenCalledTimes(1));
    expect(lastRequest()).toEqual({
      inputDir: INPUT_DIR,
      kind: "cobol",
      fileName: "SYK001",
      lines: ["IDENTIFICATION DIVISION.", "PROGRAM-ID. SYK001."],
      overwrite: false,
    });
    await waitFor(() => {
      expect(screen.getByLabelText("端末から複写した本文")).toHaveValue("");
    });
    expect(screen.getByLabelText("ファイル名")).toHaveValue("");
  });

  it("同名のファイルがあれば上書きの確認を挟み、確認後に上書きを許して呼び直す", async () => {
    importSource.mockResolvedValueOnce({
      status: "exists",
      relPath: "cobol/SYK001.cbl",
      lineCount: 0,
    });
    renderImport();
    fireEvent.change(screen.getByLabelText("端末から複写した本文"), { target: { value: PASTED } });
    fireEvent.change(screen.getByLabelText("ファイル名"), { target: { value: "SYK001" } });
    fireEvent.click(screen.getByRole("button", { name: "資産フォルダへ保存する" }));

    await waitFor(() => {
      expect(screen.getByRole("alert")).toHaveTextContent("cobol/SYK001.cbl は既にある");
    });
    expect(lastRequest().overwrite).toBe(false);

    fireEvent.click(screen.getByRole("button", { name: "上書きする" }));
    await waitFor(() => expect(importSource).toHaveBeenCalledTimes(2));
    expect(lastRequest().overwrite).toBe(true);
  });

  it("確認の表示中に保存先を変えると、確認は取り下げられる", async () => {
    importSource.mockResolvedValueOnce({
      status: "exists",
      relPath: "cobol/SYK001.cbl",
      lineCount: 0,
    });
    renderImport();
    fireEvent.change(screen.getByLabelText("端末から複写した本文"), { target: { value: PASTED } });
    fireEvent.change(screen.getByLabelText("ファイル名"), { target: { value: "SYK001" } });
    fireEvent.click(screen.getByRole("button", { name: "資産フォルダへ保存する" }));

    await waitFor(() => {
      expect(screen.getByRole("button", { name: "上書きする" })).toBeInTheDocument();
    });
    // 別のファイル名へ変えた後も上書きを許せば、確認を経ていない資産を壊す。
    fireEvent.change(screen.getByLabelText("ファイル名"), { target: { value: "SYK002" } });
    expect(screen.queryByRole("button", { name: "上書きする" })).not.toBeInTheDocument();
  });

  it("上書きの確認を「やめる」で閉じる", async () => {
    importSource.mockResolvedValueOnce({
      status: "exists",
      relPath: "cobol/SYK001.cbl",
      lineCount: 0,
    });
    renderImport();
    fireEvent.change(screen.getByLabelText("端末から複写した本文"), { target: { value: PASTED } });
    fireEvent.change(screen.getByLabelText("ファイル名"), { target: { value: "SYK001" } });
    fireEvent.click(screen.getByRole("button", { name: "資産フォルダへ保存する" }));

    await waitFor(() => {
      expect(screen.getByRole("button", { name: "やめる" })).toBeInTheDocument();
    });
    fireEvent.click(screen.getByRole("button", { name: "やめる" }));
    expect(screen.queryByRole("button", { name: "上書きする" })).not.toBeInTheDocument();
    expect(importSource).toHaveBeenCalledTimes(1);
  });

  it("解析の実行中は取込のフォームを出さない", () => {
    renderImport(importState({ mode: "running", runStage: 1 }));
    expect(screen.getByRole("status")).toHaveTextContent("資産を解析しています…");
    expect(screen.queryByLabelText("端末から複写した本文")).not.toBeInTheDocument();
  });

  it("使えないファイル名では保存させない", () => {
    renderImport();
    fireEvent.change(screen.getByLabelText("端末から複写した本文"), { target: { value: PASTED } });
    fireEvent.change(screen.getByLabelText("ファイル名"), { target: { value: "sub/SYK001" } });
    expect(screen.getByRole("button", { name: "資産フォルダへ保存する" })).toBeDisabled();
  });

  it("桁は 1〜200 に収め、数値として読めない入力では現在値を保つ", () => {
    renderImport();
    fireEvent.change(screen.getByLabelText("開始桁"), { target: { value: "0" } });
    expect(screen.getByLabelText("開始桁")).toHaveValue(1);
    fireEvent.change(screen.getByLabelText("終了桁"), { target: { value: "999" } });
    expect(screen.getByLabelText("終了桁")).toHaveValue(200);
    fireEvent.change(screen.getByLabelText("終了桁"), { target: { value: "" } });
    expect(screen.getByLabelText("終了桁")).toHaveValue(200);
  });

  it("資産フォルダの選択に失敗したら理由を示す", async () => {
    selectInputFolder.mockRejectedValueOnce(new Error("ダイアログを開けなかった"));
    renderImport(importState({ project: { inputDir: null, dbPath: null, copybookPaths: [] } }));
    fireEvent.click(screen.getByRole("button", { name: "資産フォルダを選ぶ" }));
    await waitFor(() => {
      expect(screen.getByRole("alert")).toHaveTextContent("ダイアログを開けなかった");
    });
  });

  it("保存に失敗したら理由を示し、入力を残す", async () => {
    importSource.mockRejectedValueOnce(new Error("書き込みを拒まれた"));
    renderImport();
    fireEvent.change(screen.getByLabelText("端末から複写した本文"), { target: { value: PASTED } });
    fireEvent.change(screen.getByLabelText("ファイル名"), { target: { value: "SYK001" } });
    fireEvent.click(screen.getByRole("button", { name: "資産フォルダへ保存する" }));

    await waitFor(() => {
      expect(screen.getByRole("alert")).toHaveTextContent("書き込みを拒まれた");
    });
    expect(screen.getByLabelText("ファイル名")).toHaveValue("SYK001");
  });
});
