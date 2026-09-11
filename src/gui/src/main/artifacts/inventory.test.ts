import { describe, expect, it } from "vitest";
import { readInventory } from "./inventory";
import { openDatabaseWith } from "./fixtures";

/**
 * One project file holds every asset folder ever scanned, so each database here carries two roots
 * and every expectation names the rows of one of them. The checked-in scanned fixture predates
 * SOURCE.root and cannot stand in for a project file the current engine writes.
 */
const TWO_ROOTS = `
  CREATE TABLE SOURCE (
    id INTEGER PRIMARY KEY, root TEXT, path TEXT, codepage TEXT, byte_size INTEGER);
  CREATE TABLE NODE (id INTEGER PRIMARY KEY, type TEXT, label TEXT);
  CREATE TABLE FINDING (id INTEGER PRIMARY KEY, source_id INTEGER);
  INSERT INTO SOURCE VALUES
    (1, 'C:/assets', 'cobol/a.cbl', 'Shift_JIS', 10),
    (2, 'C:/assets', 'b.dat', NULL, 20),
    (3, 'C:/other', 'cobol/c.cbl', 'UTF-8', 30);
  INSERT INTO NODE VALUES (1, 'PROGRAM', 'A'), (3, 'PROGRAM', 'C');
  INSERT INTO FINDING VALUES (1, 1), (2, 1), (3, 3), (1000000000000, 1);
`;

describe("readInventory", () => {
  it("lists the assets of the given root alone, ordered by relative path", async () => {
    const db = await openDatabaseWith(TWO_ROOTS);
    try {
      expect(readInventory(db, "C:/assets").map((item) => item.path)).toEqual([
        "b.dat",
        "cobol/a.cbl",
      ]);
      expect(readInventory(db, "C:/other").map((item) => item.path)).toEqual(["cobol/c.cbl"]);
    } finally {
      db.close();
    }
  });

  it("matches the root without regard to case, as Windows paths compare", async () => {
    const db = await openDatabaseWith(TWO_ROOTS);
    try {
      expect(readInventory(db, "c:/ASSETS")).toHaveLength(2);
    } finally {
      db.close();
    }
  });

  it("takes the name from the last path segment and the kind from NODE", async () => {
    const db = await openDatabaseWith(TWO_ROOTS);
    try {
      const program = readInventory(db, "C:/assets").find((item) => item.path === "cobol/a.cbl");
      expect(program).toMatchObject({ name: "a.cbl", type: "PROGRAM", codepage: "Shift_JIS" });
    } finally {
      db.close();
    }
  });

  it("counts only scan findings, leaving the graph layer out", async () => {
    const db = await openDatabaseWith(TWO_ROOTS);
    try {
      const program = readInventory(db, "C:/assets").find((item) => item.path === "cobol/a.cbl");
      expect(program?.findingCount).toBe(2);
    } finally {
      db.close();
    }
  });

  it("reports UNKNOWN for a source with no node and null for an undecodable codepage", async () => {
    const db = await openDatabaseWith(TWO_ROOTS);
    try {
      expect(readInventory(db, "C:/assets")[0]).toMatchObject({ type: "UNKNOWN", codepage: null });
    } finally {
      db.close();
    }
  });
});
