# Third-party notices

COBOL Insight bundles the following third-party software. Licence texts for the vendored sources are
shipped inside the engine jar under `licenses/` and are reproduced in the linked upstream repositories.

## Analysis engine (`src/engine`)

| Component | Version | Licence | Source |
|---|---|---|---|
| Eclipse Che4z COBOL Language Support (engine, common, parser), patched for Japanese identifiers | 2.5.1 + `ja-identifiers-2.5.1` | EPL-2.0 | https://github.com/eclipse-che4z/che-che4z-lsp-for-cobol, fork https://github.com/tomkd555/che-che4z-lsp-for-cobol |
| MAPA — JCL and Db2 for z/OS grammars | branch `crlf-newline` | MIT | https://github.com/cschneid-the-elder/mapa, fork https://github.com/tomkd555/mapa |
| ANTLR 4 runtime and tool | 4.13.2 | BSD-3-Clause | https://www.antlr.org/ |
| ICU4J | 78.3 | Unicode License v3 (ICU) | https://icu.unicode.org/ |
| SQLite JDBC (includes SQLite) | 3.53.2.0 | Apache-2.0 (SQLite: public domain) | https://github.com/xerial/sqlite-jdbc |
| picocli | 4.7.7 | Apache-2.0 | https://picocli.info/ |
| Eclipse LSP4J | 0.14.0 | EPL-2.0 | https://github.com/eclipse-lsp4j/lsp4j |
| Google Guice (no_aop) and guice-assistedinject | 4.2.3 | Apache-2.0 | https://github.com/google/guice |
| Google Guava | 33.2.1-jre | Apache-2.0 | https://github.com/google/guava |
| Gson | 2.11.0 | Apache-2.0 | https://github.com/google/gson |
| Apache Commons Lang, IO, Text | 3.18.0 / 2.14.0 / 1.14.0 | Apache-2.0 | https://commons.apache.org/ |
| SLF4J | 2.0.9 | MIT | https://www.slf4j.org/ |
| Logback | 1.3.16 | EPL-1.0 / LGPL-2.1 | https://logback.qos.ch/ |

The distribution embeds a Java runtime image produced by `jlink`/`jpackage` from the Eclipse Temurin
JDK 21 (GPL-2.0 with Classpath Exception).

## Desktop application (`src/gui`)

| Component | Licence | Source |
|---|---|---|
| Electron | MIT | https://www.electronjs.org/ |
| React, React DOM | MIT | https://react.dev/ |
| sql.js (SQLite compiled to WebAssembly) | MIT (SQLite: public domain) | https://github.com/sql-js/sql.js |
| Monaco Editor | MIT | https://github.com/microsoft/monaco-editor |
| Cytoscape.js, cytoscape-elk | MIT | https://js.cytoscape.org/ |
| elkjs (Eclipse Layout Kernel) | EPL-2.0 | https://github.com/kieler/elkjs |
| 7zip-bin (7-Zip) | MIT (7-Zip: LGPL-2.1 with unRAR restriction) | https://github.com/develar/7zip-bin |

Exact versions of the application dependencies are recorded in `src/gui/package-lock.json`.
