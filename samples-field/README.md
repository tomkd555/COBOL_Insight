# Field-style samples

Synthetic mainframe assets written the way production code in a Japanese shop is written, with
deliberate defects next to the correct idiom for the same rule. `samples/` measures whether a rule
finds what it claims on a uniform house style; this folder measures how a rule behaves on the
idioms the field uses. Nothing here is copied from a real system.

Every finding on this folder is classified. `expected-findings.tsv` lists the seeded defects
(`no`, `file`, `line`, `ruleId`, `level`); `baseline.tsv` lists every other finding with a reason
(`ruleId`, `file:line` or `*`, `reason`). `FieldSamplesAcceptanceTest` and
`RuleEvaluationReportTest` fail on a finding in neither, on an expected row that does not fire and
on a baseline row that no longer fires. Precision of a rule on this folder is
expected / (expected + baselined), and the verdicts drawn from it are in `corpus/rule-hits.md`.

## Conventions

- Fixed format. Columns 1-6 carry a six-digit sequence number stepping by 100, column 7 the
  indicator, columns 8-72 the code, and columns 73-80 an eight-character change tag on the lines a
  maintenance change touched (`CHG24001`, `CHG25003`, ...). A tag starts in column 73 exactly.
- A line that carries Japanese text stays within 72 bytes counted in cp932 and carries no tag, so
  its layout is the same whether the columns are counted in characters or in bytes.
- Identifiers are romaji or English (`WS-NYUKIN-GAKU`, `KEIYAKU-NO`) except in `FLB040`, which
  uses Japanese identifiers (`WS-督促金額`) the way some shops do. Comments, literals, BMS
  `INITIAL` and JCL comments are Japanese.
- The domain is auto-loan sales finance: 契約 (KEIYAKU), 入金 (NYUKIN), 請求 (SEIKYU), 督促
  (TOKUSOKU), 顧客 (KOKYAKU), 残高 (ZANDAKA), 販売店 (HANBAITEN), 消込 (KESHIKOMI).
- Prefixes: `FLB` batch program, `FLO` online program, `FLS` subprogram, `FLC` copybook, `FLD`
  DCLGEN member, `FLJ` job, `FLP` catalogued PROC, `FLM` mapset, `FLENC` encoding probe.
- LF line endings, UTF-8 without BOM, except the two encoding renditions under `encoding/`.

## Files

