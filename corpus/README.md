# False-positive corpus

This corpus exists to measure false positives of the COBOL Insight static-analysis rules.
Every asset here is deliberately defect-free: each file demonstrates the *correct*,
defensive practice for one family of rules (checked file status, checked SQLCODE, checked
CICS RESP, bounded subscripts, initialised variables, matching MOVE sizes, and so on), so
that any finding a rule raises against this corpus is by definition a false positive rather
than a genuine defect. This complements `samples/`, whose `samples/期待結果.md` records
*intentional* defects for true-positive testing; this corpus carries no equivalent
ground-truth defect list because none of its findings should exist.

All files are synthetic and hand-written for this corpus. None of the code, comments, or
data is copied from real production sources. The `CRP` prefix (`CRP001.cbl`, `CRPD010.jcl`,
`CRPCPY1.cpy`, `CRPMAP1.bms`, ...) keeps corpus assets from colliding with `samples/SYK*`.

## Files

| File | Practice demonstrated |
|---|---|
| `CRP001.cbl` | QSAM batch: FILE STATUS checked after every OPEN/READ/WRITE/CLOSE, `PERFORM ... THRU` with an `EXIT` paragraph, every working-storage item initialised via `VALUE`, `EVALUATE` with `WHEN OTHER`, `ADD ... ON SIZE ERROR`, a MOVE into a larger receiving PICTURE, and an OCCURS subscript bounded by a checked counter |
| `CRP002.cbl` | VSAM KSDS updater: `READ`/`REWRITE`/`WRITE`/`DELETE` each with `INVALID KEY`/`NOT INVALID KEY` and FILE STATUS checked, no `GO TO` anywhere |
| `CRP003.cbl` | Called subprogram: LINKAGE SECTION parameters, `RETURN-CODE` set explicitly on every path, `GOBACK` as the last statement of each path |
| `CRP004.cbl` | Caller of `CRP003`: checks `RETURN-CODE` after every `CALL` |
| `CRP005.cbl` | Db2 program: `SQLCODE` checked after every `EXEC SQL` (`SELECT`, `UPDATE`, `INSERT`, and cursor `OPEN`/`FETCH`/`CLOSE`), explicit column lists, `FETCH FIRST 1 ROW ONLY` for a single-row `SELECT`, a `FOR FETCH ONLY` cursor, host variables whose PICTUREs match their Db2 columns, no reliance on `WHENEVER` |
| `CRP006.cbl` | CICS pseudo-conversational screen 1 of 2: every `EXEC CICS` (`RECEIVE MAP`, `SEND MAP`, `RETURN`, `XCTL`) checks `RESP`/`RESP2`; `RECEIVE MAP`/`SEND MAP` reference only the map defined in `CRPMAP1.bms`; `XCTL` hands off to `CRP007` |
| `CRP007.cbl` | CICS pseudo-conversational screen 2 of 2, the `XCTL` target of `CRP006`: ends with `EXEC CICS RETURN TRANSID(...) COMMAREA(...)`, never falls out of the pseudo-conversation |
| `CRPMAP1.bms` | Mapset `CRPMAP1` / map `CRPM01` with the two fields (`SHOCD`, `MSG`) that `CRP006` and `CRP007` reference |
| `CRPCPY1.cpy` | Record layout used by `CRP001`: a `REDEFINES` no longer than the item it redefines, an `OCCURS` with a matching count item, and `88`-level condition names that are actually referenced |
| `CRPCPY2.cpy` | Record layout used by `CRP002`: `88`-level condition names that are actually referenced |
| `CRP008.cbl` | Style probe for control-flow rules: `PERFORM` of a single paragraph (no `THRU`) everywhere except one `PERFORM ... THRU ... EXIT`, inside which a single `GO TO` jumps forward to that `EXIT` paragraph within the same SECTION — a legacy but correct idiom, not a defect |
| `CRP009.cbl` | `STRING`/`UNSTRING` with `ON OVERFLOW`, a signed PICTURE for a balance difference that can go negative, a password read from a file (never a literal in source) and only ever `DISPLAY`ed in masked form |
| `CRPD010.jcl` | Batch job: `SET` symbolic, `COND` on the non-first step, DD statements with `DSN`/`DISP`; runs `CRP001` then `CRP002` |
| `CRPD020.jcl` | Batch job: `SET` symbolic, one in-stream `PROC` (`CRPPRC01`), `IF`/`THEN`/`ENDIF` conditional logic instead of `COND` on the non-first step; runs `CRP005` then, through the in-stream PROC, `CRP008` |
