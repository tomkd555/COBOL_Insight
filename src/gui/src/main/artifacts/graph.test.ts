import { describe, expect, it } from "vitest";
import { readGraph } from "./graph";
import { openDatabaseWith } from "./fixtures";

/**
 * The checked-in scanned fixture predates PARAGRAPH_EDGE, CALL_EDGE.seq and SOURCE.root, so the
 * ordering rules are pinned against a database built here with exactly the rows they concern.
 *
 * Two asset folders share the project file, as they do once a second folder has been opened. Every
 * query is read for one root, and the rows of the other must stay out of the answer.
 */
const GRAPH_DB = `
  CREATE TABLE SOURCE (
    id INTEGER PRIMARY KEY, root TEXT, path TEXT, codepage TEXT, byte_size INTEGER);
  CREATE TABLE NODE (id INTEGER PRIMARY KEY, type TEXT, label TEXT);
  CREATE TABLE CALL_EDGE (
    id INTEGER PRIMARY KEY, from_node INTEGER, to_node INTEGER,
    kind TEXT, resolution TEXT, seq INTEGER, line INTEGER, access TEXT);
  CREATE TABLE PROGRAM (id INTEGER PRIMARY KEY, source_id INTEGER);
  CREATE TABLE PARAGRAPH (
    id INTEGER PRIMARY KEY, program_id INTEGER, name TEXT, start_line INTEGER, end_line INTEGER);
  CREATE TABLE PARAGRAPH_EDGE (
    id INTEGER PRIMARY KEY, program_source_id INTEGER, from_paragraph INTEGER,
    to_paragraph INTEGER, to_name TEXT, kind TEXT, line INTEGER, seq INTEGER);

  INSERT INTO SOURCE VALUES
    (2, 'C:/assets', 'cobol/SYK001.cbl', 'Shift_JIS', 10),
    (5, 'C:/other', 'cobol/OTHER.cbl', 'Shift_JIS', 10);
  INSERT INTO NODE VALUES
    (2, 'PROGRAM', 'SYK001'), (5, 'PROGRAM', 'OTHER'), (1000000000001, 'JOB', 'SYKD010');
  INSERT INTO CALL_EDGE VALUES
    (1, 1000000000001, 2, 'REFERENCE', NULL, 0, NULL, 'READ'),
    (2, 1000000000001, 2, 'EXECUTION', 'CONSTANT', 2, 30, NULL),
    (3, 1000000000001, 2, 'EXECUTION', 'CONSTANT', 1, 20, NULL),
    (4, 1000000000001, 5, 'EXECUTION', 'CONSTANT', 1, 40, NULL);
  INSERT INTO PROGRAM VALUES (100, 2), (101, 5);
  INSERT INTO PARAGRAPH VALUES
    (22, 100, 'READ-ORDER', 20, 25), (21, 100, 'MAIN-PROC', 10, 19),
    (31, 101, 'OTHER-PROC', 10, 19);
  INSERT INTO PARAGRAPH_EDGE VALUES
    (1, 2, 21, 22, 'READ-ORDER', 'PERFORM', 12, 1),
    (2, 2, 21, NULL, 'MISSING-PARA', 'GOTO', 13, 2),
    (3, 5, 31, NULL, 'OTHER-PARA', 'GOTO', 14, 1);
`;

/** The folder under view in every test but the one that reads the other root. */
const ROOT = "C:/assets";

describe("readGraph", () => {
  it("orders edges by execution order and pushes the unordered ones last", async () => {
    const db = await openDatabaseWith(GRAPH_DB);
    try {
      expect(readGraph(db, ROOT).edges.map((edge) => edge.seq)).toEqual([1, 2, 0]);
    } finally {
      db.close();
    }
  });

  it("keeps a null resolution and a null line as null rather than defaulting them", async () => {
    const db = await openDatabaseWith(GRAPH_DB);
    try {
      const unordered = readGraph(db, ROOT).edges[2];
      expect(unordered.resolution).toBeNull();
      expect(unordered.line).toBeNull();
    } finally {
      db.close();
    }
  });

  it("passes the access through, and leaves it null where the engine recorded none", async () => {
    const db = await openDatabaseWith(GRAPH_DB);
    try {
      const edges = readGraph(db, ROOT).edges;
      expect(edges[2].access).toBe("READ");
      expect(edges[0].access).toBeNull();
    } finally {
      db.close();
    }
  });

  it("returns both the asset nodes and the graph-layer nodes", async () => {
    const db = await openDatabaseWith(GRAPH_DB);
    try {
      expect(readGraph(db, ROOT).nodes.map((node) => node.type)).toEqual(["PROGRAM", "JOB"]);
    } finally {
      db.close();
    }
  });

  it("attaches paragraphs to their asset through PROGRAM, in source order", async () => {
    const db = await openDatabaseWith(GRAPH_DB);
    try {
      const paragraphs = readGraph(db, ROOT).paragraphs;
      expect(paragraphs.map((paragraph) => paragraph.name)).toEqual(["MAIN-PROC", "READ-ORDER"]);
      expect(paragraphs[0].programSourceId).toBe(2);
    } finally {
      db.close();
    }
  });

  it("keeps the target name of an unresolved paragraph edge", async () => {
    const db = await openDatabaseWith(GRAPH_DB);
    try {
      const unresolved = readGraph(db, ROOT).paragraphEdges[1];
      expect(unresolved.to).toBeNull();
      expect(unresolved.toName).toBe("MISSING-PARA");
      expect(unresolved.kind).toBe("GOTO");
    } finally {
      db.close();
    }
  });

  it("leaves the other folder's assets, edges and paragraphs out", async () => {
    // Same project file, two asset folders: reading one must not put the other on screen.
    const db = await openDatabaseWith(GRAPH_DB);
    try {
      const mine = readGraph(db, ROOT);
      expect(mine.nodes.map((node) => node.label)).toEqual(["SYK001", "SYKD010"]);
      expect(mine.edges.map((edge) => edge.to)).toEqual([2, 2, 2]);
      expect(mine.paragraphs.map((paragraph) => paragraph.name)).toEqual([
        "MAIN-PROC",
        "READ-ORDER",
      ]);
      expect(mine.paragraphEdges).toHaveLength(2);

      const other = readGraph(db, "C:/other");
      expect(other.nodes.map((node) => node.label)).toEqual(["OTHER", "SYKD010"]);
      expect(other.paragraphs.map((paragraph) => paragraph.name)).toEqual(["OTHER-PROC"]);
      expect(other.paragraphEdges.map((edge) => edge.toName)).toEqual(["OTHER-PARA"]);
    } finally {
      db.close();
    }
  });
});
