/**
 * The call graph the model tests share: one job with two steps, the programs they run, a called
 * subprogram, a dataset, and a copybook row that is an asset rather than a call-graph node.
 *
 * The two step edges are deliberately listed out of execution order, so anything that claims to
 * order by seq has to do so rather than pass the recorded order through.
 */

import type { GraphData } from "../../../shared/ipc";

/** The graph layer's ids start above the asset ids, as the engine keeps them. */
export const GRAPH_ID_BASE = 1_000_000_000_000;

export const JOB_ID = GRAPH_ID_BASE + 1;
export const STEP010_ID = GRAPH_ID_BASE + 2;
export const STEP020_ID = GRAPH_ID_BASE + 3;
export const DATASET_ID = GRAPH_ID_BASE + 4;
export const SYK001_ID = 2;
export const SYK002_ID = 3;
export const COPYBOOK_ID = 4;

export const GRAPH: GraphData = {
  nodes: [
    { id: SYK001_ID, type: "PROGRAM", label: "SYK001" },
    { id: SYK002_ID, type: "PROGRAM", label: "SYK002" },
    { id: COPYBOOK_ID, type: "COPYBOOK", label: "SYKCPY1" },
    { id: JOB_ID, type: "JOB", label: "SYKD010" },
    { id: STEP010_ID, type: "STEP", label: "STEP010" },
    { id: STEP020_ID, type: "STEP", label: "STEP020" },
    { id: DATASET_ID, type: "DATASET", label: "SYKT.ORDER.DAILY" },
  ],
  edges: [
    { from: JOB_ID, to: STEP020_ID, kind: "EXECUTION", resolution: "CONSTANT", seq: 2, line: 27, access: null },
    { from: JOB_ID, to: STEP010_ID, kind: "EXECUTION", resolution: "CONSTANT", seq: 1, line: 14, access: null },
    { from: STEP010_ID, to: SYK001_ID, kind: "EXECUTION", resolution: "CONSTANT", seq: 1, line: 14, access: null },
    { from: STEP010_ID, to: DATASET_ID, kind: "REFERENCE", resolution: "CONSTANT", seq: 0, line: 16, access: "READ" },
    { from: SYK001_ID, to: SYK002_ID, kind: "CALL", resolution: "DATAFLOW", seq: 1, line: 110, access: null },
  ],
  paragraphs: [
    { id: 21, programSourceId: SYK001_ID, name: "MAIN-PROC", startLine: 80, endLine: 90 },
    { id: 22, programSourceId: SYK001_ID, name: "READ-ORDER", startLine: 91, endLine: 99 },
    { id: 23, programSourceId: SYK001_ID, name: "WRITE-ERROR", startLine: 100, endLine: 110 },
  ],
  paragraphEdges: [
    { programSourceId: SYK001_ID, from: 21, to: 23, toName: "WRITE-ERROR", kind: "PERFORM", line: 85, seq: 2 },
    { programSourceId: SYK001_ID, from: 21, to: 22, toName: "READ-ORDER", kind: "PERFORM", line: 82, seq: 1 },
    { programSourceId: SYK001_ID, from: 21, to: 22, toName: "READ-ORDER", kind: "FALLTHROUGH", line: null, seq: 3 },
  ],
};

export const INVENTORY = [
  { id: SYK001_ID, path: "cobol/SYK001.cbl", name: "SYK001.cbl", type: "PROGRAM", codepage: "Shift_JIS", byteSize: 10, findingCount: 0 },
  { id: SYK002_ID, path: "cobol/SYK002.cbl", name: "SYK002.cbl", type: "PROGRAM", codepage: "Shift_JIS", byteSize: 10, findingCount: 0 },
  { id: 5, path: "jcl/SYKD010.jcl", name: "SYKD010.jcl", type: "JCL", codepage: "Shift_JIS", byteSize: 10, findingCount: 0 },
];
