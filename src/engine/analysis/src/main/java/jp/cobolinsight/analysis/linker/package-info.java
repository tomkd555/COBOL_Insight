/**
 * Builds the integrated call graph: matching EXEC PGM= to PROGRAM-ID, resolving static CALLs,
 * resolving dynamic CALLs via constant propagation, typing unresolved nodes and external utility
 * nodes, adding EXEC CICS transaction-transfer edges and map-reference edges, and resolving
 * transaction IDs to programs.
 */
package jp.cobolinsight.analysis.linker;
