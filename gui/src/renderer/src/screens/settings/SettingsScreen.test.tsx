import { render, screen, fireEvent, waitFor, within } from "@testing-library/react";
import type { ReactElement } from "react";
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { SettingsScreen } from "./SettingsScreen";
import { AppStateProvider, useAppDispatch, useAppState } from "../../state/AppStateContext";
import { initialState, type AppState } from "../../state/appState";
import { disabledRuleIds } from "./settingsModel";
import { ENCODING_OPTIONS as ASSET_ENCODING_OPTIONS } from "../explorer/assetView";
import type { CobolInsightApi } from "../../../../shared/engine-api";

const PATHS = ["C:\\資産\\copybook", "C:\\資産\\共通\\copylib"];

let checkDirectoryExists: ReturnType<typeof vi.fn>;

beforeEach(() => {
  // 既定ではすべて実在するものとして扱い、実在確認と無関係な既存テストへ影響しないようにする。
  checkDirectoryExists = vi.fn().mockResolvedValue(true);
  window.cobolInsight = { checkDirectoryExists } as unknown as CobolInsightApi;
});

afterEach(() => {
  delete (window as { cobolInsight?: CobolInsightApi }).cobolInsight;
});

function seedState(overrides: Partial<AppState> = {}): AppState {
  return {
    ...initialState,
    screen: "settings",
    project: { inputDir: "C:\\資産\\SYK", dbPath: null, copybookPaths: PATHS },
    ...overrides,
  };
}

/** コピー句探索パスと設定値の共有状態を観測できる器。 */
function Harness(): ReactElement {
  const state = useAppState();
  return (
    <>
      <p data-testid="copybook-paths">{state.project.copybookPaths.join(" | ")}</p>
      <p data-testid="toast-msg">{state.toastMsg ?? ""}</p>
      <p data-testid="settings-state">
        {[
          disabledRuleIds(state.rulesDisabled).join(","),
          state.severityThreshold,
          state.defaultEncoding,
          state.ruleSearch,
        ].join(" | ")}
      </p>
      <SettingsScreen />
    </>
  );
}

/** 画面を捨てて描き直す器。タブ移動で設定が失われないことを観測する。 */
function TabHarness(): ReactElement {
  const state = useAppState();
  const dispatch = useAppDispatch();
  return (
    <>
      <button type="button" onClick={() => dispatch({ type: "NAV", screen: "explorer" })}>
        資産タブ
      </button>
      <button type="button" onClick={() => dispatch({ type: "NAV", screen: "settings" })}>
        設定タブ
      </button>
      {state.screen === "settings" ? <SettingsScreen /> : <p>資産エクスプローラー</p>}
    </>
  );
}

function renderSettings(seed?: AppState): void {
  render(
    <AppStateProvider initialState={seed}>
      <Harness />
    </AppStateProvider>,
  );
}

