# COBOL Insight

Offline static analysis of Japanese mainframe assets as a Windows desktop application. Sources in
Shift_JIS, UTF-8 and EBCDIC (IBM930/IBM939) are read and written byte-exactly. Nothing leaves the
machine.

- `src/engine` — Java 21 command-line engine (`COBOLInsight.exe` in the distribution). Subcommands:
  `scan`, `lint`, `report`, `transpile`, `fix`, `rules`, `save`, `decode`.
- `src/gui` — Electron + React front end. It launches the engine as a child process and reads its
  SQLite, SARIF and JSON output.
- `src/vendor` — git submodules (Che4z fork, MAPA fork). Check them out before building.

## What is analysed

The asset kind is decided from the content of each file; the extension is only the fallback.

| Asset | Extensions | What the engine reads |
| --- | --- | --- |
| COBOL, copybooks | `.cbl` `.cob` `.cobol`, `.cpy` `.copy` | Data and procedure division, control and data flow, `EXEC SQL`, `EXEC CICS`, DL/I and MQI calls |
| JCL | `.jcl` | Jobs, steps, PROC and INCLUDE expansion, symbols, DD statements, and the control statements of the step: `IKJEFT01` (DSN RUN, BIND), `DFSRRC00` (IMS region, program and PSB), `DFHCSDUP` (CICS resource definitions), `DSNUTILB`, `IDCAMS`, DFSORT, `IEBCOPY`, `IEFBR14`, and Easytrieve source in-stream under `EZTPA00` |
| Db2 SQL | `.sql` `.ddl` `.dml` `.spufi` | DDL, DML, native SQL PL routines; the same grammar reads embedded SQL and the SQL of Easytrieve |
| CICS BMS | `.bms` | Map sets, maps and fields |
| Easytrieve Plus | `.ezt` `.ezy` `.ezp` `.easy` | Files and fields, activities (JOB, SORT, PROGRAM), procedures, reports, macros (expanded from the member or from MSTART…MEND), calls and SQL |
| IMS | `.dbd`, `.psb` | DBDGEN and PSBGEN source: databases, segments, PCBs and sensitive segments |

All of them meet in one call graph: job → step → program (COBOL or Easytrieve, also through a TSO
launcher or an IMS region) → called programs, data sets, Db2 tables, PSBs and IMS databases, MQ
queues, CICS transactions and BMS maps.

## Rules

`lint` evaluates 112 built-in rules. Each rule states the technology it is about, what goes wrong
in production when the finding is ignored (its category), and how the defect shows itself — an
abend code, an SQLCODE, a JCL message — so the catalogue can be filtered by any of the three.
`rules` prints the catalogue as JSON, with the detection condition and the remedy of every rule.

| Technology | Rules | Examples |
| --- | --- | --- |
| COBOL | 31 | uninitialised items, S0C7 from unchecked or cleared numeric items, subscripts out of range, unchecked FILE STATUS, STOP RUN in a called program |
| Db2 | 29 | unchecked SQLCODE, cursor lifecycle, `-811`/`-305` conditions, COMMIT under CICS or IMS, host variable count mismatch, cartesian joins, LOAD `LOG NO` without an image copy, BIND options |
| JCL | 18 | GDG `(+1)` read back as `(0)`, temporary data sets not passed, DISP omitted or left to fail a rerun, invalid data set names, PARM over 100 characters, missing DD for a program |
| CICS | 13 | unchecked RESP, missing RETURN, COMMAREA length, COBOL file I/O in a CICS program, STARTBR without ENDBR, READ UPDATE never released |
| Easytrieve | 10 | `JOB INPUT NULL` without STOP, fields beyond the record length, GET without an EOF test, unchecked SQLCODE, missing DD |
| MQ | 5 | unchecked completion and reason codes, MsgId/CorrelId not reset in a get loop, implicit syncpoint |
| IMS | 3 | unchecked PCB status code, SENSEG not in the DBD, BMP without checkpoints |
| Utilities | 3 | required DD missing, input and output on the same data set, IDCAMS delete-and-define return code |

A rule can be switched off, or given another severity, in the rule configuration file
(`--rules`); the same file holds user-defined rules.

## Build

Prerequisites: JDK 21 on `JAVA_HOME`, Node.js 20+.

```powershell
git clone --recurse-submodules <repo>
.\gradlew.bat dist           # app-image + GUI package -> release/COBOL_Insight_v<version>.zip
cd src\gui; npm ci; npm run dev   # GUI development (runs :engine:app:installDist first)
```

The version lives in `gradle.properties`; `src/gui/package.json` must carry the same value.

## Licence

MIT. Third-party components are listed in `THIRD_PARTY_NOTICES.md`.
