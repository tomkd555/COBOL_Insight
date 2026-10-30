import { link, lstat, mkdir, mkdtemp, readdir, readFile, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { importFileSystem, userDataFileSystem } from "./nodeFs";

let dir = "";

beforeEach(async () => {
  dir = await mkdtemp(join(tmpdir(), "ci-nodefs-"));
});

afterEach(async () => {
  await rm(dir, { recursive: true, force: true });
});

/** The files the engine's scan would pass over: the ones the import writes under a name of its own. */
async function importLeftovers(): Promise<string[]> {
  return (await readdir(dir)).filter((name) => name.endsWith(".import"));
}

describe("importFileSystem", () => {
  describe("writeText", () => {
    it("writes a new file", async () => {
      await expect(importFileSystem.writeText(join(dir, "A.cbl"), "new text", false)).resolves.toBe(true);

      expect(await readFile(join(dir, "A.cbl"), "utf-8")).toBe("new text");
      expect(await importLeftovers()).toEqual([]);
    });

    it("leaves the other name of a hard link as it was, and no .import file behind", async () => {
      const first = join(dir, "A.cbl");
      const second = join(dir, "B.cbl");
      await writeFile(first, "original", "utf-8");
      await link(first, second);

      await importFileSystem.writeText(second, "imported", true);

      expect(await readFile(second, "utf-8")).toBe("imported");
      expect(await readFile(first, "utf-8")).toBe("original");
      expect(await importLeftovers()).toEqual([]);
    });

    it("leaves no .import file behind when the name cannot be taken", async () => {
      // A folder holds the name, so the new file cannot replace it.
      await mkdir(join(dir, "A.cbl"));

      await expect(importFileSystem.writeText(join(dir, "A.cbl"), "imported", true)).rejects.toThrow();

      expect(await importLeftovers()).toEqual([]);
    });
  });

  describe("writeText under a long name", () => {
    // The new file beside it has a name of one length, so a long name leaves room for it.
    const LONG = `${"A".repeat(251)}.cbl`;

    it("replaces with a name of 255 characters, the longest a name can be", async () => {
      expect(LONG).toHaveLength(255);

      await expect(importFileSystem.writeText(join(dir, LONG), "first", true)).resolves.toBe(true);
      await expect(importFileSystem.writeText(join(dir, LONG), "second", true)).resolves.toBe(true);

      expect(await readFile(join(dir, LONG), "utf-8")).toBe("second");
      expect(await readdir(dir)).toEqual([LONG]);
    });
  });

  describe("writeText where the name must be free", () => {
    it("writes nothing and answers false where a file has the name", async () => {
      await writeFile(join(dir, "A.cbl"), "original", "utf-8");

      await expect(importFileSystem.writeText(join(dir, "A.cbl"), "imported", false)).resolves.toBe(false);

      expect(await readFile(join(dir, "A.cbl"), "utf-8")).toBe("original");
    });
  });

  describe("entry", () => {
    it("answers file for a file and folder for a folder", async () => {
      await writeFile(join(dir, "A.cbl"), "text", "utf-8");
      await mkdir(join(dir, "sub"));

      await expect(importFileSystem.entry(join(dir, "A.cbl"))).resolves.toBe("file");
      await expect(importFileSystem.entry(join(dir, "sub"))).resolves.toBe("folder");
    });

    it.runIf(process.platform === "win32")("answers link for a junction", async () => {
      await mkdir(join(dir, "target"));
      await symlink(join(dir, "target"), join(dir, "A.cbl"), "junction");

      await expect(importFileSystem.entry(join(dir, "A.cbl"))).resolves.toBe("link");
    });

    it.runIf(process.platform === "win32")("answers link for a junction whose target is gone", async () => {
      await mkdir(join(dir, "target"));
      await symlink(join(dir, "target"), join(dir, "A.cbl"), "junction");
      await rm(join(dir, "target"), { recursive: true });

      await expect(importFileSystem.entry(join(dir, "A.cbl"))).resolves.toBe("link");
    });

    it("answers null for a path that is not there", async () => {
      await expect(importFileSystem.entry(join(dir, "missing.cbl"))).resolves.toBeNull();
      await expect(importFileSystem.entry(join(dir, "missing", "A.cbl"))).resolves.toBeNull();
    });
  });
});

describe("userDataFileSystem", () => {
  describe("writeText", () => {
    it("replaces the content and leaves no temporary file behind", async () => {
      const path = join(dir, "rules.json");
      await writeFile(path, "old", "utf-8");

      await userDataFileSystem.writeText(path, "new");

      expect(await readFile(path, "utf-8")).toBe("new");
      expect(await readdir(dir)).toEqual(["rules.json"]);
    });

    it("creates a file that is new", async () => {
      const path = join(dir, "settings.json");

      await userDataFileSystem.writeText(path, "{}");

      expect(await readFile(path, "utf-8")).toBe("{}");
      expect(await readdir(dir)).toEqual(["settings.json"]);
    });

    it("leaves no temporary file behind when the name cannot be taken", async () => {
      await mkdir(join(dir, "rules.json"));

      await expect(userDataFileSystem.writeText(join(dir, "rules.json"), "new")).rejects.toThrow();

      expect(await readdir(dir)).toEqual(["rules.json"]);
    });

    it("replaces the file through a new one, so the other name of a hard link keeps the old content", async () => {
      const path = join(dir, "rules.json");
      const other = join(dir, "linked.json");
      await writeFile(path, "old", "utf-8");
      await link(path, other);

      await userDataFileSystem.writeText(path, "new");

      expect(await readFile(path, "utf-8")).toBe("new");
      expect(await readFile(other, "utf-8")).toBe("old");
    });
  });

  describe("writeText through a symbolic link", () => {
    it("replaces the file the link leads to and leaves the link a link", async (context) => {
      const real = join(dir, "real-rules.json");
      const named = join(dir, "rules.json");
      await writeFile(real, "old", "utf-8");
      try {
        await symlink(real, named, "file");
      } catch (error) {
        // Windows lets an account create a file link only in developer mode or with the privilege.
        if ((error as { code?: unknown }).code === "EPERM") context.skip();
        throw error;
      }

      await userDataFileSystem.writeText(named, "new");

      expect((await lstat(named)).isSymbolicLink()).toBe(true);
      expect(await readFile(real, "utf-8")).toBe("new");
      expect(await readFile(named, "utf-8")).toBe("new");
    });
  });

  describe("readBytes", () => {
    it("reads the bytes as they are, a byte order mark and Shift_JIS alike", async () => {
      const bytes = new Uint8Array([0xef, 0xbb, 0xbf, 0x93, 0xfa, 0x96, 0x7b]);
      await writeFile(join(dir, "rules.json"), bytes);

      expect(new Uint8Array(await userDataFileSystem.readBytes(join(dir, "rules.json")))).toEqual(bytes);
    });
  });

  describe("copyNew", () => {
    it("copies the file byte for byte to a free name", async () => {
      const bytes = new Uint8Array([0xff, 0xfe, 0x7b, 0x00]);
      await writeFile(join(dir, "rules.json"), bytes);

      await expect(userDataFileSystem.copyNew(join(dir, "rules.json"), join(dir, "copy"))).resolves.toBe(true);

      expect(new Uint8Array(await readFile(join(dir, "copy")))).toEqual(bytes);
    });

    it("answers false and leaves a file that has the name as it was", async () => {
      await writeFile(join(dir, "rules.json"), "new", "utf-8");
      await writeFile(join(dir, "copy"), "earlier copy", "utf-8");

      await expect(userDataFileSystem.copyNew(join(dir, "rules.json"), join(dir, "copy"))).resolves.toBe(false);

      expect(await readFile(join(dir, "copy"), "utf-8")).toBe("earlier copy");
    });
  });
});