| File | Models |
|---|---|
| `batch/FLJ010.jcl` | 日次入金消込 job: `/*JOBPARM`, `JOBLIB`, `JCLLIB ORDER=`, `SET`, IDCAMS DELETE with `SYSIN DD *`, `SORT` with `SYSIN DD *`, `EXEC FLP010` with symbolic overrides and a `//STEP1.DD` override, `IF (STEP030.RC > 4) THEN ... ELSE ... ENDIF`, `IKJEFT01` with `SYSTSIN DD *` running `FLB020` under `DSN`, `IEBGENER` to a GDG `(+1)` |
| `batch/FLJ020.jcl` | 月次請求 job: `FLP010` called a second time with different symbolics, `COND=(0,NE)`, `RESTART=`, one `PGM=FLB0301` that names no program in the folder |
| `batch/FLJ030.jcl` | Rule-verification job (R046/R047): `STEP020`'s `COND=(4,LT,STEP999)` names a step no earlier step defines; the last step's own `COND=(0,EQ)` is bypassed whenever the preceding steps end normally, next to `STEP030`'s ordinary `COND=(0,NE)` run-on-success guard |
| `batch/FLP010.proc` | Catalogued PROC next to the jobs: `STEP1` runs `FLB010`, `STEP2` runs `FLB040` under `COND=(0,NE,STEP1)`; symbolics `&CYCLE`, `&HLQ` |
| `batch/FLB010.cbl` | 入金データ編集 (QSAM): `SORT ... INPUT PROCEDURE ... OUTPUT PROCEDURE`, `RELEASE`/`RETURN`, `OCCURS ... DEPENDING ON`, `SEARCH ALL`, `INSPECT`, reference modification, `ACCEPT ... FROM DATE`, `COPY FLC010 REPLACING ==:PFX:== BY ==IN==`, `COPY FLCERR` as the last line of the PROCEDURE DIVISION, a common status-check paragraph |
| `batch/FLB020.cbl` | 入金消込 (Db2): `INCLUDE SQLCA`, `INCLUDE FLD010`, `WHENEVER SQLERROR GO TO`, a cursor `FOR UPDATE OF` with `UPDATE ... WHERE CURRENT OF`, a read-only cursor declared in WORKING-STORAGE, `SELECT INTO` with indicator variables, `COMMIT` every 500 rows, a dynamic `CALL WS-PGM-NAME` whose only value is the `VALUE 'FLS020'` clause, and a static `CALL 'FLS010'` |
| `batch/FLB030.cbl` | 請求書作成, legacy style: SECTIONs, `GO TO` loops, `ALTER`, `PERFORM ... THRU ...-EXIT`, unsigned totals, `EVALUATE` |
| `batch/FLB040.cbl` | 督促リスト, Japanese identifiers: `COPY FLC020 REPLACING LEADING`, `COPY FLC030 REPLACING` with partial-word pseudo-text, an FTP password literal, a card number in an error message |
| `batch/FLB050.cbl` | 契約明細抽出 (Db2): `INCLUDE FLD010`, a cursor without `WITH HOLD` whose fetch loop commits next to one declared `WITH HOLD`, a `FETCH INTO` whose nullable column has no indicator next to one that has, and a `SELECT INTO` whose `PIC X(08)` receives a `CHAR(10)` column next to a `PIC X(08)` that receives a `CHAR(8)` one |
| `batch/FLB055.cbl` | 契約処理区分の一括更新 (Db2): a `FETCH`/`UPDATE ... WHERE CURRENT OF` loop with no `COMMIT` anywhere, so the whole step is one unit of work |
| `batch/FLS010.cbl` | 共通エラーログ subprogram: LINKAGE SECTION, `RETURN-CODE`, `GOBACK` |
| `batch/FLB060.cbl` | 入金明細検証 (QSAM), rule-verification sample: an alphanumeric input transcribed into a numeric item with no `NUMERIC` test next to a tested one, an FD amount added to a total with no `NUMERIC` test next to a tested `IN-KENSU`, a reference modification past `WS-NAME`'s length next to an in-range one, and a `CALL 'FLS030'` whose operand is shorter than the callee's LINKAGE item next to a correctly sized `CALL 'FLS010'` |
| `batch/FLS030.cbl` | `FLB060`'s CALL target: `01 LK-DATA-AREA` is longer than the short operand `FLB060` passes |
| `batch/FLC010.cpy` | 入金レコード with `:PFX:` placeholders and `OCCURS 1 TO 30 DEPENDING ON` |
| `batch/FLC020.cpy` | 契約マスタ record with COMP-3 amounts and a REDEFINES date |
| `batch/FLC030.cpy` | 督促レコード with the `XX-` prefix |
| `batch/FLCERR.cpy` | A PROCEDURE DIVISION paragraph (`9900-ERROR-EXIT`) held in a copybook |
| `batch/FLD010.cpy` | DCLGEN of `FLDB.KEIYAKU`: `EXEC SQL DECLARE ... TABLE` and the host structure `DCLKEIYAKU` |
| `online/FLM010.bms` | Mapset `FLM010` with `PRINT NOGEN`, `TERM=3270-2`, maps `FLM01` (契約番号入力) and `FLM02` (契約明細, ten detail lines `MEI01`..`MEI10`), unlabelled constant fields, Japanese `INITIAL`, `PICIN`/`PICOUT`, `TIOAPFX=YES`, `CTRL=(FREEKB,FRSET)` |
| `online/FLM010.cpy` | The symbolic map copybook as the assembler would generate it, with an eleventh detail line `MEI11` the map does not define |
| `online/FLO010.cbl` | 契約照会 driver: `COPY DFHAID`, `COPY DFHBMSCA`, `EIBCALEN`, `HANDLE AID`, `HANDLE CONDITION MAPFAIL`, `RECEIVE MAP`, `READ DATASET ... RIDFLD ... RESP`, `WRITEQ TS`, `SEND TEXT ... NOHANDLE`, `LINK ... COMMAREA`, `XCTL`, `RETURN TRANSID ... COMMAREA` |
| `online/FLO020.cbl` | LINK target: reads Db2 with SQLCODE checked, fills the COMMAREA, `EXEC CICS RETURN END-EXEC` then `GOBACK` |
| `online/FLO030.cbl` | XCTL target that ends the conversation with a plain `EXEC CICS RETURN END-EXEC` |
| `online/FLO040.cbl` | 契約入金登録 (CICS): a conversation program that reads `DFHCOMMAREA` with no `EIBCALEN` test, a `RECEIVE MAP` whose `RESP` is tested against `DFHRESP(NORMAL)` only next to one covered by `HANDLE CONDITION MAPFAIL`, an `XCTL` with a `COMMAREA` shorter than the target's `DFHCOMMAREA` next to one with the full length, a `HANDLE ABEND` exit with no `SYNCPOINT ROLLBACK` next to one that rolls back, and a `WRITEQ TS` queue it `DELETEQ`s on exit |
| `online/FLO050.cbl` | The `XCTL` target of `FLO040`: its `01 DFHCOMMAREA` is longer than the short `COMMAREA` `FLO040` passes, and it tests `EIBCALEN` |
| `online/DFHAID.cpy`, `online/DFHBMSCA.cpy` | Minimal renditions of the CICS-supplied copybooks: the AID values and attribute bytes the programs use |
| `online/トランザクション定義表.csv` | `FL01,FLO010` and `FL02,FLO030` |
| `encoding/FLENC1_UTF8.cbl` | Encoding probe: half-width katakana in DISPLAY literals, full-width Japanese in a VALUE, sequence numbers and a change tag |
| `encoding/FLENC1_SJIS.cbl` | The same program in cp932 |
| `encoding/FLENC1_CP930.cbl` | The same program in IBM930, produced by `tools/GenerateEbcdicSamples.java`. There is no IBM939 rendition: IBM939 has no half-width katakana, so the encoder rejects the master by design |

