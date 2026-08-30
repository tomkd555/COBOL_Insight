# Vendored Che4z engine

COBOL parsing is done by the Eclipse Che4z COBOL Language Support engine (EPL-2.0), carried as a git
submodule at `src/vendor/che4z` and consumed as one pre-built jar,
`jp.cobolinsight.vendor:che4z-cobol-engine:<tag>-<suffix>`, from the in-tree Maven repository
`src/engine/libs/m2`. Everyday builds and CI never run Maven and never touch `~/.m2`.

| Piece | Where |
|---|---|
| Upstream | https://github.com/eclipse-che4z/che-che4z-lsp-for-cobol, tag `2.5.1` |
| Fork with our patch | https://github.com/tomkd555/che-che4z-lsp-for-cobol, branch `ja-identifiers-2.5.1` |
| Submodule | `src/vendor/che4z`, pinned to a commit of that branch |
| Artefact | `src/engine/libs/m2/jp/cobolinsight/vendor/che4z-cobol-engine/2.5.1-ja1/` (jar, POM, `.sha256`) |
| Consumer | `src/engine/cobol-frontend/build.gradle.kts` — the only module that sees the jar |
| Guard | `VendoredEngineJarTest` fails the build if the jar content drifts from `.sha256` |
| Licence text shipped | `src/engine/cli/src/main/resources/licenses/che4z/LICENSE.md` |

The artefact is the `engine`, `common` and `parser` modules merged into one jar (signatures and
`module-info.class` dropped, `META-INF/services` concatenated). Its POM declares no dependencies; the
third-party coordinates are listed explicitly in `cobol-frontend/build.gradle.kts` so the supply chain is
readable in one file. Stock Guice 4.2.3 fails class generation on JDK 21, so the `no_aop` classifier is used.

## The patch: Japanese identifiers

Upstream accepts only ASCII in user-defined words. Japanese mainframe sources use data names such as
`WS-検証金額`, which fail lexing as a syntax error. The fork branch widens the identifier character classes
(first and following characters) in seven files by these Unicode ranges:

| Range | Content |
|---|---|
| U+3040–U+309F | Hiragana |
| U+30A0–U+30FF | Katakana, including the prolonged sound mark U+30FC |
| U+4E00–U+9FFF | CJK unified ideographs |
| U+FF10–U+FF19 | Full-width digits |
| U+FF21–U+FF3A, U+FF41–U+FF5A | Full-width Latin letters |
| U+FF66–U+FF9F | Half-width katakana |

| File | Rule | Change |
|---|---|---|
| `server/parser/.../core/CobolLexer.g4` | `IDENTIFIER` | Main lexer |
| `server/engine/.../core/TechnicalLexer.g4` | `IDENTIFIER`, `COPYBOOK_IDENTIFIER` | Preprocessor lexer |
| `server/engine/.../core/CompilerDirectivesLexer.g4` | `IDENTIFIER` | Compiler directives |
| `server/engine/.../implicitDialects/cics/CICSLexer.g4` | `WORD_IDENTIFIER`, `COPYBOOK_IDENTIFIER` | CICS implicit dialect |
| `server/dialect-daco/.../daco/TechnicalLexer.g4` | `IDENTIFIER`, `COPYBOOK_IDENTIFIER` | DaCo dialect |
| `server/dialect-idms/.../idms/IdmsTechnicalLexer.g4` | `IDENTIFIER`, `COPYBOOK_IDENTIFIER` | IDMS dialect |
| `server/engine/.../replacement/ReplacingServiceImpl.java` | `Pattern.compile` | Adds `UNICODE_CHARACTER_CLASS` so COPY REPLACING (`\b` boundaries, case-insensitive) matches Japanese identifiers |

The SQL lexers (`Db2SqlLexer.g4`, `Db2SqlExecLexer.g4`) already use `\p{Alnum}\p{Other_Letter}` upstream
and need no change.

## Known limits

- Diagnostic positions count UTF-16 code units. A Japanese character is one column, so Che4z columns do
  not match byte columns of fixed-format source; the engine computes byte columns separately with
  `ByteOffsetTable`.
- COPY REPLACING pseudo-text of the form `==XXXX-==` does not replace part of a word (IBM semantics), so
  such replacements do not take effect and the resulting undefined-reference diagnostics remain.

## Upgrading to a new upstream tag

```powershell
pwsh -File tools/upgrade-che4z.ps1 -Tag 2.5.2 -Suffix ja1 -JdkHome <JDK 21> -MavenHome <Maven 3.9+>
```

The script rebases the patch commits onto the tag, runs Maven once (`install`, then the thin `engine`
jar with `-Dassembly.skipAssembly=true`), merges the three jars with `tools/MergeJars.java`, writes the
POM and `.sha256`, and refreshes the shipped licence text. It then prints the `git push`, the one-line
version change in `cobol-frontend/build.gradle.kts`, and the `git add` to run. Rebase conflicts stop the
script; resolve them in `src/vendor/che4z` and rerun.

EPL-2.0 requires modified sources to be available; the public fork branch satisfies that.

## MAPA (JCL and Db2 SQL grammars)

`src/vendor/mapa` is a submodule of https://github.com/tomkd555/mapa, branch `crlf-newline`, a fork of
https://github.com/cschneid-the-elder/mapa (MIT). The branch carries one fix: `NEWLINE` in `JCLLexer.g4`
and `JCLPPLexer.g4` was `[\n\r]`, which splits a CRLF terminator and turns the leftover LF into
`ERROR_CHAR`, breaking continued statements and quoted strings. Gradle compiles the grammars straight
from the submodule; there is no build artefact to refresh. `tools/upgrade-mapa.ps1` rebases the fix onto
a newer upstream commit.
