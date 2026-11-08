# COBOL Insight

Offline static analysis of Japanese mainframe assets (COBOL, copybooks, JCL, CICS BMS maps, embedded
Db2 SQL) as a Windows desktop application. Sources in Shift_JIS, UTF-8 and EBCDIC (IBM930/IBM939) are
read and written byte-exactly. Nothing leaves the machine.

- `src/engine` — Java 21 command-line engine (`COBOLInsight.exe` in the distribution). Subcommands:
  `scan`, `lint`, `report`, `transpile`, `fix`, `rules`, `save`, `decode`.
- `src/gui` — Electron + React front end. It launches the engine as a child process and reads its
  SQLite, SARIF and JSON output.
- `src/vendor` — git submodules (Che4z fork, MAPA fork). Check them out before building.

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