## Seeded defects

`Where` is the line `expected-findings.tsv` pins. A defect marked "known miss" is written into the
code and left out of `expected-findings.tsv` because no rule reports it; the row says what a rule
would need.

| Id | Where | Rule | Defect |
|---|---|---|---|
| D01 | FLB010:137 | R017 | One READ of the 入金 file whose FILE STATUS is never tested. Every other I/O is followed by `PERFORM 9100-CHECK-STATUS` |
| D02 | FLB010:196 | R003 | `MOVE` of a `S9(09)V99 COMP-3` amount into a `9(07)` print field |
| D03 | FLB010:201 | R016 | `STRING` of two 15-byte names and a separator into a 20-byte field, no `ON OVERFLOW` |
| D04 | FLB010:184 | R005 | `PERFORM VARYING` bounded by the detail count read from the input record, indexing a table of 30 |
| D05 | FLB010:219 | R012 | `PERFORM 2200-RETRY UNTIL WS-RETRY-END = 'Y'` whose body never sets the flag; the outer paragraph sets it before the loop |
| D06 | FLB010:214 | R025 | `IF IN-NYUKIN-GAKU = IN-NYUKIN-GAKU` where the right side should be the previous amount |
| D07 | FLB020:140 | R018 | `SELECT INTO` whose SQLCODE is never tested; `+100` leaves the host variable stale. Reported as WARNING because `WHENEVER SQLERROR GO TO` catches the negative codes |
| D08 | FLB020:172 | R018 | `UPDATE` with no SQLCODE test while `WHENEVER SQLERROR GO TO` is in effect: a negative code is caught, `+100` (no row) is not. WARNING for the same reason |
| D09 | FLB020:260 | R019 | Known miss: `9900-SQL-ERROR` leaves `CSR-KEIYAKU` open; the cursor is closed on the normal path only (line 116). R019 cross-references names, not paths |
| D10 | FLB020:70 | S001 | `SELECT *` in the read-only cursor |
| D11 | FLB020:70 | S002 | `WHERE SUBSTR(KEIYAKU_NO, 1, 3) = :WS-BRANCH-CD` |
| D12 | FLB020:70 | S004 | The read-only cursor declares neither `FOR FETCH ONLY` nor `FOR UPDATE` |
| D13 | FLB020:190 | R029 | `CALL 'FLS010'` with no RETURN-CODE test, while the dynamic CALL at line 242 is followed by one |
| D14 | FLB030:186 | R010 | `ALTER` switching a `GO TO` paragraph |
| D15 | FLB030:169 | R009 | `GO TO` from one SECTION into a paragraph of another |
| D16 | FLB030:135 | R014 | A SECTION that is not the last and does not end in EXIT, GO TO or STOP. Every entry into it is a PERFORM, so the fall-through is a latent trap rather than a live one; this is the finding that moved R014 to LOW |
| D17 | FLB030:152 | R013 | `EVALUATE` without `WHEN OTHER` |
| D18 | FLB030:63 | R015 | `REDEFINES` longer than the item it redefines |
| D19 | FLB030:150 | R028 | `SUBTRACT ... GIVING` into an unsigned `9(09)` balance |
| D20 | FLB030:164, 165 | R006 | A `PIC 9(03)` DISPLAY-usage subscript, used at two sites; one row each |
| D21 | FLB030:247 | R007 | `GO TO` from outside a `PERFORM ... THRU` range into its middle paragraph |
| D22 | FLB030:218 | R023 | The same paragraph name twice in one SECTION |
| D23 | FLB030:110 | R011 | A paragraph after `STOP RUN` that nothing performs or branches to |
| D24 | FLB030:174 | R004 | `COMPUTE WS-HEIKIN = WS-GOKEI / WS-KENSU` where the count starts at zero. R004 reports the missing `ON SIZE ERROR`, which is also what catches the zero divisor; its message names the overflow because the divisor's interval (E5) is not modelled. The guarded copy at line 176 draws nothing |
| D25 | FLB040:126 | R001 | An item without VALUE, set in one EVALUATE branch, read after the EVALUATE |
| D26 | FLB040:56 | R002 | A WORKING-STORAGE item nothing references |
| D27 | FLB040:45 | R024 | `COPY FLC030 REPLACING ==XX-== BY ==WS-==`: pseudo-text matches whole text words, so `XX-` never matches `XX-KEIYAKU-NO`; the code goes on using the `XX-` names |
| D28 | FLB040:62 | R026 | `05 WS-FTP-PASSWORD PIC X(08) VALUE 'ftp#2024'` |
| D29 | FLB040:139 | R027 | `DISPLAY` of an item named `WS-CARD-NO` in an error message |
| D30 | FLB040:90 | R034 | `MOVE WS-入力金額X TO WS-入力金額`, alphanumeric input to a numeric item with no `NUMERIC` class test (S0C7 on bad input). The correct idiom on the next line tests `IS NUMERIC` first |
| D31 | FLJ010:46 | R032 | `DISP=(NEW,CATLG)` on a new dataset: an abend leaves it catalogued and the rerun fails on the duplicate |
| D32 | FLJ010:65 | R030 | `STEP050`, a non-first step with neither `COND=` nor an enclosing `IF` |
| D33 | FLJ020:29 | R048 | `PGM=FLB0301` resolves to no program in the folder: the node carries `external=true` |
| D34 | FLO010:72 | R021 | `SEND MAP('FLM01')` with neither RESP nor NOHANDLE and no HANDLE CONDITION before it. Reported at the `END-EXEC` line |
| D35 | FLO010:134 | R021 | `WRITEQ TS` with `RESP(WS-RESP)` that nothing tests before the next CICS command. Reported at the `END-EXEC` line |
| D36 | FLO010:190 | R031 | `SEND MAP('FLM03') MAPSET('FLM010')`: the mapset has no map `FLM03`; the call graph marks the map node `undefined=true` |
| D37 | FLO010:163 | R033 | `MOVE SPACES TO MEI11O`, a field of the symbolic map copybook (`FLM010.cpy`) that the BMS map (`FLM010.bms`) does not define (the copybook is stale) |
| D38 | FLB010:240 | R011 | Not seeded: `2100-EDIT-EXIT`, an EXIT paragraph that no `PERFORM ... THRU` names. `2100-EDIT-REC` is performed alone and returns at its own end, so the paragraph never runs. It surfaced once R011 stopped flowing out of the SORT output procedure's last paragraph, and stays as a defect because it is one |
| D39 | FLB020:110 | R039 | Not seeded: `CSR-KEIYAKU` is declared without `WITH HOLD` (line 70) and `2900-COMMIT-CHECK` commits every 500 rows inside its fetch loop. Db2 closes the cursor at the commit, so the next `FETCH` fails with SQLCODE -501. Surfaced by R039 |
| D40 | FLB020:202 | R039 | Not seeded: `CSR-UPD` (declared at line 78, `FOR UPDATE OF`) has the same defect; `3200-UPD-ZANDAKA` performs the same commit paragraph |
| D41 | FLB020:120 | R037 | Not seeded: `FETCH CSR-KEIYAKU INTO :DCLKEIYAKU` takes the whole row of `FLDB.KEIYAKU` into the DCLGEN structure, and `SHORI_KBN` and `KOSHIN_YMD` carry no `NOT NULL` in `FLD010`. A NULL row fails the FETCH with SQLCODE -305. The `SELECT INTO` at line 136 is the correct idiom, with an indicator on each column |
| D42 | FLB050:95 | R037 | `FETCH CSR-MEISAI` receives `SHORI_KBN` with no indicator, on the same INTO as `KOSHIN_YMD :WS-KOSHIN-YMD-IND`, which is the correct idiom |
| D43 | FLB050:112 | R038 | `SELECT ... INTO :WS-KEIYAKU-NO` where the item is `PIC X(08)` and `KEIYAKU_NO` is `CHAR(10)`. `:WS-KOKYAKU-NO` on the line above receives `CHAR(8)` into `PIC X(08)` |
| D44 | FLB050:83 | R039 | `CSR-MEISAI` is opened without `WITH HOLD` and `2900-COMMIT-CHECK` commits inside its loop. `CSR-KOKYAKU` (line 57) is declared `WITH HOLD` and commits in the same paragraph, drawing nothing |
| D45 | FLB055:85 | R040 | `UPDATE ... WHERE CURRENT OF` inside the fetch loop with no `COMMIT` anywhere in the program: every row lock is held until the step ends and an abend backs the whole run out |
| D46 | FLO010:131 | R045 | Not seeded: `WRITEQ TS QUEUE('FLTSQ001')` that no program in the folder `DELETEQ`s. Surfaced by R045; `FLO040`'s `FLTSQ002` is the correct idiom, deleted on exit |
| D47 | FLO040:50 | R035 | `MOVE DFHCOMMAREA TO WS-COMMAREA` at the top of `0000-MAIN`, before any `EIBCALEN` test; the program dispatches on a status flag carried inside the connection area instead |
| D48 | FLO040:134 | R036 | `XCTL PROGRAM('FLO050') COMMAREA(WS-CA-SHORT) LENGTH(10)`: `FLO050`'s `01 DFHCOMMAREA` is 41 bytes. The error path's `XCTL` at `FLO040:148`, with `LENGTH(LENGTH OF WS-COMMAREA)` (41 bytes), is the correct idiom |
| D49 | FLO040:52 | R043 | `HANDLE ABEND LABEL(9500-ABEND-BAD)`: the label paragraph sends a message and returns without a `SYNCPOINT ROLLBACK`. The later `HANDLE ABEND LABEL(9600-ABEND-OK)`, whose paragraph rolls back before it returns, is the correct idiom |
| D50 | FLO040:91 | R044 | `RECEIVE MAP('FLM01')` whose `RESP` is compared only with `DFHRESP(NORMAL)`. The second `RECEIVE MAP('FLM02')`, covered by an intervening `HANDLE CONDITION MAPFAIL`, is the correct idiom |
| D51 | FLB010:182 | R049 | Not seeded: `PERFORM VARYING WS-IX FROM 1 BY 1 UNTIL WS-IX > IN-MEISAI-CNT` compares an FD `PIC 9(02)` DISPLAY item straight from the input record, with no `NUMERIC` test anywhere in the program. Surfaced by R049 |
| D52 | FLB060:79 | R034 | `MOVE WS-INPUT-GAKU-X TO WS-INPUT-GAKU`, alphanumeric input to a numeric item with no `NUMERIC` class test, next to the tested `WS-INPUT-KENSU-X` two lines below |
| D53 | FLB060:85 | R042 | `MOVE WS-NAME(20:5) TO WS-NAME-OVER`, a part reference past the 20-byte `WS-NAME`, next to the in-range `WS-NAME(1:5)` above it |
| D54 | FLB060:99 | R049 | `ADD IN-KINGAKU TO WS-GOKEI-GAKU`, an FD `PIC 9(07)` DISPLAY amount with no `NUMERIC` test, next to the tested `IN-KENSU` two lines below |
| D55 | FLB060:112 | R041 | `CALL 'FLS030' USING WS-SHORT-AREA` (10 bytes) where `FLS030`'s `01 LK-DATA-AREA` is 20 bytes, next to the correctly sized `CALL 'FLS010' USING WS-ERR-AREA` (72 bytes on both sides) above it |
| D56 | FLJ030:19 | R047 | `STEP020`'s `COND=(4,LT,STEP999)` names `STEP999`, a step no earlier step of the job defines |
| D57 | FLJ030:28 | R046 | `STEP040`'s own `COND=(0,EQ)`, the job's last step: bypassed whenever the preceding steps all end with return code 0, so it never runs on a clean pass |

