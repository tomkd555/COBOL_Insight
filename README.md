# COBOL Insight

Static analysis for Japanese mainframe assets — COBOL, copybooks, JCL, CICS BMS maps and embedded Db2
SQL — as a Windows desktop application. Sources in Shift_JIS, UTF-8 and EBCDIC (IBM930/IBM939) are read
and written byte-exactly. Nothing leaves the machine: there is no network access at runtime.

Two parts:

- `src/engine` — a Java 21 command-line analysis engine (`COBOLInsight.exe` in the distribution).
  It classifies assets from their bytes, decodes them, parses them (Eclipse Che4z for COBOL, MAPA
  grammars for JCL and Db2 SQL, an in-house grammar for BMS), builds control-flow and data-flow
  facts and the job → step → program → paragraph call graph, runs the rules, and writes SQLite,
  SARIF and reports.
- `src/gui` — an Electron + React front end in the shape of VS Code: explorer, editor (Monaco), problems,
  call graph, rules. It launches the engine as a child process and reads its artefacts.

## Build

Prerequisites: JDK 21 on `JAVA_HOME`, Node.js 20+, git with submodules.

```powershell
git clone --recurse-submodules <repo>
$env:JAVA_HOME = "<path to JDK 21>"
.\gradlew.bat build          # engine: compile, tests, samples acceptance suite
.\gradlew.bat dist           # engine app-image + GUI packaging -> release/COBOL_Insight_v<version>.zip
```

No Maven and no `~/.m2` are needed: the patched Che4z jar is committed under `src/engine/libs/m2`
(see `docs/vendor-che4z.md` for how it is produced and upgraded). The version lives in
`gradle.properties`.

GUI development: `cd src/gui; npm ci; npm run dev` (uses `.\gradlew.bat :engine:app:installDist`).

## Layout

| Path | Content |
|---|---|
| `src/engine` | Gradle modules `core`, `cobol-frontend`, `jcl-frontend`, `sql-frontend`, `bms-frontend`, `analysis`, `rules`, `transpile`, `app` |
| `src/gui` | Electron application |
| `src/vendor` | Git submodules: Che4z fork, MAPA fork |
| `samples` | Synthetic assets with a ground truth (`expected-results.md`, `expected-findings.tsv`) used by the acceptance tests |
| `docs` | `cli.md` (subcommands and JSON), `rules.md` (`rules.json` schema), `vendor-che4z.md` |
| `tools` | Upgrade and generator scripts |
| `release` | Output of `gradlew dist` (ignored) |

## Rules

Built-in rules and user-defined rules are listed, enabled, disabled and re-prioritised through one
file, `rules.json`, passed to every engine subcommand as `--rules`. Declarative rules need no Java:
a line pattern, a statement pattern, or a "checked after" data-flow query. See `docs/rules.md`.
