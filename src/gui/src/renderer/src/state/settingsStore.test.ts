import { describe, expect, it } from "vitest";
import { emptyAppSettings } from "../../../shared/settings";
import {
  initialSettingsState,
  settingsReducer,
  toAppSettings,
  type SettingsState,
} from "./settingsStore";

describe("RESTORE", () => {
  it("takes the stored values it recognises", () => {
    const state = settingsReducer(initialSettingsState, {
      type: "RESTORE",
      settings: {
        ...emptyAppSettings(),
        severityThreshold: "medium",
        defaultEncoding: "IBM930",
        copybookPaths: ["C:/cpy"],
      },
    });
    expect(state).toMatchObject({
      severityThreshold: "medium",
      defaultEncoding: "IBM930",
      copybookPaths: ["C:/cpy"],
      restored: true,
    });
  });

  it("keeps the current threshold when the stored one is not a severity the interface knows", () => {
    const state = settingsReducer(initialSettingsState, {
      type: "RESTORE",
      settings: { ...emptyAppSettings(), severityThreshold: "critical" },
    });
    expect(state.severityThreshold).toBe(initialSettingsState.severityThreshold);
  });

  it("drops a codepage the interface no longer offers, rather than keeping an unselectable value", () => {
    const state = settingsReducer(initialSettingsState, {
      type: "RESTORE",
      settings: { ...emptyAppSettings(), defaultEncoding: "EUC-JP" },
    });
    expect(state.defaultEncoding).toBe("");
  });

  it("marks the settings restored, which is what unblocks saving", () => {
    expect(initialSettingsState.restored).toBe(false);
    expect(
      settingsReducer(initialSettingsState, { type: "RESTORE", settings: emptyAppSettings() })
        .restored,
    ).toBe(true);
  });
});

describe("toAppSettings", () => {
  it("produces only the fields the settings screen owns, leaving paneSizes out", () => {
    const state: SettingsState = {
      severityThreshold: "high",
      defaultEncoding: "Shift_JIS",
      copybookPaths: ["C:/cpy"],
      fixOutDir: "C:/out",
      theme: "light",
      restored: true,
    };
    expect(toAppSettings(state)).toEqual({
      severityThreshold: "high",
      defaultEncoding: "Shift_JIS",
      copybookPaths: ["C:/cpy"],
      fixOutDir: "C:/out",
      theme: "light",
    });
  });
});

describe("the fix output directory", () => {
  it("is taken from the stored settings and written back", () => {
    const restored = settingsReducer(initialSettingsState, {
      type: "RESTORE",
      settings: { ...emptyAppSettings(), fixOutDir: "C:/out" },
    });
    expect(restored.fixOutDir).toBe("C:/out");
    const changed = settingsReducer(restored, { type: "SET_FIX_OUT_DIR", dir: "C:/fixed" });
    expect(toAppSettings(changed).fixOutDir).toBe("C:/fixed");
  });
});
