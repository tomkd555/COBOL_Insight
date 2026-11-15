# COBOL Insight

COBOL Insight is a Windows desktop application for offline static analysis of Japanese mainframe
assets. Source files in Shift_JIS, UTF-8 and EBCDIC (IBM930/IBM939) are read and written byte for
byte.
Nothing leaves the machine.

## Install

Download `COBOL_Insight_v<version>.zip` from [Releases](https://github.com/tomkd555/COBOL_Insight/releases),
extract it and run `COBOL Insight.exe`, the desktop application. It starts the engine,
`COBOLInsight.exe`, automatically. The archive bundles its own Java runtime. The application runs on
Windows x64.

## What is analysed

The engine detects each file's asset kind from its content and falls back to the extension.

| Asset kind | Extensions | What the engine analyses |
| --- | --- | --- |
| COBOL, copybooks | `.cbl` `.cob` `.cobol`, `.cpy` `.copy` | Data and procedure divisions, control and data flow, `EXEC SQL`, `EXEC CICS`, DL/I and MQI calls |
| JCL | `.jcl` | Jobs, steps, PROC and INCLUDE expansion, symbols, DD statements, and the control statements of the step: `IKJEFT01` (DSN RUN, BIND), `DFSRRC00` (IMS region, program and PSB), `DFHCSDUP` (CICS resource definitions), `DSNUTILB`, `IDCAMS`, DFSORT, `IEBCOPY`, `IEFBR14`, and Easytrieve source in-stream under `EZTPA00` |
| Db2 SQL | `.sql` `.ddl` `.dml` `.spufi` | DDL, DML, native SQL PL routines; the same grammar reads embedded SQL and the SQL of Easytrieve |
| CICS BMS | `.bms` | Map sets, maps and fields |
| Easytrieve Plus | `.ezt` `.ezy` `.ezp` `.easy` | Files and fields, activities (JOB, SORT, PROGRAM), procedures, reports, macros (expanded from the member or from MSTART…MEND), calls and SQL |
| IMS | `.dbd`, `.psb` | DBDGEN and PSBGEN source: databases, segments, PCBs and sensitive segments |

The engine links files of all these kinds in one call graph: job → step → program (COBOL or
Easytrieve, run directly, under `IKJEFT01` or in an IMS region) → called programs and the resources
they use: data sets, Db2 tables, PSBs and IMS databases, MQ
queues, CICS transactions and BMS maps.

## Rules

The engine's `lint` subcommand evaluates 110 built-in rules. Each rule has three attributes: the technology it covers; its
category, which says what goes wrong in production when a finding is ignored; and the symptom of
the defect, such as an abend code, an SQLCODE or a JCL message. The catalogue can be filtered by
any of the three. The `rules` subcommand prints the catalogue as JSON. Each entry says what the rule
detects and how to fix what it finds.

The 110 built-in rules cover eight technologies:

| Technology | Rule count | Examples |
| --- | --- | --- |
| COBOL | 31 | uninitialised items, S0C7 from unchecked or cleared numeric items, subscripts out of range, unchecked FILE STATUS, STOP RUN in a called program |
| Db2 | 28 | unchecked SQLCODE, cursor lifecycle, `-811`/`-305` conditions, COMMIT under CICS or IMS, host variable count mismatch, cartesian joins, LOAD `LOG NO` without an image copy, BIND options |
| JCL | 17 | GDG `(+1)` read back as `(0)`, temporary data sets not passed, DISP omitted, or coded so that a rerun fails, invalid data set names, PARM over 100 characters, missing DD for a program |
| CICS | 13 | unchecked RESP, missing RETURN, COMMAREA length, COBOL file I/O in a CICS program, STARTBR without ENDBR, READ UPDATE never released |
| Easytrieve | 10 | `JOB INPUT NULL` without STOP, fields beyond the record length, GET without an EOF test, unchecked SQLCODE, missing DD |
| MQ | 5 | unchecked completion and reason codes, MsgId/CorrelId not reset in a get loop, implicit syncpoint |
| IMS | 3 | unchecked PCB status code, SENSEG not in the DBD, BMP without checkpoints |
| Utilities | 3 | required DD missing, input and output on the same data set, IDCAMS delete-and-define return code |

A rule can be switched off, or given a different severity, in the rule configuration file passed
with `--rules`. The same file holds user-defined rules.

## Repository and build

Prerequisites: JDK 21, with `JAVA_HOME` set to it and its `bin` folder on PATH (the build calls
`jpackage`), and Node.js 20+.

The repository holds three parts:

- `src/engine`: the Java 21 command-line engine (`COBOLInsight.exe` in the distribution). Its
  subcommands are `scan`, `lint`, `report`, `transpile`, `fix`, `rules`, `save` and `decode`.
- `src/gui`: the Electron and React front end. It launches the engine as a child process and reads
  its SQLite, SARIF and JSON output.
- `src/vendor`: git submodules (a Che4z fork and a MAPA fork). Check them out before building.

```powershell
git clone --recurse-submodules https://github.com/tomkd555/COBOL_Insight.git
.\gradlew.bat dist           # app-image + GUI package -> release/COBOL_Insight_v<version>.zip
.\gradlew.bat :engine:app:installDist   # the engine the GUI launches in development
cd src\gui; npm ci; npm run dev   # GUI development
```

The version is defined in `gradle.properties`, and `src/gui/package.json` must have the same value.

## Licence

MIT. Third-party components are listed in `THIRD_PARTY_NOTICES.md`.