Correct idioms placed next to the defects, which must draw no finding: a READ followed by
`PERFORM 9100-CHECK-STATUS` that tests the status item; `STRING ... ON OVERFLOW`; a subscript
bounded by a `VALUE 30` limit; a `PERFORM UNTIL` whose body sets its flag; `SELECT INTO`
followed by `EVALUATE SQLCODE WHEN 0 ... WHEN +100 ... WHEN OTHER`; a `FETCH` loop on
`SQLCODE`; `UPDATE ... WHERE CURRENT OF` followed by an SQLCODE test; a cursor `FOR UPDATE OF`;
a CALL followed by a RETURN-CODE test; the same paragraph name in two different SECTIONs; a
`GO TO` that stays inside its SECTION; `EVALUATE ... WHEN OTHER`; steps guarded by `IF ... THEN`;
`DISP=(NEW,CATLG,DELETE)` and `DISP=(,CATLG,DELETE)`; `RECEIVE MAP` under `HANDLE CONDITION
MAPFAIL`; `READ` whose RESP is tested with `DFHRESP(NOTFND)`; `SEND TEXT ... NOHANDLE`; a LINK
target ending in GOBACK; an XCTL target ending in a plain `EXEC CICS RETURN`; a cursor declared
`WITH HOLD` whose fetch loop commits; a nullable column received with `:host :ind`; a host variable
whose PICTURE is the one DCLGEN generates for its column; a `MOVE`/`ADD` guarded by an `IS NUMERIC`
test on the item it reads; a part reference within the item's length; a `CALL` whose operand is as
long as the callee's LINKAGE item.

