import { describe, expect, it } from "vitest";
import { readProgramFlow } from "./programFlow";
import { openDatabaseWith } from "./fixtures";

/**
 * A project file as schema version 6 writes it, holding two programs of one asset folder and a
 * program of the same name in another folder.
 */
const FLOW_DB = `
  CREATE TABLE SOURCE (id INTEGER PRIMARY KEY, root TEXT, path TEXT);
  -- INT, so the id is a column of its own and the table keeps the order of the inserts: a lookup
  -- with no ORDER BY would then name the program inserted first, the one with the higher id.
  CREATE TABLE PROGRAM (id INT PRIMARY KEY, source_id INTEGER, program_id_name TEXT);
  CREATE TABLE PARAGRAPH (
    id INTEGER PRIMARY KEY, program_id INTEGER, name TEXT, kind TEXT, section_name TEXT,
    start_line INTEGER, end_line INTEGER);
  CREATE TABLE PARAGRAPH_EDGE (
    id INTEGER PRIMARY KEY, program_source_id INTEGER, from_paragraph INTEGER,
    to_paragraph INTEGER, to_name TEXT, thru_paragraph INTEGER, thru_name TEXT, kind TEXT,
    line INTEGER, seq INTEGER);

  -- The other folder's SYKSUB has the lowest program id and another path, so a lookup that ignored
  -- the folder would name sub/SYKSUB.cbl. Two programs of one folder declare DUPPGM: the lower id
  -- (10) is the one at the later path, in the table after the other.
  INSERT INTO SOURCE VALUES
    (1, 'C:/other', 'sub/SYKSUB.cbl'),
    (2, 'C:/assets', 'cobol/SYK001.cbl'),
    (3, 'C:/assets', 'cobol/SYKSUB.cbl'),
    (6, 'C:/assets', 'cobol/CALLER.cbl'),
    (7, 'C:/other', 'sub/ONLYOTHER.cbl'),
    (8, 'C:/assets', 'cobol/AA-DUP.cbl'),
    (9, 'C:/assets', 'cobol/ZZ-DUP.cbl');
  INSERT INTO PROGRAM VALUES
    (1, 1, 'SYKSUB'), (2, 2, 'SYK001'), (3, 3, 'SYKSUB'), (6, 6, 'CALLER'), (7, 7, 'ONLYOTHER'),
    (11, 8, 'DUPPGM'), (10, 9, 'DUPPGM');
  INSERT INTO PARAGRAPH VALUES
    (601, 6, 'CALL-PROC', 'PARAGRAPH', NULL, 1, 9),
    (201, 2, 'MAIN-SECT', 'SECTION', NULL, 10, 30),
    (202, 2, 'MAIN-PROC', 'PARAGRAPH', 'MAIN-SECT', 11, 20),
    (203, 2, 'MAIN-EXIT', 'PARAGRAPH', 'MAIN-SECT', 21, 30),
    (301, 3, 'SUB-PROC', 'PARAGRAPH', NULL, 5, 9);
  INSERT INTO PARAGRAPH_EDGE VALUES
    (1, 2, 202, 202, 'MAIN-PROC', 203, 'MAIN-EXIT', 'PERFORM', 12, 2),
    (2, 2, 202, NULL, 'SYKSUB', NULL, NULL, 'CALL', 11, 1),
    (3, 2, 202, NULL, 'W-PGM', NULL, NULL, 'XCTL', 14, 3),
    (4, 2, 201, 202, 'MAIN-PROC', NULL, NULL, 'FALLTHROUGH', NULL, 1),
    (5, 3, 301, NULL, 'SUB-EXIT', NULL, 'NO-SUCH', 'PERFORM', 6, 1),
    (6, 6, 601, NULL, 'ONLYOTHER', NULL, NULL, 'CALL', 2, 1),
    (7, 6, 601, NULL, 'DUPPGM', NULL, NULL, 'CALL', 3, 2);
`;

const ROOT = "C:/assets";

