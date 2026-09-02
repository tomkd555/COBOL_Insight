import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, render, screen } from "@testing-library/react";
import { fireEvent } from "@testing-library/dom";
import { Toast } from "./Toast";

const MESSAGE = { id: 1, text: "「a.cbl」を保存しました。", failed: false };

describe("Toast", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it("marks a dismissed toast as leaving and only then drops it", () => {
    const onDismiss = vi.fn();
    render(<Toast messages={[MESSAGE]} onDismiss={onDismiss} />);

    fireEvent.click(screen.getByRole("button"));
    // The element is still on screen, carrying the class the leaving transition is written against.
    expect(screen.getByTestId("toast-1").className).toContain("ci-toast--leaving");
    expect(onDismiss).not.toHaveBeenCalled();

    act(() => void vi.advanceTimersByTime(200));
    expect(onDismiss).toHaveBeenCalledWith(1);
  });

  it("drops a toast on its own once it has stood long enough", () => {
    const onDismiss = vi.fn();
    render(<Toast messages={[MESSAGE]} onDismiss={onDismiss} />);

    act(() => void vi.advanceTimersByTime(6000));
    expect(onDismiss).not.toHaveBeenCalled();
    act(() => void vi.advanceTimersByTime(200));
    expect(onDismiss).toHaveBeenCalledWith(1);
  });
});
