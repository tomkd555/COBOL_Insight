/**
 * JCL analysis over the MAPA grammars, compiled from the src/vendor/mapa submodule. Covers the
 * job card, EXEC PGM= and EXEC of a PROC (in-stream or catalogued), DD statements and their
 * concatenations, INCLUDE, symbolic parameters (SET, PROC defaults, EXEC overrides) and COND,
 * and implements the JclParser interface.
 */
package jp.cobolinsight.frontend.jcl;
