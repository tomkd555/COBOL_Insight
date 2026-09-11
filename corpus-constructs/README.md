# corpus-constructs

Hand-written, defect-free fixtures, one file per construct family of z/OS JCL and Db2 for z/OS
embedded SQL. They exist to prove that the engine reads every construct the JCL Reference and the
Db2 SQL Reference define, not to hold defects.

- `jcl/` — every JCL statement, JES2 and JES3 job control, every DD parameter family, PROC
  mechanics (`CP*.proc`), INCLUDE members (`CI*.jcl`), in-stream utility cards, scheduler markers,
  sequence numbers, lowercase JCL, DBCS comments in Shift_JIS and IBM930 (`*_CP930.*`).
- `sql/` — one COBOL program per SQL statement kind and host-variable form (`CSQ*.cbl`), DCLGEN
  copybooks (`CSD*.cpy`), an SQL-bearing INCLUDE member, a CICS program with SYNCPOINT, a BMS map.
- `cobol/` — the programs the JCL fixtures execute (`CCP*.cbl`), so program resolution has targets.
- `ddl/` — DDL scripts, a native SQL PL procedure, a trigger and a SPUFI input member.

Two `CREATE TABLE` statements of `ddl/CSL501.sql` are read only in part: the Db2 grammar this tool
embeds does not take `GENERATED ALWAYS AS IDENTITY`, so `CSDB.CSQ_MEISAI` and `CSDB.CSQ_AUDIT` come
back degraded. Each still names its table, which is what puts it in the call graph, but its columns
are not read and the table therefore carries no column count. The two are the `SQL script statements
degraded` of `parse-report.md`; a grammar that takes the clause would bring them to zero.

One COBOL file is lost whole. `sql/CSQ308.cbl` declares `01 WS-CLOB-FILE USAGE SQL TYPE IS
CLOB-FILE` and uses `WS-CLOB-FILE-NAME` and its siblings at lines 109-111 — the sub-items the Db2
precompiler generates and Che4z does not — so Che4z stops on "Variable WS-CLOB-FILE-NAME is not
defined" and the program reaches no SQL parse at all. It is the `COBOL files failed` of
`parse-report.md`, it is named in `SqlModelSnapshotTest.WITHOUT_A_MODEL`, and its 17 `EXEC SQL`
blocks reach no CRUD fact and no rule. `ParseRateReportTest` pins the count at 1 so it cannot
grow; a frontend that synthesised the precompiler's sub-items would bring it to zero.

Two tests read this folder:

- `ParseRateReportTest` (`src/engine/app`) parses everything and pins what came out: the JCL and
  SQL rates, the file and statement counts it reached, and a ceiling on each kind of loss — files
  failed, members skipped, statements not understood. The numbers land in
  `corpus-public/parse-report.md`.
- `RuleEvaluationReportTest` runs every rule over it; every finding must be listed in
  `baseline.tsv` with a reason. The fixtures carry no seeded defects, but a fixture written to
  carry a construct — `RESTART=` on a JOB card, a step that allocates none of its program's DDs —
  satisfies the rule that reads it, so a row is either a true finding of that kind or a rule limit,
  and its reason says which.

A fixture that a public corpus already covers is not duplicated here; `corpus-public/MANIFEST.md`
lists what that corpus contains.
