import { describe, expect, it, vi } from "vitest";
import type { DecodeResult, DecodeSourceRequest, SourceStamp } from "../../shared/ipc";
import { createCachedDecode, type DecodeCacheDeps } from "./decodeCache";

function decoded(text: string, error = ""): DecodeResult {
  return {
    text,
    codepage: "Shift_JIS",
    detected: true,
    lines: [],
    stamp: { mtimeMs: 1, byteSize: 1 },
    error,
  };
}

/** A fake decode runner whose files carry a stamp the test can move. */
function deps(
  overrides: Partial<DecodeCacheDeps> = {},
): DecodeCacheDeps & { decode: ReturnType<typeof vi.fn> } {
  const base = {
    locate: async (request: DecodeSourceRequest) => ({
      realPath: `C:/assets/${request.path}`,
      stamp: { mtimeMs: 1000, byteSize: 40 } satisfies SourceStamp,
    }),
    decode: vi.fn(async (request: DecodeSourceRequest) => decoded(`text of ${request.path}`)),
  };
  return { ...base, ...overrides } as DecodeCacheDeps & { decode: ReturnType<typeof vi.fn> };
}

const request: DecodeSourceRequest = { baseDir: "C:/assets", path: "cobol/A.cbl" };

describe("createCachedDecode", () => {
  it("runs the engine once for the same unchanged file", async () => {
    const dependencies = deps();
    const cached = createCachedDecode(dependencies);
    const first = await cached(request);
    const second = await cached(request);
    expect(dependencies.decode).toHaveBeenCalledTimes(1);
    expect(second).toBe(first);
  });

  it("runs it again once the file's stamp has moved", async () => {
    let mtimeMs = 1000;
    const dependencies = deps({
      locate: async () => ({ realPath: "C:/assets/cobol/A.cbl", stamp: { mtimeMs, byteSize: 40 } }),
    });
    const cached = createCachedDecode(dependencies);
    await cached(request);
    mtimeMs = 2000;
    await cached(request);
    expect(dependencies.decode).toHaveBeenCalledTimes(2);
  });

  it("keeps the codepages of one file apart", async () => {
    const dependencies = deps();
    const cached = createCachedDecode(dependencies);
    await cached(request);
    await cached({ ...request, codepage: "IBM930" });
    expect(dependencies.decode).toHaveBeenCalledTimes(2);
  });

  it("keeps the project files apart, since the recorded codepage comes from one", async () => {
    const dependencies = deps();
    const cached = createCachedDecode(dependencies);
    await cached({ ...request, db: "C:/a.db" });
    await cached({ ...request, db: "C:/b.db" });
    expect(dependencies.decode).toHaveBeenCalledTimes(2);
  });

  it("does not cache a failed decode", async () => {
    const decode = vi.fn(async () => decoded("", "no engine"));
    const cached = createCachedDecode(deps({ decode }));
    await cached(request);
    await cached(request);
    expect(decode).toHaveBeenCalledTimes(2);
  });

  it("decodes without caching when the file cannot be located", async () => {
    const dependencies = deps({ locate: async () => null });
    const cached = createCachedDecode(dependencies);
    await cached(request);
    await cached(request);
    expect(dependencies.decode).toHaveBeenCalledTimes(2);
  });

  it("evicts the least recently used entry once it is full", async () => {
    const dependencies = deps({
      locate: async (each) => ({
        realPath: `C:/assets/${each.path}`,
        stamp: { mtimeMs: 1000, byteSize: 40 },
      }),
    });
    const cached = createCachedDecode(dependencies, 2);
    await cached({ baseDir: "C:/assets", path: "a.cbl" });
    await cached({ baseDir: "C:/assets", path: "b.cbl" });
    // Touching a.cbl makes b.cbl the oldest.
    await cached({ baseDir: "C:/assets", path: "a.cbl" });
    await cached({ baseDir: "C:/assets", path: "c.cbl" });
    expect(dependencies.decode).toHaveBeenCalledTimes(3);

    await cached({ baseDir: "C:/assets", path: "a.cbl" });
    expect(dependencies.decode).toHaveBeenCalledTimes(3);
    await cached({ baseDir: "C:/assets", path: "b.cbl" });
    expect(dependencies.decode).toHaveBeenCalledTimes(4);
  });

  it("runs the engine once for two requests that arrive together", async () => {
    let started = 0;
    const dependencies = deps({
      decode: vi.fn(async () => {
        started += 1;
        await Promise.resolve();
        return decoded("text");
      }),
    });
    const cached = createCachedDecode(dependencies);
    const [first, second] = await Promise.all([cached(request), cached(request)]);
    expect(started).toBe(1);
    expect(second).toBe(first);
  });

  it("forgets a decode that rejected, so the next request tries again", async () => {
    const decode = vi.fn(async () => {
      throw new Error("engine gone");
    });
    const cached = createCachedDecode(deps({ decode }));
    await expect(cached(request)).rejects.toThrow("engine gone");
    await expect(cached(request)).rejects.toThrow("engine gone");
    expect(decode).toHaveBeenCalledTimes(2);
  });

  it("decodes again after the cache is cleared", async () => {
    const dependencies = deps();
    const cached = createCachedDecode(dependencies);
    await cached(request);
    cached.clear();
    await cached(request);
    expect(dependencies.decode).toHaveBeenCalledTimes(2);
  });

  it("falls back to the engine when locating the file throws", async () => {
    const dependencies = deps({
      locate: async () => {
        throw new Error("realpath failed");
      },
    });
    const cached = createCachedDecode(dependencies);
    await expect(cached(request)).resolves.toMatchObject({ error: "" });
    expect(dependencies.decode).toHaveBeenCalledTimes(1);
  });
});
