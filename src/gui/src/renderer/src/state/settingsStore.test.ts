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
        lastInputDir: "C:/assets",
        paneSizes: { sideWidth: 320 },
      },
    });
    expect(state).toMatchObject({
      severityThreshold: "medium",
      defaultEncoding: "IBM930",
      copybookPaths: ["C:/cpy"],
      lastInputDir: "C:/assets",
      paneSizes: { sideWidth: 320 },
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

describe("updates", () => {
  it("stores a pane size beside the ones already there", () => {
    const state = settingsReducer(
      { ...initialSettingsState, paneSizes: { sideWidth: 300 } },
      { type: "SET_PANE_SIZE", key: "panelHeight", size: 240 },
    );
    expect(state.paneSizes).toEqual({ sideWidth: 300, panelHeight: 240 });
  });
});

describe("toAppSettings", () => {
  it("produces the shape the settings file stores", () => {
    const state: SettingsState = {
      severityThreshold: "high",
      defaultEncoding: "Shift_JIS",
      copybookPaths: ["C:/cpy"],
      lastInputDir: "C:/assets",
      paneSizes: { sideWidth: 320 },
      restored: true,
    };
    expect(toAppSettings(state)).toEqual({
      severityThreshold: "high",
      defaultEncoding: "Shift_JIS",
      copybookPaths: ["C:/cpy"],
      lastInputDir: "C:/assets",
      paneSizes: { sideWidth: 320 },
    });
  });
});