Seven findings of rules that are on by default are not seeded defects and are accepted in
`baseline.tsv`, so precision over all rules on this folder is 57 / 64. Three are R005: FLB010:234,
where the loop exit is a flag set once the subscript passes its limit, and FLB030:164 and 165,
where an `IF WS-IX < 100` guards the increment. The interval analysis does not narrow on a branch
or a flag, the same limit `corpus/baseline.tsv` records for CRP001:163. One is R048 at FLB020:242,
where the dynamic `CALL WS-PGM-NAME` resolves to `FLS020`, a subprogram kept outside the folder on
purpose. The last three are true by the rule's reading rather than false positives: two R052, at
FLB010:21 and :24, because FLJ030 runs FLB010 with a SYSOUT DD and none of the program's data DDs,
and one R053, at FLJ020.jcl:1, because that job restarts from the last run's cut-off on purpose.
R008 and R058 are off by default and are accepted wholesale, so they count in neither figure.

## Call graph

`FieldSamplesAcceptanceTest` pins the whole graph edge by edge: 62 nodes, 77 edges.

- Jobs `FLJ010`, `FLJ020` and `FLJ030`; a step expanded from `FLP010` is named `job.step.procstep`
  (`step:FLJ010.STEP030.STEP1`). The `EXEC FLP010` step itself is not a node. A job's out-edges
  are numbered in execution order even though the expanded steps carry the PROC member's lines.
