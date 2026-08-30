import { describe, expect, it } from "vitest";
import {
  SEQUENCE_TIMEOUT_MS,
  commandAfterPrefix,
  isModifierKey,
  isSequencePrefix,
  type ChordEvent,
} from "./keybindings";

/** A key press with no field under it. */
function press(
  key: string,
  modifiers: { ctrlKey?: boolean; shiftKey?: boolean } = {},
): ChordEvent {
  return {
    key,
    ctrlKey: modifiers.ctrlKey === true,
    metaKey: false,
    altKey: false,
    shiftKey: modifiers.shiftKey === true,
    target: null,
  };
}

describe("isModifierKey", () => {
  it("recognises the keys that only qualify another key", () => {
    for (const key of ["Control", "Shift", "Alt", "Meta"]) {
      expect(isModifierKey(press(key, { ctrlKey: true }))).toBe(true);
    }
  });

  it("does not recognise a key that carries a command", () => {
    expect(isModifierKey(press("s"))).toBe(false);
    expect(isModifierKey(press("k", { ctrlKey: true }))).toBe(false);
  });
});

describe("the Ctrl+K sequence", () => {
  it("arms on Ctrl+K and completes on S, with or without Ctrl", () => {
    expect(isSequencePrefix(press("k", { ctrlKey: true }))).toBe(true);
    expect(commandAfterPrefix(press("s"))).toBe("editor.saveAll");
    expect(commandAfterPrefix(press("s", { ctrlKey: true }))).toBe("editor.saveAll");
  });

  it("completes on no other key", () => {
    expect(commandAfterPrefix(press("b", { ctrlKey: true }))).toBeNull();
  });

  it("waits no longer than a gesture lasts", () => {
    expect(SEQUENCE_TIMEOUT_MS).toBeGreaterThan(0);
    expect(SEQUENCE_TIMEOUT_MS).toBeLessThanOrEqual(3000);
  });
});