describe("SettingsScreen のルール一覧", () => {
  it("37 件のルールをカテゴリ別に並べる", () => {
    renderSettings(seedState());
    expect(screen.getAllByRole("switch")).toHaveLength(37);
    expect(screen.getByText("有効 37 / 37")).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "セキュリティ" })).toBeInTheDocument();
  });

  it("修正案を生成するルールにバッジを付ける", () => {
    renderSettings(seedState());
    expect(screen.getAllByText("修正案")).toHaveLength(4);
  });

  it("ルールを無効にすると有効数が減り、トグルの状態が変わる", () => {
    renderSettings(seedState());
    const toggle = screen.getByRole("switch", { name: "R017 ファイル状態(FILE STATUS)未検査" });
    expect(toggle).toHaveAttribute("aria-checked", "true");
    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute("aria-checked", "false");
    expect(screen.getByText("有効 36 / 37")).toBeInTheDocument();
  });

  it("無効にしたルール名へ、色に依存しない無効の印を添える", () => {
    renderSettings(seedState({ rulesDisabled: { R009: true } }));
    const toggle = screen.getByRole("switch", { name: "R009 GO TO文による構造化フローからの逸脱" });
    const row = toggle.parentElement as HTMLElement;
    expect(within(row).getByText(/GO TO文による構造化フローからの逸脱/)).toHaveClass("ci-rules__name--off");
    expect(within(row).getByText("（無効）")).toBeInTheDocument();
  });

  it("有効なルール名には無効の印を添えない", () => {
    renderSettings(seedState());
    expect(screen.queryByText("（無効）")).not.toBeInTheDocument();
  });

  it("押し直すと有効へ戻す", () => {
    renderSettings(seedState());
    const toggle = screen.getByRole("switch", { name: "R004 ON SIZE ERROR句の欠如" });
    fireEvent.click(toggle);
    fireEvent.click(toggle);
    expect(screen.getByText("有効 37 / 37")).toBeInTheDocument();
  });

  it("AppState の無効化状態を初期値として読む", () => {
    renderSettings(seedState({ rulesDisabled: { R009: true } }));
    expect(screen.getByText("有効 36 / 37")).toBeInTheDocument();
    expect(screen.getByRole("switch", { name: "R009 GO TO文による構造化フローからの逸脱" })).toHaveAttribute(
      "aria-checked",
      "false",
    );
  });

  it("検索でルールを絞り込み、一致件数を示す", () => {
    renderSettings(seedState());
    fireEvent.change(screen.getByLabelText("ルールを検索（ID / 名称 / カテゴリ）"), {
      target: { value: "セキュリティ" },
    });
    expect(screen.getAllByRole("switch")).toHaveLength(2);
    expect(screen.getByText("検索に一致: 2 件")).toBeInTheDocument();
  });

  it("一致するルールが無いときは探し方を示す", () => {
    renderSettings(seedState());
    fireEvent.change(screen.getByLabelText("ルールを検索（ID / 名称 / カテゴリ）"), {
      target: { value: "該当しない語" },
    });
    expect(screen.getByText(/検索に一致するルールがない/)).toBeInTheDocument();
    expect(screen.queryAllByRole("switch")).toHaveLength(0);
  });

  it("すべて無効・すべて有効を一括で切り替える", () => {
    renderSettings(seedState());
    fireEvent.click(screen.getByRole("button", { name: "すべて無効" }));
    expect(screen.getByText("有効 0 / 37")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "すべて有効" }));
    expect(screen.getByText("有効 37 / 37")).toBeInTheDocument();
  });

  it("絞り込み中の一括操作は表示中のルールだけを対象にする", () => {
    renderSettings(seedState());
    fireEvent.change(screen.getByLabelText("ルールを検索（ID / 名称 / カテゴリ）"), {
      target: { value: "セキュリティ" },
    });
    fireEvent.click(screen.getByRole("button", { name: "表示中をすべて無効" }));
    expect(screen.getByText("有効 35 / 37")).toBeInTheDocument();
  });
});

describe("SettingsScreen のコピー句探索パス", () => {
  it("並びを共有状態から読み、順番を付けて示す", () => {
    renderSettings(seedState());
    expect(screen.getByTestId("copybook-paths")).toHaveTextContent(PATHS.join(" | "));
    expect(screen.getByText("C:\\資産\\copybook")).toBeInTheDocument();
  });

  it("上へ動かすと共有状態の並びが変わる", () => {
    renderSettings(seedState());
    fireEvent.click(screen.getByRole("button", { name: "C:\\資産\\共通\\copylib を1つ上へ" }));
    expect(screen.getByTestId("copybook-paths")).toHaveTextContent(
      "C:\\資産\\共通\\copylib | C:\\資産\\copybook",
    );
  });

  it("端の移動ボタンは押せない", () => {
    renderSettings(seedState());
    expect(screen.getByRole("button", { name: "C:\\資産\\copybook を1つ上へ" })).toBeDisabled();
    expect(
      screen.getByRole("button", { name: "C:\\資産\\共通\\copylib を1つ下へ" }),
    ).toBeDisabled();
  });

  it("削除すると共有状態から外れる", () => {
    renderSettings(seedState());
    fireEvent.click(screen.getByRole("button", { name: "C:\\資産\\copybook を削除" }));
    expect(screen.getByTestId("copybook-paths")).toHaveTextContent("C:\\資産\\共通\\copylib");
  });

  it("追加すると末尾へ加わり、入力欄が空になる", () => {
    renderSettings(seedState());
    const input = screen.getByLabelText("追加するコピー句探索パス");
    fireEvent.change(input, { target: { value: "D:\\copylib2" } });
    fireEvent.click(screen.getByRole("button", { name: "追加" }));
    expect(screen.getByTestId("copybook-paths")).toHaveTextContent(
      `${PATHS.join(" | ")} | D:\\copylib2`,
    );
    expect(input).toHaveValue("");
  });

  it("重複するパスは加えない", () => {
    renderSettings(seedState());
    fireEvent.change(screen.getByLabelText("追加するコピー句探索パス"), {
      target: { value: "C:\\資産\\copybook" },
    });
    fireEvent.click(screen.getByRole("button", { name: "追加" }));
    expect(screen.getByTestId("copybook-paths")).toHaveTextContent(PATHS.join(" | "));
  });

  it("未設定のときは engine の既定の探索先を示す", () => {
    renderSettings(seedState({ project: { inputDir: null, dbPath: null, copybookPaths: [] } }));
    expect(screen.getByText(/copybook・copy/)).toBeInTheDocument();
  });
});