- Utilities `IDCAMS`, `SORT`, `IEFBR14`, `IKJEFT01`, `IEBGENER` are `EXTERNAL_UTILITY` nodes.
  `STEP040` has two execution edges: one to `IKJEFT01`, which the step does run, and one to
  `program:FLB020`, which the `RUN PROGRAM(FLB020) PLAN(FLPLAN1)` card of its `SYSTSIN DD *` names.
  The second carries `launcher=IKJEFT01` and `plan=FLPLAN1` in its attributes.
- The control cards of a step reach the graph wherever they name something no DD statement does:
  `STEP010`'s `DELETE FLW.D250901.NYUKIN.SORTED` adds a `REFERENCE` edge with `access=DELETE` to
  that data set, which `STEP020` and `STEP050` also use.
- `PGM=FLB0301` (D33) is a `PROGRAM` node with `external=true`. `CALL WS-PGM-NAME` in `FLB020`
  resolves to `FLS020` through the `VALUE` clause (a `CALL/CONSTANT` edge, the linker note names
  the variable); `FLS020` is outside the folder, so it is external too. No `UNRESOLVED` node.
- `LINK PROGRAM('FLO020')` is a `CALL` edge; `XCTL PROGRAM('FLO030')` and
  `RETURN TRANSID('FL01')` are `TRANSACTION_TRANSITION` edges, and the definition table adds
  `transaction:FL01 -> program:FLO010`. `FL02` is in the table but no program names it, so it is
  not a node.
