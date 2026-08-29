import { describe, expect, it } from "vitest";
import { readGraph } from "./graph";
import { openDatabaseWith } from "./fixtures";

/**
 * The checked-in scanned fixture predates PARAGRAPH_EDGE and CALL_EDGE.seq, so the ordering rules
 * are pinned against a database built here with exactly the rows they concern.
 */
const GRAPH_DB = `
  CREATE TABLE NODE (id INTEGER PRIMARY KEY, type TEXT, label TEXT);
  CREATE TABLE CALL_EDGE (
    id INTEGER PRIMARY KEY, from_node INTEGER, to_node INTEGER,
    kind TEXT, resolution TEXT, seq INTEGER, line INTEGER);
  CREATE TABLE PROGRAM (id INTEGER PRIMARY KEY, source_id INTEGER);
  CREATE TABLE PARAGRAPH (
    id INTEGER PRIMARY KEY, program_id INTEGER, name TEXT, start_line INTEGER, end_line INTEGER);
  CREATE TABLE PARAGRAPH_EDGE (
    id INTEGER PRIMARY KEY, program_source_id INTEGER, from_paragraph INTEGER,
    to_paragraph INTEGER, to_name TEXT, kind TEXT, line INTEGER, seq INTEGER);

  INSERT INTO NODE VALUES (2, 'PROGRAM', 'SYK001'), (1000000000001, 'JOB', 'SYKD010');
  INSERT INTO CALL_EDGE VALUES
    (1, 1000000000001, 2, 'REFERENCE', NULL, 0, NULL),
    (2, 1000000000001, 2, 'EXECUTION', 'CONSTANT', 2, 30),
    (3, 1000000000001, 2, 'EXECUTION', 'CONSTANT', 1, 20);
  INSERT INTO PROGRAM VALUES (100, 2);
  INSERT INTO PARAGRAPH VALUES
    (22, 100, 'READ-ORDER', 20, 25), (21, 100, 'MAIN-PROC', 10, 19);
  INSERT INTO PARAGRAPH_EDGE VALUES
    (1, 2, 21, 22, 'READ-ORDER', 'PERFORM', 12, 1),
    (2, 2, 21, NULL, 'MISSING-PARA', 'GOTO', 13, 2);
`;

describe("readGraph", () => {
  it("orders edges by execution order and pushes the unordered ones last", async () => {
    const db = await openDatabaseWith(GRAPH_DB);
    try {
      expect(readGraph(db).edges.map((edge) => edge.seq)).toEqual([1, 2, 0]);
    } finally {
      db.close();
    }
  });

  it("keeps a null resolution and a null line as null rather than defaulting them", async () => {
    const db = await openDatabaseWith(GRAPH_DB);
    try {
      const unordered = readGraph(db).edges[2];
      expect(unordered.resolution).toBeNull();
      expect(unordered.line).toBeNull();
    } finally {
      db.close();
    }
  });

  it("returns both the asset nodes and the graph-layer nodes", async () => {
    const db = await openDatabaseWith(GRAPH_DB);
    try {
      expect(readGraph(db).nodes.map((node) => node.type)).toEqual(["PROGRAM", "JOB"]);
    } finally {
      db.close();
    }
  });

  it("attaches paragraphs to their asset through PROGRAM, in source order", async () => {
    const db = await openDatabaseWith(GRAPH_DB);
    try {
      const paragraphs = readGraph(db).paragraphs;
      expect(paragraphs.map((paragraph) => paragraph.name)).toEqual(["MAIN-PROC", "READ-ORDER"]);
      expect(paragraphs[0].programSourceId).toBe(2);
    } finally {
      db.close();
    }
  });

  it("keeps the target name of an unresolved paragraph edge", async () => {
    const db = await openDatabaseWith(GRAPH_DB);
    try {
      const unresolved = readGraph(db).paragraphEdges[1];
      expect(unresolved.to).toBeNull();
      expect(unresolved.toName).toBe("MISSING-PARA");
      expect(unresolved.kind).toBe("GOTO");
    } finally {
      db.close();
    }
  });
});