describe("readProgramFlow", () => {
  it("returns the paragraphs of the one program, in definition order, with kind and section", async () => {
    const db = await openDatabaseWith(FLOW_DB);
    try {
      expect(readProgramFlow(db, ROOT, "cobol/SYK001.cbl").paragraphs).toEqual([
        { id: 201, name: "MAIN-SECT", kind: "SECTION", sectionName: null, startLine: 10, endLine: 30 },
        { id: 202, name: "MAIN-PROC", kind: "PARAGRAPH", sectionName: "MAIN-SECT", startLine: 11, endLine: 20 },
        { id: 203, name: "MAIN-EXIT", kind: "PARAGRAPH", sectionName: "MAIN-SECT", startLine: 21, endLine: 30 },
      ]);
    } finally {
      db.close();
    }
  });

  it("orders the flows by paragraph and execution order, and carries the THRU end", async () => {
    const db = await openDatabaseWith(FLOW_DB);
    try {
      const edges = readProgramFlow(db, ROOT, "cobol/SYK001.cbl").edges;
      expect(edges.map((edge) => `${edge.from}:${edge.seq}:${edge.kind}`)).toEqual([
        "201:1:FALLTHROUGH",
        "202:1:CALL",
        "202:2:PERFORM",
        "202:3:XCTL",
      ]);
      expect(edges[2]).toMatchObject({ to: 202, thru: 203, thruName: "MAIN-EXIT", line: 12 });
    } finally {
      db.close();
    }
  });

  it("names the asset of a called program of the same folder, and none where no asset declares it", async () => {
    const db = await openDatabaseWith(FLOW_DB);
    try {
      const edges = readProgramFlow(db, ROOT, "cobol/SYK001.cbl").edges;
      expect(edges[1]).toMatchObject({ toName: "SYKSUB", to: null, toPath: "cobol/SYKSUB.cbl" });
      expect(edges[3]).toMatchObject({ toName: "W-PGM", toPath: null });
      expect(edges[2]?.toPath).toBeNull();
    } finally {
      db.close();
    }
  });

  it("resolves a CALL to the program of the folder under view", async () => {
    const db = await openDatabaseWith(FLOW_DB);
    try {
      const call = readProgramFlow(db, ROOT, "cobol/SYK001.cbl").edges.find((edge) => edge.kind === "CALL");
      expect(call?.toPath).toBe("cobol/SYKSUB.cbl");
    } finally {
      db.close();
    }
  });

  it("names no asset for a CALL whose PROGRAM-ID is declared only under another folder", async () => {
    const db = await openDatabaseWith(FLOW_DB);
    try {
      const edges = readProgramFlow(db, ROOT, "cobol/CALLER.cbl").edges;
      expect(edges[0]).toMatchObject({ toName: "ONLYOTHER", kind: "CALL", toPath: null });
    } finally {
      db.close();
    }
  });

  it("names the asset of the lower program id where two programs of one folder share a PROGRAM-ID", async () => {
    const db = await openDatabaseWith(FLOW_DB);
    try {
      const edges = readProgramFlow(db, ROOT, "cobol/CALLER.cbl").edges;
      expect(edges[1]).toMatchObject({ toName: "DUPPGM", toPath: "cobol/ZZ-DUP.cbl" });
    } finally {
      db.close();
    }
  });

  it("keeps an unknown THRU name with no paragraph behind it", async () => {
    const db = await openDatabaseWith(FLOW_DB);
    try {
      expect(readProgramFlow(db, ROOT, "cobol/SYKSUB.cbl").edges).toEqual([
        {
          from: 301,
          to: null,
          toName: "SUB-EXIT",
          kind: "PERFORM",
          thru: null,
          thruName: "NO-SUCH",
          line: 6,
          seq: 1,
          toPath: null,
        },
      ]);
    } finally {
      db.close();
    }
  });

  it("reads the asset of the folder under view, whatever the case of its path", async () => {
    const db = await openDatabaseWith(FLOW_DB);
    try {
      expect(readProgramFlow(db, "c:/ASSETS", "COBOL/syksub.cbl").paragraphs.map((each) => each.id)).toEqual([301]);
      expect(readProgramFlow(db, "C:/other", "sub/SYKSUB.cbl").paragraphs).toEqual([]);
      expect(readProgramFlow(db, ROOT, "cobol/MISSING.cbl")).toEqual({ paragraphs: [], edges: [] });
    } finally {
      db.close();
    }
  });
});
