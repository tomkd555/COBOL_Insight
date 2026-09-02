# `rules.json` reference

The file passed with `--rules`. It carries two things: overrides for rules the
build already has, and definitions of custom rules. `scan`, `call-graph`, `lint`,
`sql-lint`, `report`, `fix preview`, `fix apply` and `rules` accept it.
`translate`, `save` and `decode` do not.

## Top level

```json
{
  "version": 2,
  "rules": {
    "R008": {"enabled": false},
    "R017": {"severity": "HIGH"}
  },
  "custom": [
    {"id": "U001", "name": "…", "message": "…", "match": {"kind": "line", "regex": "…"}}
  ]
}
```

| Key | Type | Required | Meaning |
|---|---|---|---|
| `version` | integer | yes | Format version. This build reads `2` and nothing else |
| `rules` | object | no | Rule id → override object. Absent means no override |
| `custom` | array | no | Custom rule definitions. Absent means none |

Unknown top-level keys are ignored.

### Failure handling

Failures split in two. A problem that puts the whole intended configuration out of
effect rejects the file; a problem confined to one entry drops that entry and
keeps the rest.

| Situation | Result |
|---|---|
| `--rules` not given, or the path is not a regular file | Not a failure. The defaults apply. The GUI passes the same path whether or not the file exists yet |
| The file cannot be read (I/O) | Rejected. Usage error, exit code 2 |
| The content is not JSON | Rejected. Usage error, exit code 2 |
| `version` missing, or not exactly the integer `2` | Rejected: `version は 2 のみ扱える(指定値 …)`. Usage error, exit code 2. `2.0` is a JSON fraction, not the integer 2, and is rejected |
| `rules` is not an object | Rejected: `rules はオブジェクトで書く` |
| `custom` is not an array | Rejected: `custom は配列で書く` |
| One override entry is malformed | That entry is dropped; the reason is recorded; the rest of the file applies |
| One `custom` entry is malformed | That entry is dropped; the reason is recorded; the rest of the file applies |
| Two `custom` entries share an id | The second is dropped: `ID が重複している: <id>` |
| A `custom` id collides with a built-in id | The custom rule is dropped: `利用者定義ルールの ID が組み込みルールと重なっている: <id>` |
| A key in `rules` names an id that is in no catalogue | Recorded as `設定にあるルールIDがカタログに無い: <id>`. Nothing else happens; the run continues |

Recorded per-entry problems stop nothing. Every command except `rules` prints them
to standard error as `警告: ルール設定: …`; `rules` carries them in its own output
under `ruleErrors`.

There is a second wording for an unknown id — `rule <id> was removed in V2` — used
when the id is on the removed list. That list holds `S003`, `S005` and `S006`; see
"How the default rules were validated" below for why they went.

## Per-rule override

The value of each `rules` entry.

| Field | Type | Required | Effect |
|---|---|---|---|
| `enabled` | boolean | no | Turns the rule on or off. Absent leaves the rule's own default |
| `severity` | string | no | Replaces the rule's default severity. Absent leaves it |

`severity` accepts `HIGH`, `MEDIUM`, `LOW`, `ADVISORY`, case-insensitively and
with surrounding whitespace trimmed. Anything else drops the entry with
`severity に扱えない値がある: …`. A non-boolean `enabled` drops it with
`enabled は true か false で書く`; a non-string `severity` with
`severity は文字列で書く`.

An overridden severity applies to the rule's findings as well as to the catalogue,
so the setting changes what a run reports and not just what the GUI shows.

Severity maps to the SARIF level and, through it, to the exit code:

| Severity | Finding level | SARIF level | Exit code contribution |
|---|---|---|---|
| `HIGH` | ERROR | `error` | 2 |
| `MEDIUM` | WARNING | `warning` | 1 |
| `ADVISORY` | WARNING | `warning` | 1 |
| `LOW` | NOTE | `note` | 0 |

Overriding a rule can therefore change a command's exit code.

## Custom rule object

An element of `custom`.

