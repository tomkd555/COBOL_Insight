import { describe, expect, it } from "vitest";
import { readInventory } from "./inventory";
import { openDatabaseWith, openFixtureDatabase } from "./fixtures";

describe("readInventory against the scanned fixture", () => {
  it("lists every source, ordered by relative path", async () => {
    const db = await openFixtureDatabase();
    try {
      const items = readInventory(db);
      expect(items.length).toBeGreaterThan(0);
      expect(items.map((item) => item.path)).toEqual([...items.map((item) => item.path)].sort());
      expect(items[0].path).toBe("bms/SYKMAP1.bms");
    } finally {
      db.close();
    }
  });

  it("takes the name from the last path segment and the kind from NODE", async () => {
    const db = await openFixtureDatabase();
    try {
      const program = readInventory(db).find((item) => item.path === "cobol/SYK001.cbl");
      expect(program).toMatchObject({ name: "SYK001.cbl", type: "PROGRAM", codepage: "UTF-8" });
      expect(program?.byteSize).toBeGreaterThan(0);
    } finally {
      db.close();
    }
  });

  it("reports the kinds the classifier inferred from the bytes", async () => {
    const db = await openFixtureDatabase();
    try {
      const kinds = new Set(readInventory(db).map((item) => item.type));
      expect(kinds).toEqual(new Set(["PROGRAM", "COPYBOOK", "JCL", "BMS"]));
    } finally {
      db.close();
    }
  });
});

const MINIMAL = `
  CREATE TABLE SOURCE (id INTEGER PRIMARY KEY, path TEXT, codepage TEXT, byte_size INTEGER);
  CREATE TABLE NODE (id INTEGER PRIMARY KEY, type TEXT, label TEXT);
  CREATE TABLE FINDING (id INTEGER PRIMARY KEY, source_id INTEGER);
  INSERT INTO SOURCE VALUES (1, 'a.cbl', 'Shift_JIS', 10), (2, 'b.dat', NULL, 20);
  INSERT INTO NODE VALUES (1, 'PROGRAM', 'A');
  INSERT INTO FINDING VALUES (1, 1), (2, 1), (1000000000000, 1);
`;

describe("readInventory edge cases", () => {
  it("counts only scan findings, leaving the graph layer out", async () => {
    const db = await openDatabaseWith(MINIMAL);
    try {
      expect(readInventory(db)[0].findingCount).toBe(2);
    } finally {
      db.close();
    }
  });

  it("reports UNKNOWN for a source with no node and null for an undecodable codepage", async () => {
    const db = await openDatabaseWith(MINIMAL);
    try {
      expect(readInventory(db)[1]).toMatchObject({ type: "UNKNOWN", codepage: null });
    } finally {
      db.close();
    }
  });
});