- `SEND MAP('FLM03')` (D36) creates `bmsmap:FLM010.FLM03` with `undefined=true`; the two defined
  maps carry no attribute.
- `FLB050` and `FLB055` run under no job of the folder, so they are `PROGRAM` nodes with no
  incoming edge; each has one `REFERENCE` edge to `db2:FLDB.KEIYAKU`.
- `FLB060` runs under no job either; its static `CALL 'FLS010'` and `CALL 'FLS030'` are two
  `CALL/CONSTANT` edges, both fully resolved in the folder.
- `FLO040` and `FLO050` are `PROGRAM` nodes fully resolved in the folder (no `external` attribute).
  `RETURN TRANSID('FL03')` in `FLO040` adds `transaction:FL03` and the pair of edges
  `program:FLO040 -> transaction:FL03` and `transaction:FL03 -> program:FLO040`, the same shape as
  `FL01`/`FLO010`. The two `XCTL PROGRAM('FLO050')` statements collapse into one
  `TRANSACTION_TRANSITION` edge. `FL04` is in the definition table for `FLO050` but no program
  issues `RETURN TRANSID('FL04')`, so it is not a node, the same as `FL02`. `FLO040`'s two `RECEIVE
  MAP`s add `MAP_REFERENCE` edges to `bmsmap:FLM010.FLM01` and `bmsmap:FLM010.FLM02`; `FLO050`'s
  `SEND MAP('FLM01')` adds one to `bmsmap:FLM010.FLM01`. No new map node: both maps already exist.
- The `//STEP1.NYUKIN DD` override in `FLJ010` is applied, so `STEP030.STEP1` reads
  `FLW.D250901.NYUKIN.SORTED` and not the `FLT.D250901.NYUKIN.DAILY` the PROC names. The daily file
  stays a node: `STEP020` and `FLJ020`'s own `STEP030.STEP1` still read it.
- Every `STEP -> DATASET` edge carries an `access` attribute — `READ`, `WRITE`, `UPDATE`, `CREATE`,
  `DELETE` or `UNKNOWN` — from the FILE-CONTROL of the program the step runs where the DD name
  matches one of its `ASSIGN` clauses, and from the step's control cards and the DD's DISP
  otherwise. `STEP030.STEP1` is the worked case: `FLB010` opens `NYUKIN` for input and `NYUOUT` for
  output, so the two edges read `READ` and `WRITE` whatever the DISP says. Where a step reaches one
  data set through two DDs, reading through one and writing through the other, the edge reads
  `UPDATE`. A `PROGRAM -> DB2_TABLE` edge carries the letters `R`, `C`, `U` and `D` instead; no step
  of this folder names a Db2 table on a control card, so there is no `STEP -> DB2_TABLE` edge here.
  The text block of the acceptance test does not print `access`; two assertions over the written
  database and the linker's own tests pin it.
- `FLJ030`'s four steps each run `FLB010`, a program already resolved in the folder, so R048 draws
  nothing from this job; its `job:FLJ030` and four `step:` nodes and their eight `EXECUTION` edges
  are the only additions to the graph from this fixture.

## Encoding

`FLENC1_UTF8.cbl` is the master. `FLENC1_SJIS.cbl` is its cp932 rendition, detected as
`windows-31j` without an override; `FLENC1_CP930.cbl` is produced by
`tools/GenerateEbcdicSamples.java samples-field/encoding/FLENC1_UTF8.cbl samples-field/encoding CP930`
and is estimated as `x-IBM930`. `decode` gives the same text for all three once the EBCDIC record
padding is trimmed. IBM939 has no half-width katakana, so the master has no IBM939 rendition.

## What the field idioms exposed in the engine

Measured with a spike before the code was written and with a blind triage of the findings
afterwards; each item is fixed in the same change unless marked otherwise.