describe("SettingsScreen のコピー句探索パスの実在確認", () => {
  it("画面を開いた時点で保存済みの全パスを実在確認する", async () => {
    renderSettings(seedState());
    await waitFor(() => expect(checkDirectoryExists).toHaveBeenCalledTimes(2));
    expect(checkDirectoryExists).toHaveBeenCalledWith(PATHS[0]);
    expect(checkDirectoryExists).toHaveBeenCalledWith(PATHS[1]);
  });

  it("実在しない保存済みパスの行に警告を出す", async () => {
    checkDirectoryExists.mockImplementation((path: string) =>
      Promise.resolve(path !== PATHS[1]),
    );
    renderSettings(seedState());
    await waitFor(() => expect(screen.getByText(/見つからない/)).toBeInTheDocument());
    expect(screen.getByText("C:\\資産\\copybook")).toBeInTheDocument();
  });

  it("実在するパスだけのときは警告を出さない", async () => {
    renderSettings(seedState());
    await waitFor(() => expect(checkDirectoryExists).toHaveBeenCalledTimes(2));
    expect(screen.queryByText(/見つからない/)).toBeNull();
  });

  it("追加したパスが実在しなければ、入力欄の近くに警告を出しトーストは出さない", async () => {
    checkDirectoryExists.mockImplementation((path: string) =>
      Promise.resolve(path !== "D:\\無い\\フォルダ"),
    );
    renderSettings(seedState());
    await waitFor(() => expect(checkDirectoryExists).toHaveBeenCalledTimes(2));

    fireEvent.change(screen.getByLabelText("追加するコピー句探索パス"), {
      target: { value: "D:\\無い\\フォルダ" },
    });
    fireEvent.click(screen.getByRole("button", { name: "追加" }));

    await waitFor(() =>
      expect(screen.getByRole("alert")).toHaveTextContent("D:\\無い\\フォルダ が見つからない"),
    );
    expect(screen.getByTestId("toast-msg")).toHaveTextContent("");
    // 行そのものは残り、一覧からは消えない。
    expect(screen.getByText("D:\\無い\\フォルダ")).toBeInTheDocument();
  });

  it("追加したパスが実在すれば、入力欄近くの警告を出さない", async () => {
    renderSettings(seedState());
    await waitFor(() => expect(checkDirectoryExists).toHaveBeenCalledTimes(2));

    fireEvent.change(screen.getByLabelText("追加するコピー句探索パス"), {
      target: { value: "D:\\ある\\フォルダ" },
    });
    fireEvent.click(screen.getByRole("button", { name: "追加" }));

    await waitFor(() => expect(checkDirectoryExists).toHaveBeenCalledWith("D:\\ある\\フォルダ"));
    expect(screen.queryByRole("alert")).toBeNull();
  });
});

