import { render, screen, fireEvent } from "@testing-library/react";
import { describe, it, expect, vi, afterEach } from "vitest";
import { Toast } from "./Toast";

afterEach(() => {
  vi.useRealTimers();
});

describe("Toast", () => {
  it("メッセージが null でも読み上げ領域は空のまま DOM に残す", () => {
    // 領域を本文と同時に挿入すると読み上げが落ちる支援技術があるため、器は常に置く。
    render(<Toast message={null} />);
    const region = screen.getByRole("status");
    expect(region).toHaveAttribute("aria-live", "polite");
    expect(region).toHaveTextContent("");
  });

  it("メッセージが null のときは帯を見せない", () => {
    const { container } = render(<Toast message={null} />);
    expect(container.querySelector(".ci-toast--empty")).not.toBeNull();
  });

  it("メッセージは器を作り直さず本文だけを差し替える", () => {
    const { rerender } = render(<Toast message={null} />);
    const before = screen.getByRole("status");
    rerender(<Toast message="解析が完了しました" />);
    const after = screen.getByRole("status");
    expect(after).toBe(before);
    expect(after).toHaveTextContent("解析が完了しました");
  });

  it("既定 2600ms 経過後に onDismiss を呼ぶ", () => {
    vi.useFakeTimers();
    const onDismiss = vi.fn();
    render(<Toast message="保存しました" onDismiss={onDismiss} />);
    expect(onDismiss).not.toHaveBeenCalled();
    vi.advanceTimersByTime(2599);
    expect(onDismiss).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(onDismiss).toHaveBeenCalledOnce();
  });

  it("親の再描画で onDismiss の同一性が変わっても計時をやり直さない", () => {
    // 呼び出し側はインラインのディスパッチを渡すため、描画のたび関数の同一性が変わる。
    // これで計時が張り直されると、描画が続くあいだトーストが消えなくなる。
    vi.useFakeTimers();
    const onDismiss = vi.fn();
    const { rerender } = render(<Toast message="保存しました" onDismiss={() => onDismiss()} />);
    for (let i = 0; i < 3; i += 1) {
      vi.advanceTimersByTime(1000);
      rerender(<Toast message="保存しました" onDismiss={() => onDismiss()} />);
    }
    expect(onDismiss).toHaveBeenCalledOnce();
  });

  it("durationMs で自動消滅の時間を変えられる", () => {
    vi.useFakeTimers();
    const onDismiss = vi.fn();
    render(<Toast message="x" onDismiss={onDismiss} durationMs={1000} />);
    vi.advanceTimersByTime(1000);
    expect(onDismiss).toHaveBeenCalledOnce();
  });

  it("閉じるボタンを aria-label 付きで表示し、押すと onDismiss を呼ぶ", () => {
    const onDismiss = vi.fn();
    render(<Toast message="保存しました" onDismiss={onDismiss} />);
    const closeButton = screen.getByRole("button", { name: "通知を閉じる" });
    fireEvent.click(closeButton);
    expect(onDismiss).toHaveBeenCalledOnce();
  });

  it("onDismiss を渡さない場合は閉じるボタンを描画しない", () => {
    render(<Toast message="保存しました" />);
    expect(screen.queryByRole("button", { name: "通知を閉じる" })).toBeNull();
  });
});
