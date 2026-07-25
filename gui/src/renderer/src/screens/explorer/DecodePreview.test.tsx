import { render, screen } from "@testing-library/react";
import { describe, it, expect } from "vitest";
import { DecodePreview } from "./DecodePreview";

describe("DecodePreview(デコードプレビュー)", () => {
  it("先頭 maxLines 行までを表示する", () => {
    const lines = ["行1", "行2", "行3", "行4", "行5", "行6"];
    render(<DecodePreview preview={{ status: "ready", lines, codepage: "UTF-8" }} />);
    expect(screen.getByText("行5")).toBeInTheDocument();
    expect(screen.queryByText("行6")).toBeNull();
  });

  it("復号できた本文では警告を出さず、コードページを添える", () => {
    render(<DecodePreview preview={{ status: "ready", lines: ["A", "B"], codepage: "Shift_JIS" }} />);
    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.getByText(/Shift_JIS/)).toBeInTheDocument();
  });

  it("復号非対応は engine の charset 名でなく利用者向けの表記を添えて示す", () => {
    // main は復号非対応のとき、要求した engine の charset 名をそのまま返す。
    render(<DecodePreview preview={{ status: "unsupported", codepage: "x-IBM930" }} />);
    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("EBCDIC CP930");
    expect(alert).not.toHaveTextContent("x-IBM930");
    expect(alert).toHaveTextContent("表示に対応していません");
  });

  it("写像を持たないコードページ(不明)はそのまま添える", () => {
    render(<DecodePreview preview={{ status: "unsupported", codepage: "不明" }} />);
    expect(screen.getByRole("alert")).toHaveTextContent("不明");
  });

  it("読取失敗は理由を示す", () => {
    render(<DecodePreview preview={{ status: "error", message: "ファイルが見つかりません" }} />);
    expect(screen.getByRole("alert")).toHaveTextContent("ファイルが見つかりません");
  });

  it("読込中は読込中と示す", () => {
    render(<DecodePreview preview={{ status: "loading" }} />);
    expect(screen.getByText("読み込み中…")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("行が無ければプレースホルダを出す", () => {
    render(<DecodePreview preview={{ status: "ready", lines: [], codepage: "UTF-8" }} />);
    expect(screen.getByText("プレビューする内容がありません")).toBeInTheDocument();
  });

  it("未選択(idle)でもプレースホルダを出す", () => {
    render(<DecodePreview preview={{ status: "idle" }} />);
    expect(screen.getByText("プレビューする内容がありません")).toBeInTheDocument();
  });
});