| Field | Type | Required | Default | Meaning |
|---|---|---|---|---|
| `id` | string | **yes** | — | Rule id. Must match `U[0-9A-Za-z_-]{1,15}` |
| `name` | string | **yes** | — | Display name |
| `message` | string | **yes** | — | Text of every finding this rule reports |
| `match` | object | **yes** | — | The detection itself; see below |
| `severity` | string | no | `MEDIUM` | `HIGH`, `MEDIUM`, `LOW` or `ADVISORY`, case-insensitive |
| `targets` | array of string | no | `["COBOL"]` | Asset kinds to examine |
| `commands` | array of string | no | `["LINT", "REPORT"]` | Subcommands this rule runs under |
| `category` | string | no | `利用者定義` | Category shown in the catalogue |
| `summary` | string | no | generated from `match` | One-line description |
| `rationale` | string | no | a stock sentence | Why it matters |
| `remedy` | string | no | a stock sentence | How to fix it |
| `badExample` | string | no | `""` | Offending fragment |
| `goodExample` | string | no | `""` | Its corrected form |

`detection` is not settable: the text is generated from the `match` object, so the
catalogue description cannot drift from what the rule does.

`id`, `name` and `message` are rejected when absent, non-string or blank
(`… が無い、または空である`). Optional string fields that are present but blank
fall back to their default; a present non-string value is rejected with
`… は文字列で書く`.

### `id` format

`U` followed by 1 to 15 characters from `[0-9A-Za-z_-]`, so 2 to 16 characters
overall. The `U` prefix keeps custom ids clear of the built-in `R` (syntax,
control flow, data flow) and `S` (SQL) ranges, so a custom rule can never collide
with a built-in one. A violation drops the entry with `id は U で始まり、
英数字・ハイフン・下線が1〜15文字続く形にする(指定値 …)`.

### `severity`

`HIGH`, `MEDIUM`, `LOW`, `ADVISORY` — the same four values as an override, mapping
to levels and exit codes the same way. Rejected otherwise with
`severity に扱えない値がある: …(扱えるのは HIGH・MEDIUM・LOW・ADVISORY)`.

### `commands`

Which subcommands run the rule. Allowed values, upper-cased and trimmed before
matching:

| Value | Subcommand |
|---|---|
| `LINT` | `lint` |
| `SQL_LINT` | `sql-lint` |
| `REPORT` | `report` |
| `FIX` | `fix preview` and `fix apply` |
| `SCAN` | `scan` (and `call-graph`, which runs the same pipeline) |

Absent means `["LINT", "REPORT"]`. An empty array is rejected
(`commands が空である`); a non-array with `commands は配列で書く`; an unknown value
with `commands に扱えない値がある: …`.

Declaring `FIX` does not make a custom rule produce a fix. `fix` asks the rule set
only for rules that carry a fix producer, and a custom rule has none, so a custom
rule never fires under `fix`.

### `targets`

Which asset kinds the rule examines. Allowed values, upper-cased and trimmed:
`COBOL`, `COPYBOOK`, `BMS`, `JCL`. Absent means `["COBOL"]`. An empty array is
rejected (`targets が空である`); a non-array with `targets は配列で書く`; an
unknown kind with `targets に扱えない種別がある: …`.

All three `match.kind` values honour `targets`. Each file's kind is resolved from
its extension, and a file of a kind the rule does not list — or of an extension that
names no kind at all — is skipped. `statement` and `checked-after` walk parsed
programs, so the only distinction they can draw in practice is `COBOL` against
`COPYBOOK`; a rule that lists neither examines nothing.

## `match.kind`

Exactly one of `line`, `statement`, `checked-after`. `match.kind` is required;
anything else is rejected with `match.kind に扱えない値がある: …(扱えるのは
line・statement・checked-after)`. A `match` that is absent or not an object is
rejected with `match が無い` or `match はオブジェクトで書く`.

### `line`

A regular expression applied to each line of decoded source. At most one finding
per line: reporting the second and later matches on a line would put the count out
of step with the line-oriented check the author wrote.

| Field | Type | Required | Default | Meaning |
|---|---|---|---|---|
| `kind` | string | yes | — | `"line"` |
| `regex` | string | **yes** | — | Java regular expression; a line is reported when it is found anywhere in the scanned span |
| `ignoreCase` | boolean | no | `false` | Compiles both `regex` and `excludeRegex` case-insensitively |
| `area` | string | no | `"programArea"` | `"programArea"` or `"wholeLine"` |
| `excludeRegex` | string | no | none | A line that also matches this is not reported |