describe("SettingsScreen の重大度しきい値と文字コード", () => {
  it("しきい値を選ぶと対象の重大度を説明文へ並べる", () => {
    renderSettings(seedState());
    expect(screen.getByText(/「警告」以上を表示/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("radio", { name: "中" }));
    expect(screen.getByText("現在の設定: 「中」以上を表示（高・中 が対象）")).toBeInTheDocument();
  });

  it("既定の文字コードを選べる", () => {
    renderSettings(seedState());
    const select = screen.getByLabelText("既定の文字コード");
    fireEvent.change(select, { target: { value: "手動: Shift_JIS" } });
    expect(select).toHaveValue("手動: Shift_JIS");
  });

  it("選択肢は資産エクスプローラーの文字コード指定と同じ語彙である", () => {
    renderSettings(seedState());
    const options = within(screen.getByLabelText("既定の文字コード")).getAllByRole("option");
    for (const option of options) {
      expect(ASSET_ENCODING_OPTIONS).toContain(option.textContent);
    }
    expect(screen.queryByRole("option", { name: "Shift_JIS 固定" })).toBeNull();
  });
});

describe("SettingsScreen の操作が共有状態へ届く", () => {
  it("ルールの無効化を共有状態へ書き込む", () => {
    renderSettings(seedState());
    fireEvent.click(screen.getByRole("switch", { name: "R017 ファイル状態(FILE STATUS)未検査" }));
    expect(screen.getByTestId("settings-state")).toHaveTextContent("R017");
  });

  it("しきい値・既定の文字コード・検索語を共有状態へ書き込む", () => {
    renderSettings(seedState());
    fireEvent.click(screen.getByRole("radio", { name: "中" }));
    fireEvent.change(screen.getByLabelText("既定の文字コード"), {
      target: { value: "手動: EBCDIC CP930" },
    });
    fireEvent.change(screen.getByLabelText("ルールを検索（ID / 名称 / カテゴリ）"), {
      target: { value: "R017" },
    });
    expect(screen.getByTestId("settings-state")).toHaveTextContent(
      "medium | 手動: EBCDIC CP930 | R017",
    );
  });

  it("タブを移動して戻っても設定を保つ", () => {
    render(
      <AppStateProvider initialState={seedState()}>
        <TabHarness />
      </AppStateProvider>,
    );
    fireEvent.click(screen.getByRole("switch", { name: "R004 ON SIZE ERROR句の欠如" }));
    fireEvent.click(screen.getByRole("radio", { name: "高" }));
    expect(screen.getByText("有効 36 / 37")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "資産タブ" }));
    fireEvent.click(screen.getByRole("button", { name: "設定タブ" }));
    expect(screen.getByText("有効 36 / 37")).toBeInTheDocument();
    expect(screen.getByRole("switch", { name: "R004 ON SIZE ERROR句の欠如" })).toHaveAttribute(
      "aria-checked",
      "false",
    );
    expect(screen.getByRole("radio", { name: "高" })).toHaveAttribute("aria-checked", "true");
  });
});

describe("SettingsScreen の読み取り専用", () => {
  it("解析の実行中は告知を出し、操作を禁じる", () => {
    renderSettings(seedState({ mode: "running" }));
    expect(screen.getByRole("status")).toHaveTextContent("解析の実行中は設定を変更できない");
    expect(screen.getByRole("switch", { name: "R004 ON SIZE ERROR句の欠如" })).toBeDisabled();
    expect(screen.getByLabelText("既定の文字コード")).toBeDisabled();
    expect(screen.getByRole("button", { name: "追加" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "すべて無効" })).toBeDisabled();
  });

  it("解析していないときは操作できる", () => {
    renderSettings(seedState());
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    expect(screen.getByRole("switch", { name: "R004 ON SIZE ERROR句の欠如" })).toBeEnabled();
  });
});

describe("SettingsScreen の engine の実行", () => {
  it("配布時と開発時の起動対象を示す", () => {
    renderSettings(seedState());
    expect(screen.getByText(/resources\\engine\\COBOLInsight\.exe/)).toBeInTheDocument();
    expect(screen.getByText(/JAVA_HOME の java/)).toBeInTheDocument();
    expect(screen.getByText(initialState.version)).toBeInTheDocument();
  });

  it("ネットワーク接続を行わない旨を示す", () => {
    renderSettings(seedState());
    expect(screen.getByText(/ネットワーク接続は行わない/)).toBeInTheDocument();
  });

  it("利用者向けの文言に内部名(engine・コマンド引数)を出さない", () => {
    renderSettings(seedState());
    expect(screen.getByRole("heading", { level: 4, name: "解析エンジンの実行" })).toBeInTheDocument();
    expect(screen.queryByText(/--disable-rule|--copybook-path/)).toBeNull();
  });
});

describe("SettingsScreen の見出し階層", () => {
  it("画面の題目を h3 に置き、各カードを h4、ルールのカテゴリを h5 とする", () => {
    renderSettings(seedState());
    expect(screen.getByRole("heading", { level: 3 })).toHaveTextContent("設定");
    expect(screen.getByRole("heading", { level: 4, name: "既定の文字コード" })).toBeInTheDocument();
    expect(screen.getAllByRole("heading", { level: 5 }).length).toBeGreaterThan(0);
  });
});
