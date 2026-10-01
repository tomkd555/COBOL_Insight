# COBOL Insight

Offline static analysis of Japanese mainframe assets (COBOL, copybooks, JCL, CICS BMS maps, embedded
Db2 SQL) as a Windows desktop application. Sources in Shift_JIS, UTF-8 and EBCDIC (IBM930/IBM939) are
read and written byte-exactly. Nothing leaves the machine.

- `src/engine` — Java 21 command-line engine (`COBOLInsight.exe` in the distribution). Subcommands:
  `scan`, `lint`, `report`, `transpile`, `fix`, `rules`, `save`, `decode`.
- `src/gui` — Electron + React front end. It launches the engine as a child process and reads its
  SQLite, SARIF and JSON output.
- `src/vendor` — git submodules (Che4z fork, MAPA fork). Check them out before building.
- `src/tools` — vendor upgrade scripts and the EBCDIC sample generator.
- `samples` — the asset folders the acceptance tests and benchmarks read (see below).

## Build

Prerequisites: JDK 21 on `JAVA_HOME`, Node.js 20+.

```powershell
git clone --recurse-submodules <repo>
.\gradlew.bat build          # engine: compile, tests, acceptance suites
.\gradlew.bat dist           # app-image + GUI package -> release/COBOL_Insight_v<version>.zip
cd src\gui; npm ci; npm run dev   # GUI development (runs :engine:app:installDist first)
```

The version lives in `gradle.properties`; `src/gui/package.json` must carry the same value.

## Ground truth

| Folder | Content |
|---|---|
| `samples/seeded` | Synthetic assets with seeded defects; `expected-findings.tsv` lists every one |
| `samples/field` | Field-style batch and CICS programs; seeded defects in `expected-findings.tsv`, accepted false positives in `baseline.tsv` |
| `samples/corpus` | Defect-free programs: every finding is a false positive and must be in `baseline.tsv`; `rule-hits.md` is the measurement over all folders |
| `samples/corpus-public` | Third-party sources, unchanged, for the parse-rate benchmark |
| `samples/corpus-constructs` | One defect-free fixture per JCL and Db2 SQL construct family |

The acceptance tests in `:engine:app` fail on a finding missing from these tables and on a stale row.

## Licence

MIT. Third-party components are listed in `THIRD_PARTY_NOTICES.md`.
