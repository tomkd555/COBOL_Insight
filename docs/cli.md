# `cobol-insight` command reference

The engine is a picocli application whose entry point is
`jp.cobolinsight.app.cli.Main`. It reports its version as `COBOL Insight 0.1.0-m2`.
Standard output and standard error are forced to UTF-8 before any subcommand runs.

Running `cobol-insight` with no subcommand prints usage and exits 0.

Every command below accepts `-h` / `--help` and `-V` / `--version` from picocli's
standard help mixin.

## Exit codes

`jp.cobolinsight.core.pipeline.ExitCodes` defines the whole contract:

| Code | Constant | Meaning |
|---|---|---|
| 0 | `SUCCESS` | No finding, or only findings below warning level |
| 1 | `WARNINGS` | At least one warning-level finding, no error-level finding |
| 2 | `ERRORS` | At least one error-level finding |

`ExitCodes.fromFindings` walks the findings and returns the highest level present.

**A non-zero exit code from `lint`, `sql-lint`, `report`, `scan`, `call-graph`,
`translate` or `fix` does not mean the run failed.** It means findings were
reported. The analysis completed and wrote its output files. Callers that treat
non-zero as a failure will misread a normal result; the GUI does not.

Two exit codes come from picocli rather than from findings, because the code does
not override picocli's defaults:

- An invalid option or argument — including a `--rules` file that is not JSON or
  carries an unsupported `version` — exits **2** (picocli's invalid-input code).
- An uncaught exception inside a subcommand exits **1** (picocli's execution-exception
  code), with a stack trace on standard error. `report` reaches this path when the
  `--db` file does not exist.

`rules` and `decode` are the exceptions to the finding-based contract and are
documented with their own codes below.

## Shared options

`--copybook-path DIR` (repeatable) — where COPY statements are resolved from.
Accepted by `scan`, `call-graph`, `lint`, `sql-lint`, `report`, `translate`,
`fix preview`, `fix apply` and `save`. With no value given, the tool walks the
asset folder and uses every directory that contained a copybook. Folder names are
not consulted.

`--codepage FILE=CHARSET` (repeatable map) — pins one file's code page, keyed by
its path relative to the asset folder or by its bare file name. Overrides
detection. Accepted by `scan`, `call-graph`, `lint`, `sql-lint`, `report`,
`translate`, `fix preview` and `fix apply`. `save` and `decode` take a different,
single-valued `--codepage CHARSET` that applies to the one file they operate on.

`--rules FILE` — the rule configuration file, described in `docs/rules.md`.
Accepted by `scan`, `call-graph`, `lint`, `sql-lint`, `report`, `fix preview`,
`fix apply` and `rules`. Not accepted by `translate`, `save` or `decode`.
A missing file is not an error: the defaults apply. A file that is not JSON, or
whose `version` this build cannot read, is rejected as a usage error. Per-entry
problems are written to standard error as `警告: ルール設定: …` by every command
except `rules`, which carries them in its output as `ruleErrors` instead.

All output paths are written as UTF-8, with parent directories created as needed.
Backslashes in the paths echoed back in summary JSON are normalised to `/`.

---

## `scan INPUT_DIR`

Walks the asset folder, analyses what it finds, persists the result into a SQLite
project file, and writes a summary JSON to standard output. Later subcommands read
or extend that database.

| Option | Argument | Required | Default | Effect |
|---|---|---|---|---|
| `INPUT_DIR` | path | yes | — | Asset folder to walk |
| `--db` | FILE | no | `cobol-insight.db` | SQLite project file to write |
| `--copybook-path` | DIR | no | discovered | COPY search path |
| `--codepage` | FILE=CHARSET | no | — | Per-file code page override |
| `--rules` | FILE | no | — | Rule configuration |
| `--copy-expansion` | FILE | no | — | Write the inline COPY expansions here |

Exit code: 0/1/2 from the findings of decoding, parsing, the `SCAN` rules and the
linker, raised to 2 if persistence reported an error and to 1 if it reported a
warning.

### Summary JSON (standard output)

```json
{
  "analyzed": ["src/PGM01.cbl"],
  "skipped": ["src/PGM02.cbl"],
  "removed": ["src/OLD.cbl"],
  "findingCount": 3,
  "dbFile": "cobol-insight.db",
  "copyExpansionFile": "out/expansion.json",
  "truncated": true,
  "undecided": ["docs/notes.txt"],
  "mismatches": [
    {"path": "src/JOB01.cbl", "byExtension": "COBOL", "byContent": "JCL"}
  ],
  "unreadable": ["locked/PGM99.cbl"],
  "exitCode": 1
}
```

- `analyzed` — sources written to the database on this run.
- `skipped` — sources left alone because their hash and analysis were already on
  record.
- `removed` — paths that were in the database and are no longer in the folder;
  their rows were deleted.
- `findingCount` — the count of decode and parse findings persisted. Linker
  findings steer the exit code but are not counted here.
- `dbFile` — the `--db` path as given.
- `copyExpansionFile` — present only when `--copy-expansion` was given.
- `truncated` — present, and always `true`, only when the walk hit its file limit.
  Absent otherwise.
- `undecided` — files whose kind the content-based classifier could not settle;
  they were left out of the analysis.
- `mismatches` — files whose extension and content disagreed. `byExtension` and
  `byContent` are `AssetKind` names (`BMS`, `COBOL`, `COPYBOOK`, `JCL`).
- `unreadable` — files and directories the walk could not read.

`undecided`, `mismatches` and `unreadable` are complete lists, not samples; there
is no separate count field.

### COPY expansion JSON (`--copy-expansion`)

For each program, the copybook lines that belong where its COPY statements sit,
after `REPLACING`. Programs with no expansion are omitted.

```json
{
  "programs": [
    {
      "path": "src/PGM01.cbl",
      "programId": "PGM01",
      "expansions": [
        {
          "copyStatementLine": 42,
          "copybookName": "CUSTREC",
          "copybookPath": "copy/CUSTREC.cpy",
          "lines": [
            {"copybookLine": 1, "text": "       01  CUST-REC."}
          ]
        }
      ]
    }
  ]
}
```

`copybookPath` is relative to the asset folder when the copybook is inside it, and
absolute otherwise.

---

## `call-graph INPUT_DIR`

Runs the same scan pipeline as `scan` — it also writes the SQLite project file —
and additionally serialises the call graph. With no output option, the JSON goes
to standard output.

| Option | Argument | Required | Default | Effect |
|---|---|---|---|---|
| `INPUT_DIR` | path | yes | — | Asset folder |
| `--db` | FILE | no | `cobol-insight.db` | SQLite project file |
| `--copybook-path` | DIR | no | discovered | COPY search path |
| `--codepage` | FILE=CHARSET | no | — | Per-file code page override |
| `--rules` | FILE | no | — | Rule configuration |
| `--json` | FILE | no | — | Graph as JSON |
| `--dot` | FILE | no | — | Graph as Graphviz DOT |
| `--svg` | FILE | no | — | Graph as SVG, rendered in-JVM by graphviz-java |
| `--png` | FILE | no | — | Graph as PNG, rendered in-JVM by graphviz-java |

Exit code: identical to `scan`'s, computed from the same summary.

### Graph JSON

```json
{
  "nodes": [
    {"id": "PGM:PGM01", "kind": "PROGRAM", "label": "PGM01"},
    {"id": "PGM:DYN", "kind": "UNRESOLVED", "label": "WS-PGM",
     "attributes": {"variable": "WS-PGM"}}
  ],
  "edges": [
    {"from": "JOB:J1", "to": "STEP:J1.S1", "kind": "EXECUTION",
     "resolution": "CONSTANT", "seq": 1, "line": 12}
  ]
}
```

- `nodes[].id` — unique node key; nodes are emitted in ascending `id` order.
- `nodes[].kind` — one of `JOB`, `STEP`, `PROGRAM`, `PARAGRAPH`, `DATASET`,
  `DB2_TABLE`, `UNRESOLVED`, `EXTERNAL_UTILITY`, `TRANSACTION`, `BMS_MAP`,
  `UNANALYZABLE`.
- `nodes[].label` — display name.
- `nodes[].attributes` — free-form string map; the key is absent when empty.
- `edges[]` — ordered by `from`, `to`, `kind`, `resolution`.
- `edges[].kind` — one of `CALL`, `EXECUTION`, `REFERENCE`,
  `TRANSACTION_TRANSITION`, `MAP_REFERENCE`.
- `edges[].resolution` — `CONSTANT`, `DATAFLOW` or `UNRESOLVED`.
- `edges[].seq` — the out-edge order within the source node. Emitted only when
  greater than zero; absent when the order is unknown.
- `edges[].line` — the call site's line. Absent when unknown.

The DOT output carries `label`, `kind` and the node attributes on nodes, and
`kind` and `resolution` on edges. `seq` and `line` are not in the DOT output.

---

## `lint INPUT_DIR`

Runs the bug-detection rules over the asset folder's COBOL, copybooks and BMS,
writes a SARIF 2.1.0 file and prints a summary JSON. Decode and parse failures
join the findings as errors.

| Option | Argument | Required | Default | Effect |
|---|---|---|---|---|
| `INPUT_DIR` | path | yes | — | Asset folder |
| `--file` | FILE | no | — | Analyse only this source, resolving its copybooks from the search path |
| `--sarif` | FILE | no | `cobol-insight.sarif` | SARIF output |
| `--copybook-path` | DIR | no | discovered | COPY search path |
| `--codepage` | FILE=CHARSET | no | — | Per-file code page override |
| `--rules` | FILE | no | — | Rule configuration |

Exit code: 0/1/2 from the merged findings.

### Summary JSON (standard output)

```json
{
  "analyzed": ["src/PGM01.cbl"],
  "findingCount": 4,
  "errors": 1,
  "warnings": 2,
  "notes": 1,
  "sarifFile": "cobol-insight.sarif",
  "exitCode": 2
}
```

`analyzed` lists the programs that parsed. `errors`, `warnings` and `notes` count
findings by level and sum to `findingCount`.

Anything the walk dropped or reinterpreted is written to standard error as
`警告: …`; only `scan` carries it in JSON.

### SARIF file

SARIF 2.1.0, deterministic: rules in ascending id order, results ordered by file,
line, column, rule id and message. `runs[0].tool.driver` is
`{"name": "COBOL Insight", "version": "0.1.0"}`. The rule table holds every rule
the command ran, whether or not it fired.

```json
{
  "$schema": "https://docs.oasis-open.org/sarif/sarif/v2.1.0/errata01/os/schemas/sarif-schema-2.1.0.json",
  "version": "2.1.0",
  "runs": [
    {
      "tool": {
        "driver": {
          "name": "COBOL Insight",
          "version": "0.1.0",
          "rules": [
            {
              "id": "R017",
              "name": "…",
              "shortDescription": {"text": "…"},
              "fullDescription": {"text": "summary\n\nrationale\n\ndetection"},
              "help": {"text": "対処: …"},
              "properties": {"category": "…"},
              "defaultConfiguration": {"level": "warning"}
            }
          ]
        }
      },
      "results": [
        {
          "ruleId": "R017",
          "ruleIndex": 0,
          "level": "warning",
          "message": {"text": "…"},
          "locations": [
            {"physicalLocation": {
              "artifactLocation": {"uri": "src/PGM01.cbl"},
              "region": {"startLine": 42, "startColumn": 12}}}
          ],
          "codeFlows": [
            {"threadFlows": [{"locations": [
              {"location": {
                "physicalLocation": {
                  "artifactLocation": {"uri": "src/PGM01.cbl"},
                  "region": {"startLine": 30, "startColumn": 12}},
                "message": {"text": "…"}}}
            ]}]}
          ],
          "fixes": [
            {"description": {"text": "…"},
             "artifactChanges": [
               {"artifactLocation": {"uri": "src/PGM01.cbl"},
                "replacements": [
                  {"deletedRegion": {"startLine": 42, "startColumn": 12,
                                     "endLine": 42, "endColumn": 12},
                   "insertedContent": {"text": "…"}}
                ]}
             ]}
          ]
        }
      ]
    }
  ]
}
```

- `ruleIndex` is emitted only when the rule is in the driver's rule table.
- `level` is `error`, `warning` or `note`.
- `codeFlows` appears only on findings that carry a taint path; `fixes` only on
  findings that carry a canned fix. Both keys are absent otherwise.
- A `deletedRegion` whose start equals its end is an insertion.
- `uri` values are relative to the asset folder where the file can be placed
  inside it or under a COPY search path, and absolute otherwise. Each path
  segment is percent-encoded per RFC 3986; `/` is preserved.

---

## `sql-lint INPUT_DIR`

Parses the COBOL, turns its `EXEC SQL` blocks into statement models, runs the SQL
advice rules and writes SARIF. Bug detection belongs to `lint`.

| Option | Argument | Required | Default | Effect |
|---|---|---|---|---|
| `INPUT_DIR` | path | yes | — | Asset folder |
| `--sarif` | FILE | no | `cobol-insight-sql.sarif` | SARIF output |
| `--copybook-path` | DIR | no | discovered | COPY search path |
| `--codepage` | FILE=CHARSET | no | — | Per-file code page override |
| `--rules` | FILE | no | — | Rule configuration |

Exit code: 0/1/2 from the findings. There is no `--file` option here.

Summary JSON and SARIF have exactly the shape `lint` produces, with
`sarifFile` naming the `--sarif` path.

---

## `report INPUT_DIR`

Reads the asset inventory, the call-graph summary and the persisted findings out
of a scanned SQLite file, re-runs the lint and SQL analysis in memory over the
same asset folder, and writes an HTML report and a text report.

| Option | Argument | Required | Default | Effect |
|---|---|---|---|---|
| `INPUT_DIR` | path | yes | — | Asset folder |
| `--db` | FILE | no | `cobol-insight.db` | Scanned SQLite project file, must already exist |
| `--html` | FILE | no | `cobol-insight-report.html` | HTML report |
| `--text` | FILE | no | `cobol-insight-report.txt` | Text report |
| `--copybook-path` | DIR | no | discovered | COPY search path |
| `--codepage` | FILE=CHARSET | no | — | Per-file code page override |
| `--rules` | FILE | no | — | Rule configuration |

Exit code: 0/1/2 from the union of the persisted scan findings, the lint findings
and the SQL advice. If `--db` names something that is not a regular file, the
command throws and picocli exits **1** — the file is not created, so a wrong path
cannot pass as an empty report.

### Summary JSON (standard output)

```json
{
  "assets": 12,
  "scanFindings": 1,
  "lintFindings": 7,
  "sqlAdvice": 2,
  "callGraphNodes": 30,
  "callGraphEdges": 41,
  "htmlFile": "cobol-insight-report.html",
  "textFile": "cobol-insight-report.txt",
  "exitCode": 1
}
```

`assets` is the row count of the inventory read from `SOURCE`. `scanFindings`
counts the rows restored from `FINDING` (decode and parse failures plus the
linker's resolution records). `lintFindings` and `sqlAdvice` come from the
in-memory re-analysis. The two report files are the only content output; there is
no JSON report.

---

## `translate INPUT_DIR`

Transpiles the asset folder's COBOL line by line into Python and/or Java, writes
the generated files, and persists the COBOL-to-generated line correspondence into
the `LINE_MAP` table.

| Option | Argument | Required | Default | Effect |
|---|---|---|---|---|
| `INPUT_DIR` | path | yes | — | Asset folder |
| `--language` | LANG | no | `both` | `python`, `java` or `both`; anything else is a usage error |
| `--out` | DIR | no | `transpile` | Where generated files are written |
| `--db` | FILE | no | `cobol-insight.db` | SQLite project file for `LINE_MAP` |
| `--copybook-path` | DIR | no | discovered | COPY search path |
| `--codepage` | FILE=CHARSET | no | — | Per-file code page override |

There is no `--rules`: no rule runs during translation.

Generated files are written as UTF-8 without a byte order mark. Sources whose line
map cannot be tied to a registered `SOURCE` row are skipped with a warning on
standard error.

Exit code: 0 or 2. Only decode and parse failures produce findings here, and they
are error level; the code is 2 when any is present, 0 otherwise.

### Summary JSON (standard output)

```json
{
  "analyzed": ["src/PGM01.cbl"],
  "generatedFiles": ["PGM01.java", "pgm01.py"],
  "outputDir": "transpile",
  "lineMapCount": 318,
  "findingCount": 0,
  "errors": 0,
  "exitCode": 0
}
```

`generatedFiles` holds bare file names, sorted and de-duplicated.
`lineMapCount` is the number of `LINE_MAP` rows written.

---

## `fix`

The parent command. With no subcommand it prints usage and exits 0.
Both subcommands share these options:

| Option | Argument | Required | Default | Effect |
|---|---|---|---|---|
| `INPUT_DIR` | path | yes | — | Asset folder |
| `--copybook-path` | DIR | no | discovered | COPY search path |
| `--codepage` | FILE=CHARSET | no | — | Per-file code page override |
| `--rules` | FILE | no | — | Rule configuration |

Only rules that carry a fix producer take part. Edits are computed on bytes so
that fixed-format columns survive the round trip. Original files are never
written by either subcommand.

### `fix preview INPUT_DIR`

Prints the unified diff of every proposed fix to standard output, ANSI-coloured
only when standard output is a terminal.

| Option | Argument | Required | Default | Effect |
|---|---|---|---|---|
| `--html` | FILE | no | — | Also write the diff as a self-contained HTML file |

A copybook fix prints an extra line naming the programs that copy it, since the
copybook itself is only shown, never rewritten.

Exit code: 0/1/2 from the analysis findings alone — the decode and parse failures.
A rule's own severity does not enter the code here, because it says nothing about
whether the fix is sound.

```json
{
  "fixedFiles": ["src/PGM01.cbl"],
  "copybookFixes": [
    {"copybook": "copy/CUSTREC.cpy", "importers": ["PGM01", "PGM02"]}
  ],
  "fixCount": 5,
  "analysisErrors": 0,
  "htmlFile": "out/diff.html"
}
```

`fixedFiles` names every file with a diff, copybooks included. `fixCount` is the
total number of edits. `analysisErrors` counts error-level analysis findings.
`htmlFile` is present only when `--html` was given. This summary has no
`exitCode` key.

### `fix apply INPUT_DIR`

Writes the fixed sources into an output folder, keeping their relative layout,
then re-parses each written file.

| Option | Argument | Required | Default | Effect |
|---|---|---|---|---|
| `--out` | DIR | no | `fix` | Output folder for the fixed sources |

Copybook fixes are not written. A copybook expands into more than one program, so it is
reported under `copybookFixes` with the programs that copy it and left to the
reader's judgement.

Exit code: 0/1/2 from the analysis findings plus the re-parse findings — 2 when
either carries an error.

```json
{
  "outputDir": "fix",
  "writtenFiles": ["src/PGM01.cbl"],
  "copybookFixes": [
    {"copybook": "copy/CUSTREC.cpy", "importers": ["PGM01", "PGM02"]}
  ],
  "fixCount": 5,
  "analysisErrors": 0,
  "reparseFailures": 0,
  "exitCode": 0
}
```

`writtenFiles` holds paths relative to `--out`. `reparseFailures` counts the
written files that no longer parse.

---

## `rules`

Lists the built-in and custom rules with their descriptions. The only subcommand
that takes no asset folder and runs no analysis.

| Option | Argument | Required | Default | Effect |
|---|---|---|---|---|
| `--rules` | FILE | no | — | Rule configuration, so overrides and custom rules show |
| `--json` | — | no | off | Emit JSON instead of the terminal table |
| `--id` | RULE_ID | no | — | Show only this rule, with its full description. Case-insensitive |

Exit code: **0** normally. **2** when `--id` names a rule that is not in the
catalogue, with `該当するルールが無い: <id>` on standard error. Findings play no
part — this command produces none.

Unlike every other command, `rules` does not print rule-configuration problems to
standard error; it carries them in its output (`ruleErrors` in JSON, trailing
`警告: ルール設定: …` lines in text).

### JSON (`--json`)

```json
{
  "ruleCount": 2,
  "rules": [
    {
      "id": "R017",
      "name": "…",
      "category": "…",
      "severity": "HIGH",
      "defaultSeverity": "MEDIUM",
      "hasFix": true,
      "source": "builtin",
      "enabled": true,
      "defaultEnabled": true,
      "summary": "…",
      "rationale": "…",
      "detection": "…",
      "remedy": "…",
      "badExample": "…",
      "goodExample": "…",
      "commands": ["LINT", "REPORT"],
      "targets": ["COBOL"],
      "needs": ["CFG", "SEMANTIC"]
    }
  ],
  "ruleErrors": ["rules.json の custom[0]: id が無い、または空である"]
}
```

- `severity` / `enabled` — as configured, after `--rules` was applied.
- `defaultSeverity` / `defaultEnabled` — what the rule declares on its own.
- `hasFix` — whether the rule supplies a fix producer.
- `source` — `builtin` or `user`.
- `badExample` / `goodExample` — may be empty strings.
- `commands`, `targets`, `needs` — enum names, each array sorted.
- Rules are ordered by ascending id. `ruleErrors` is empty when the configuration
  was clean.

This JSON is the GUI's rule catalogue; the GUI holds no rule text of its own.

---

## `save`

Writes text edited in the GUI back over the original file, keeping its code page
and line endings. This is the tool's one deliberate exception to leaving originals
untouched.

| Option | Argument | Required | Default | Effect |
|---|---|---|---|---|
| `--file` | FILE | **yes** | — | The original to write back to, absolute |
| `--edited` | FILE | **yes** | — | UTF-8 text file holding the complete edited content |
| `--codepage` | CHARSET | no | — | Code page of the original; beats detection and the database record |
| `--copybook-path` | DIR | no | discovered | COPY search path for the re-parse check |
| `--db` | FILE | no | — | Project file, consulted for the code page recorded at scan time |

The code page is resolved as `--codepage`, then the database record, then
detection. Only the lines that differ from the original are rewritten, as one
splice; untouched lines carry their original bytes across. The original is
replaced through a temporary file in the same directory, moved atomically where
the filesystem allows it, so a failed write leaves the original intact.

Content that cannot be encoded in the target code page (Shift_JIS, EBCDIC
CP930/939) is refused before anything is written, with exit code 2.

Exit codes:

| Code | Situation |
|---|---|
| 0 | Nothing differed, or the write-back succeeded and the file re-parses |
| 1 | The write-back succeeded but the result does not parse; the write is **not** rolled back, so that a half-finished edit can still be saved |
| 2 | Decode, encode or I/O failed before the write (the original is untouched), or the re-parse step itself threw after the write |

```json
{
  "written": true,
  "path": "C:/assets/src/PGM01.cbl",
  "changedLineFrom": 42,
  "changedLineTo": 48,
  "reparseErrors": [
    {"line": 44, "message": "…"}
  ],
  "error": "",
  "exitCode": 1
}
```

- `written` — whether the original was replaced. It stays `true` once the file has
  been replaced, even if a later step failed, so the caller never believes an
  already-overwritten original is still intact.
- `changedLineFrom` / `changedLineTo` — the span of rewritten lines. Both 0 when
  nothing changed.
- `reparseErrors` — error-level re-parse findings, each with `line` and `message`.
  At most one entry in practice; empty on success.
- `error` — the failure message, or `""`.

---

## `decode`

Decodes one source in its original code page and writes the text along with the
fixed-format column boundaries for each line. Boundaries are computed here because
they are byte columns, and a line with double-byte characters has no fixed
relation between character count and byte column.

| Option | Argument | Required | Default | Effect |
|---|---|---|---|---|
| `--file` | FILE | **yes** | — | The source to decode, absolute |
| `--out` | FILE | **yes** | — | Where the JSON result is written |
| `--codepage` | CHARSET | no | — | Beats detection and the database record |
| `--db` | FILE | no | — | Project file, consulted for the code page recorded at scan time |

Exit code: **0** on success, **2** on any failure. Findings play no part.
On failure the output file is still written, with empty fields and the message in
`error`.

```json
{
  "text": "       IDENTIFICATION DIVISION.\n",
  "codepage": "Shift_JIS",
  "detected": true,
  "soSiPresent": false,
  "lines": [
    {"byteLength": 32, "boundaries": [6, 7, 11, -1]}
  ],
  "stamp": {"mtimeMs": 1717000000000, "byteSize": 4096},
  "error": ""
}
```

- `text` — the decoded content.
- `codepage` — the charset actually used.
- `detected` — `true` when the code page was detected rather than forced.
- `soSiPresent` — whether shift-out / shift-in bytes were seen.
- `lines[].byteLength` — the line's length in bytes, its terminator included: one
  byte for LF, two for CRLF. Shift-out and shift-in bytes count as well, since they
  occupy byte columns even though no character maps to them. The last line of the
  file runs to the end of the file.
- `lines[].boundaries` — four values, for byte columns 7, 8, 12 and 73 (one-based):
  the start of the indicator, A, B and identification areas. Each value is the
  zero-based UTF-16 character offset within the line where that byte column
  begins, or `-1` when the line does not reach it. Byte columns are counted in the
  original encoding and the answer is an offset into the decoded text, so the two
  coincide only on a line of single-byte characters.

  A column landing inside a double-byte character does not split it: the boundary
  is the first character that *starts* at or after that byte. The terminator is one
  of the characters searched, which is what makes an exactly 72-byte line report
  the offset of its own line break for column 73 — that is, the position just past
  its text — rather than `-1`. The last line of a file that ends without a line
  break has no such character and reports `-1`.
- `stamp` — the original's last-modified time in milliseconds and its size in
  bytes, for the caller to detect a change under it.
- `error` — the failure message, or `""`.
