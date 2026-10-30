import { describe, expect, it } from "vitest";
import { errorMessage } from "./api";

describe("errorMessage", () => {
  it("removes the prefix Electron puts in front of an error thrown in main", () => {
    const error = new Error("Error invoking remote method 'x': Error: disk full");
    expect(errorMessage(error)).toBe("disk full");
  });

  it("leaves a plain message as it is", () => {
    expect(errorMessage(new Error("disk full"))).toBe("disk full");
    expect(errorMessage("disk full")).toBe("disk full");
  });

  it("removes the prefix only where it opens the message", () => {
    const error = new Error("write failed: Error invoking remote method 'x': Error: disk full");
    expect(errorMessage(error)).toBe("write failed: Error invoking remote method 'x': Error: disk full");
  });
});