- `EXEC CICS` other than SEND MAP, RECEIVE MAP, XCTL, LINK, START and RETURN TRANSID was dropped
  from the semantic model, so R021 never saw READ, WRITEQ or SEND TEXT and R022 never saw a plain
  RETURN. `HANDLE CONDITION` was a separate node type the mapper skipped.
- R022 counted LINK targets as pseudo-conversational participants and accepted only RETURN
  TRANSID, so every LINKed subprogram ending in GOBACK was a false positive. A program now
  participates when it talks to the terminal (SEND, RECEIVE, CONVERSE, RETURN TRANSID) or is an
  XCTL/START target; a CALLed subprogram that only issues ASSIGN or reads a queue does not.
- R021 reported a command that a `HANDLE CONDITION` covered or that said `NOHANDLE`, and did not
  see a `RESP` that was received and never tested (D35). A `HANDLE CONDITION` covers a later
  command only when it names `ERROR` or a condition that command raises: `MAPFAIL` says nothing
  about a `WRITEQ`.
- R018 did not cover `SELECT INTO` or `FETCH`, and under `WHENEVER SQLERROR` reported the
  negative-code case the program already handled. WHENEVER is read the way the precompiler reads
  it: the last one before the statement decides, `CONTINUE` cancels an earlier branch, and a
  `WHENEVER NOT FOUND` branch completes the handling of a read.
- `lint` and `report` did not read JCL at all, so R030 had never been evaluated by any command;
  R030 then reported steps that an `IF ... THEN` guarded. A PROC called without COND is reported
  at the call alone, a COND on an inner PROC call covers that PROC's steps, and a step expanded
  from a PROC two jobs call is reported once (R032 likewise).
- A SORT or MERGE `INPUT PROCEDURE`/`OUTPUT PROCEDURE` was not a PERFORM relation, so R011
  reported every statement of those sections as unreachable.
- R011 reported the target of `READ ... AT END GO TO`, the target of `ALTER ... TO PROCEED TO`,
  the paragraph after one a `GO TO` reaches, and every statement of a paragraph it judged
  unreachable, one row each: 19 findings for the one defect (D23). The first fix then let control
  flow out of the last paragraph of a `PERFORM THRU` range or a performed section into whatever
  followed, which hid D38; the range end now returns to its PERFORM. Labels of `HANDLE CONDITION`,
  `HANDLE AID` and `HANDLE ABEND` count as references, and a commented-out `GO TO` inside a
  statement's span does not.
- R002 reported every item of a copybook inside the program that copies it (35 findings) and the
  base of a REDEFINES whose redefinition was referenced. A copybook pulled in below an `01` of
  the program is left alone the same way.
- R012 counted an assignment in the outer paragraph as an update of the inner loop's flag, so it
  missed D05; it now measures the body through the PERFORM relation.
- R028 reported the quotient of two unsigned items and the average of an unsigned accumulator.
- R015 placed a finding on a copybook item at the program's path.
- `EXEC SQL WHENEVER ... GO TO` targets were not references, so R011 reported the error paragraph
  as unused and its statements as unreachable.
- A `DECLARE CURSOR` in WORKING-STORAGE was invisible to `sql-lint`, so S001, S002 and S004 never
  fired on it.
- R024 matched a `:PFX:` pseudo-text with COBOL word boundaries, so `:PFX:-REC` never matched.
- A catalogued PROC member found in the folder was a parse failure ("contains no job card").
- A `COPY` as the last line of a program aborted the mapper ("end must not precede start").
- `scan` aborted on a BMS field without a label; the BMS grammar rejected `PRINT NOGEN` and
  `TERM=3270-2`, and a lexer rule for assembler instructions matched on the first line only.
- The linker typed LINK as a transaction transition, resolved a dynamic CALL from MOVE literals
  only, numbered a job's steps by line so PROC-expanded steps sorted first, and gave a map the
  mapset does not define no marker.
- Not fixed, recorded as known misses above: D09 (R019 follows names, not paths),
  the divisor interval behind D24 (E5), and a `MOVE` into a numeric-edited receiver such as
  `PIC ZZZ,ZZ9` (R003 counts only the `9`s of an edited picture, so FLO010:152 draws nothing).
- Not modelled at the time, now read: the `DD *` in-stream data of a utility step (the program a
  `RUN PROGRAM` card names under `IKJEFT01`, the data sets an IDCAMS card names) and `//step.dd DD`
  overrides of a PROC step.