`area = "programArea"` — for `COBOL` and `COPYBOOK` files, comment lines (`*` or
`/` in column 7) are skipped, columns 73 onward are cut off, and columns 1 to 7
are blanked before matching, so the reported column still matches the column in
the original. `BMS` and `JCL` have no fixed-format layout and are always scanned
whole. `area = "wholeLine"` scans the raw line for every kind, comment lines
included.

A regular expression that does not compile drops the entry with
`match.regex の正規表現を解釈できない: …` (or `match.excludeRegex …`). An
unknown `area` is rejected with `match.area に扱えない値がある: …(扱えるのは
programArea・wholeLine)`.

Writing `${match}` in `message` substitutes the matched text.

The finding's line is the matching line and its column is the one-based start of
the match within the scanned span.

Worked example — ban `GO TO` outside comment lines, in COBOL and copybooks, unless
the line is a `GO TO ... DEPENDING ON`:

```json
{
  "version": 2,
  "custom": [
    {
      "id": "U-GOTO",
      "name": "GO TO の使用",
      "severity": "LOW",
      "targets": ["COBOL", "COPYBOOK"],
      "commands": ["LINT", "REPORT"],
      "message": "GO TO を使っている: ${match}",
      "rationale": "段落をまたぐ GO TO は制御の流れを追いにくくする。",
      "remedy": "PERFORM 文による段落の呼び出しに置き換える。",
      "match": {
        "kind": "line",
        "regex": "GO\\s+TO\\b",
        "ignoreCase": true,
        "area": "programArea",
        "excludeRegex": "DEPENDING\\s+ON"
      }
    }
  ]
}
```

### `statement`

Statements of the named verbs that carry none of the named clauses. With no
`missingClause`, every statement of those verbs is reported, which is how an
author bans a verb outright.

| Field | Type | Required | Default | Meaning |
|---|---|---|---|---|
| `kind` | string | yes | — | `"statement"` |
| `verb` | string or array of string | **yes** | — | Verbs to look at, upper-cased before matching |
| `missingClause` | string or array of string | no | none | Report the statement only when its text contains none of these, upper-cased before matching |
| `inParagraph` | string | no | none | Only look inside paragraphs whose name this regular expression finds. Always case-insensitive |

Both `verb` and `missingClause` accept a single string as shorthand for a
one-element array. `verb` is rejected when absent or empty
(`verb が無い、または空である`); either field is rejected when an element is not a
non-blank string (`… は空でない文字列の配列で書く`). An `inParagraph` that does
not compile is rejected with `match.inParagraph の正規表現を解釈できない: …`.

Matching is done on the statement text with runs of whitespace collapsed and
upper-cased, so a clause split across two lines still matches. Statements nested
inside compound statements are visited. The finding sits on the statement's start
line, column 1.

Worked example — every `READ` that has no `AT END` and no `INVALID KEY`, anywhere
in the program:

```json
{
  "version": 2,
  "custom": [
    {
      "id": "U010",
      "name": "READ の終端句なし",
      "severity": "MEDIUM",
      "message": "READ に AT END も INVALID KEY もありません。",
      "match": {
        "kind": "statement",
        "verb": ["READ"],
        "missingClause": ["AT END", "INVALID KEY"],
        "inParagraph": "^FILE-"
      }
    }
  ]
}
```

### `checked-after`

After a statement of interest, the forward control flow has to branch on a
condition naming one of the data items before it leaves the declared scope. This
is the shape of the built-in status-not-checked rules expressed declaratively, and
it walks the same control flow graph they do.

| Field | Type | Required | Default | Meaning |
|---|---|---|---|---|
| `kind` | string | yes | — | `"checked-after"` |
| `after` | object | **yes** | — | Which statement starts the check |
| `after.verb` | string | **yes** | — | The verb, upper-cased before matching |
| `after.textRegex` | string | no | none | Narrows to statements whose text this finds. Compiled case-sensitively and matched against the upper-cased, whitespace-collapsed text, so write it in upper case |
| `checks` | object | **yes** | — | What has to be examined afterwards |
| `checks.dataItem` | string or array of string | **yes** | — | Data item names. A condition that contains one of them, upper-cased, counts as the check |
| `scope` | string | no | `"untilNextMatchingStatement"` | How far forward to look |
| `onEveryPath` | boolean | no | `false` | Whether every path has to check, or one is enough |

`scope` values:

| Value | Stops at |
|---|---|
| `untilNextMatchingStatement` | The next statement with the same verb, which overwrites the status |
| `untilParagraphEnd` | Control leaving the paragraph the statement sits in |
| `untilProgramEnd` | Nothing; the flow is followed to the end of the program |

An unknown `scope` is rejected with `match.scope に扱えない値がある: …(扱えるのは
untilNextMatchingStatement・untilParagraphEnd・untilProgramEnd)`. A missing or
empty `checks.dataItem` is rejected with `checks.dataItem が無い、または空である`;
a non-string element with `checks.dataItem は空でない文字列で書く`. `after` and
`checks` are rejected when absent (`after が無い`, `checks が無い`) or when not
objects.

`onEveryPath = false` reports a statement when no forward path reaches a check.
`onEveryPath = true` reports it when any forward path reaches the scope boundary
without one. Under `onEveryPath`, a cycle that never checks counts as checked: an
execution that never leaves the loop never reaches the boundary either.

A node counts as a check when it is a compound statement whose condition text
contains one of the data item names. The finding sits on the subject statement's
**end** line, column 1.

Worked example — every `CALL` whose text mentions `SUB` must be followed, before
the next `CALL`, by a condition naming `RETURN-CODE` or `WS-STATUS`, on every path:

```json
{
  "version": 2,
  "custom": [
    {
      "id": "U020",
      "name": "CALL 後の状態未検査",
      "severity": "HIGH",
      "commands": ["LINT", "REPORT"],
      "message": "CALL の後で RETURN-CODE を検査していません。",
      "match": {
        "kind": "checked-after",
        "after": {"verb": "CALL", "textRegex": "SUB"},
        "checks": {"dataItem": ["RETURN-CODE", "WS-STATUS"]},
        "scope": "untilNextMatchingStatement",
        "onEveryPath": true
      }
    }
  ]
}
```

## Checking a file

`cobol-insight rules --rules rules.json --json` loads the file and prints the
resulting catalogue. Custom rules appear with `"source": "user"`, overrides show
in `severity` and `enabled` next to `defaultSeverity` and `defaultEnabled`, and
every per-entry problem appears in `ruleErrors`.

## How the default rules were validated

The build ships 34 built-in rules. Which of them are on by default, and which
exist at all, was settled by measurement rather than by taste.

**The two folders.** `samples/` carries deliberate defects, one row each in
`samples/expected-findings.tsv`, and answers "does the rule find what it claims
to find". `corpus/` answers the opposite question: every file in it is written to
demonstrate the correct, defensive practice for one family of rules, so it holds
no defects and every finding against it is a false positive by construction. Both
folders are synthetic — hand-written for this repository, nothing copied from a
real system — so the numbers say how a rule behaves on the idioms these files use,
not how often it is right in the field.

**The harness.** `RuleEvaluationReportTest` (in the `app` module) runs `lint` and
`sql-lint` over both folders with every rule forced on, ignoring any `rules.json`,
so a rule that ships disabled is measured too. It writes the per-rule table into
`corpus/rule-hits.md`: samples findings, how many of them are expected defects,
how many are not, and how many findings the corpus drew.

**The baseline.** Every corpus finding has to be listed in `corpus/baseline.tsv`
as `ruleId <TAB> file:line <TAB> reason`, with `*` in place of `file:line`
accepting every finding of one rule. The test fails when a corpus finding is not
listed, and when a listed one no longer fires, so noise arrives as a diff someone
has to read instead of drifting in unnoticed. The same test gates coverage: a rule
that is on by default must have at least one positive fixture, either a row in
`expected-findings.tsv` or a test under the `rules` module that builds the rule and
evaluates it.

**What the measurement changed.** R008 (PERFORM without THRU) now ships off: 43
findings over samples and 51 over the corpus, none of them a defect. S005 (FETCH
FIRST missing) and S006 (OPTIMIZE FOR missing) were dropped: they fired on every
SELECT and on every cursor declaration for no defect at all. S003 was folded into
S002, which now carries both message variants — a function on a column is one way
of being non-SARGable, and a function on the left of a comparison used to be
reported by both rules at once. R011 and R016 kept their place with the cause of a
false positive fixed rather than baselined. Proposals to switch off R006, R009,
R029 and R030, and to re-scope R017, were rejected: the first four drew no findings
at all, and the R017 re-scope would have lost expected defect No.6. The verdict on
each rule, with the number behind it, is in `corpus/rule-hits.md`.
