# Expected results (ground truth)

This document is the ground truth for the mock assets created under `samples/`. It records
the list of injected defects and the ground truth for the call relationships among the JCL,
programs, subroutines, and datasets. It is used by the analysis engine's automated tests to
compare detection results against the content of this document.

All line numbers count the first line of the target file as line 1. The 1st-6th columns
(the sequence number area) of COBOL source lines are blank; the actual code (the body from
column 8 onward, excluding the indicator area in column 7) is written as-is on each line.

## 1. Asset composition

There are 23 assets under analysis. The breakdown is: 4 JCL, 15 COBOL programs (11 under
`cobol/` and 4 under `encoding/`), 3 copybooks, and 1 BMS map. The transaction definition
table under `cics/` and this document itself are not source, so they are not included in
this count. Scanning does not depend on folder shape; the type is determined from the
source content.

### 1.1 JCL (samples/jcl/)

| File | Content |
|---|---|
| SYKD010.jcl | Order data validation and registration, daily batch (STEP010: SYK001, STEP020: SYK002) |
| SYKD020.jcl | Stock update and allocation confirmation, daily batch (STEP010: SYK006, STEP020: SYK007 via the in-stream PROC SYKPRC01) |
| SYKD030.jcl | Order data error-record reprocessing batch (for reruns. STEP010: SYK001, STEP020: SYK002) |
| SYKD040.jcl | Stock-summary recalculation batch (STEP010: SYK010, STEP020: SYK011, STEP030: the in-stream PROC SYKPRC02, STEP040: the PROC SYKPRC99, whose member is not in the folder). Carries the seeded JCL defects of R050, R051 and R053 |

### 1.2 COBOL (samples/cobol/)

| File | Kind | Content |
|---|---|---|
| SYK001.cbl | Main batch | Order data validation (QSAM I/O) |
| SYK002.cbl | Main batch | Order data registration (VSAM KSDS I/O) |
| SYK003.cbl | Subprogram | Order line-item checksum validation (statically CALLed) |
| SYK004.cbl | Subprogram | Stock allocation determination (dynamically CALLed) |
| SYK005.cbl | Subprogram | Common message formatting and log output (statically CALLed) |
| SYK006.cbl | Db2 program | Stock master update (cursor, SELECT INTO, UPDATE, INSERT) |
| SYK007.cbl | Db2 program | Stock allocation rate calculation and allocation quantity determination (SELECT INTO, UPDATE) |
| SYK008.cbl | CICS program | Order number input screen processing (pseudo-conversation; RECEIVE MAP/SEND MAP of map SYKM01, XCTL to SYK009) |
| SYK009.cbl | CICS program | XCTL target from SYK008 (confirmation message formatting) |
| SYK010.cbl | Db2 program | Stock-summary query and update (DECLARE TABLE, SELECT INTO, UPDATE, INSERT). Writes the processing log to SYKLOG, whose DD statement SYKD040 STEP010 omits |
| SYK011.cbl | Db2 program | Stock-summary cursor processing. Five cursors, one per cursor-order defect |

SYK008.cbl and SYK009.cbl are online programs started as CICS transactions, and are not
executed as steps of a batch JCL. Consequently, there is no JCL corresponding to either
program.

### 1.3 Copybooks (samples/copybook/)

| File | Content |
|---|---|
| SYKCPY1.cpy | Order line-item record (includes REDEFINES, OCCURS, COMP-3, and 88-level items) |
| SYKCPY2.cpy | Order master record (for VSAM KSDS) |
| SYKCPY3.cpy | Stock extract record (intermediate file between SYK006 and SYK007) |

### 1.4 Character-encoding verification (samples/encoding/)

| File | Content |
|---|---|
| SYKENC1_UTF8.cbl | Character-encoding verification sample (UTF-8) |
| SYKENC1_SJIS.cbl | Shift_JIS version of the same content |
| SYKENC1_CP930.cbl | EBCDIC version (IBM930, katakana SBCS + kanji DBCS) of the same content. Line separator is NEL (0x15) |
| SYKENC1_CP939.cbl | EBCDIC version (IBM939, lowercase-English SBCS + kanji DBCS) of the same content. Line separator is NEL (0x15) |

These 4 files are also included among the COBOL programs under analysis. The `PROGRAM-ID`
is `SYKENC1` in all four, meaning the same program name appears in 4 sources. Because the
call-relationship graph groups nodes by program name, only a single `program:SYKENC1` node
appears. None of the 4 files carries an intentional defect, so no finding from
"2. List of injected defects" applies to them.

How the EBCDIC versions were produced is described in section "7. Character-encoding
verification results" of this document.

### 1.5 BMS map (samples/bms/)

| File | Content |
|---|---|
| SYKMAP1.bms | Definition of mapset SYKMAP1 (map SYKM01, with 2 fields: ORDNO for order-number input and MSG for message display) |

### 1.6 CICS transaction definition (samples/cics/)

| File | Content |
|---|---|
| トランザクション定義表.csv | Table mapping the transaction ID (SYK8) to the program name (SYK008) |

## 2. List of injected defects

22 defect types, 36 instances in total, are listed here; the first 18 were injected when the
folder was written. Counts per type are recorded in "3. Breakdown by defect type". Two further instances (No. 19 and 20) were not injected: they
were found in the injected programs when R018 was widened to SELECT INTO/FETCH and lint began
to read JCL (2026-09), and they are real by the rules' own definitions, so they are listed here.
No. 21 to 36 were injected into SYKD040.jcl, SYK010.cbl and SYK011.cbl together with those
three files, one per check of the JCL and SQL rules R050 to R059 (2026-09). R058 is off by
default and is covered by a unit test of the rules module instead of by a row here.

| No. | File | Line | Defect type | Description |
|---|---|---|---|---|
| 1 | SYK001.cbl | 121 | Reference to uninitialised variable | `WS-検証金額` (declared at line 59, no VALUE clause) is set only when the processing category is new registration (line 109 of 2110-新規登録検証) or correction (line 118 of 2120-訂正検証). When the processing category is cancellation (lines 102-103, CONTINUE only), the variable is left unset yet is referenced unconditionally by the IF statement at line 121. When the first record has the cancellation category, an uninitialised value is compared. |
| 2 | SYK001.cbl | 128 | Truncation on MOVE | `ORD1-受注金額合計` (PIC S9(09)V99 COMP-3) is MOVEd to `WS-印字用金額` (PIC 9(06), unsigned, no decimal part). Not only are the 2 decimal digits lost, but for amounts whose integer part exceeds 6 digits (1,000,000 yen or more) the high-order digits are also truncated. |
| 3 | SYK001.cbl | 114 | Subscript that can exceed OCCURS bounds | The `PERFORM VARYING WS-IDX ... UNTIL WS-IDX > ORD1-明細件数` at lines 112-113 uses the value of `ORD1-明細件数`, read from the input file, as the loop-termination bound without any upper-limit check. Because `ORD1-明細行` is defined with only OCCURS 10, an invalid input record whose `ORD1-明細件数` exceeds 10 causes `ORD1-金額(WS-IDX)` at line 114 to reference outside the table bounds. |
| 4 | SYK001.cbl | 85 | Unchecked file status | `READ ORDIN` (lines 85-88) determines end-of-file only via the AT END clause, and never checks the value of `WS-ORDIN-STATUS` (declared at line 49) for I/O errors other than end-of-file (such as the '9x' status series). |
| 5 | SYK002.cbl | 118 | Truncation on MOVE | `SYK2-受注金額合計` (PIC S9(09)V99 COMP-3) is MOVEd to `WS-金額集計エリア` (PIC 9(05), unsigned, no decimal part). Not only are the 2 decimal digits lost, but amounts whose integer part exceeds 5 digits also have their high-order digits truncated. |
| 6 | SYK002.cbl | 130 | Unchecked file status | `REWRITE SYK2-受注マスタレコード` (line 130) has no INVALID KEY clause, and the value of `WS-MASTER-STATUS` (declared at line 40) is not checked after execution either. Processing continues even if the update fails. |
| 7 | SYK002.cbl | 124 | GO TO into a PERFORM THRU range | The `PERFORM 4000-マスタ更新処理 THRU 4000-マスタ更新処理-EXIT` at line 119 establishes lines 126-133 (from 4000-マスタ更新処理 to 4000-マスタ更新処理-EXIT) as a single execution range. However, `GO TO 4010-マスタ書込` at line 124, inside 9000-緊急再更新処理 (lines 122-124), which lies outside that range, branches directly into the middle of the range (4010-マスタ書込) without passing through the range's entry point (line 127 of 4000-マスタ更新処理). After the GO TO, execution falls straight through 4000-マスタ更新処理-EXIT into 8000-終了処理 (line 135 onward), so the `ADD 1 TO WS-更新件数` at line 120 of 3030-更新登録処理 and the processing that reads the next record are both bypassed before termination runs. |
| 8 | SYK003.cbl | 20 | Unused variable | `WS-旧チェック方式件数` (declared at line 20) is never referenced anywhere in the PROCEDURE DIVISION. |
| 9 | SYK004.cbl | 41 | Reference to uninitialised variable | `WS-在庫残数` (declared at line 18, no VALUE clause) is set, within 1000-在庫確認 (lines 35-38), only when `LK-商品コード` is non-blank (line 37). When called with `LK-商品コード` blank, the variable is left unset yet is compared with `LK-要求数量` by the IF statement at line 41. |
| 10 | SYK004.cbl | 47 | Unused paragraph | 9999-未使用処理 (lines 47-49) is never invoked from any PERFORM or GO TO statement anywhere in the program. |
| 11 | SYK005.cbl | 40 | Unreachable code | The `GOBACK` at line 39, inside 1000-エラーメッセージ編集, immediately returns control from the whole program to the caller. The `DISPLAY 'このメッセージは出力されない'` at the very next line, 40, can never execute. |
| 12 | SYK006.cbl | 119 | Unchecked SQLCODE | After the `EXEC SQL UPDATE` in 2100-在庫数更新 (lines 112-119), the value of SQLCODE is never checked. Processing continues even if the update fails (SQLCODE non-zero). |
| 13 | SYK006.cbl | 139 | Subscript that can exceed OCCURS bounds | 2900-エラー商品登録 (lines 136-142) increments `WS-エラー件数INDEX` by 1 at line 138 without any upper-limit check, then stores into `WS-エラー商品(WS-エラー件数INDEX)` at line 139. Because `WS-エラー商品` is defined with only OCCURS 20 (lines 69-71), if a single run produces 21 or more Db2 SELECT errors on products, the table bounds are exceeded. |
| 14 | SYK007.cbl | 79 | Arithmetic without ON SIZE ERROR | The `COMPUTE WS-引当率 = (SYK3-引当可能数量 * 100) / SYK3-在庫数量` in 2100-引当率計算 (lines 78-80) has no ON SIZE ERROR clause. Neither division by zero (when `SYK3-在庫数量` is 0) nor an overflow beyond the digits of `WS-引当率` (PIC S9(03)V99) can be detected. |
| 15 | SYK007.cbl | 89 | Unchecked SQLCODE | After the `EXEC SQL UPDATE` in 2200-引当数量更新 (lines 82-89), the value of SQLCODE is never checked. Processing continues even if the update fails (SQLCODE non-zero). |
| 16 | SYK008.cbl | 38 | Unchecked CICS response code (RESP/RESP2) | The `EXEC CICS RECEIVE MAP('SYKM01') MAPSET('SYKMAP1') INTO(WS-受注入力マップ)` (lines 34-38) inside 0000-メイン処理 (lines 33-44) has no RESP or RESP2 clause and never checks the response code of the map receive. Even if RECEIVE MAP terminates abnormally, the following 1000-受注番号検査 (lines 46-52) still executes. |
| 17 | SYK008.cbl | 56 | Reference to undefined BMS map | The map name `SYKM99`, referenced by `EXEC CICS SEND MAP('SYKM99') MAPSET('SYKMAP1')` (lines 55-61) inside 2000-エラーメッセージ表示 (lines 54-72), does not match map SYKM01 defined in mapset SYKMAP1 (samples/bms/SYKMAP1.bms). No map SYKM99 exists in that mapset. |
| 18 | SYK009.cbl | 21 | Pseudo-conversation broken by missing CICS RETURN | After control passes to SYK009 via SYK008's `EXEC CICS XCTL PROGRAM('SYK009')` (SYK008.cbl line 76), SYK009 formats a message in 0000-メイン処理 (lines 19-21) and terminates with `GOBACK` at line 21. The program never contains an EXEC CICS RETURN statement, so control for the pseudo-conversational transaction SYK8 is never correctly returned to CICS. |
| 19 | SYK007.cbl | 73 | Unchecked SQLCODE | After the `EXEC SQL SELECT SOKO_NM INTO :HOST-倉庫名` in 2000-在庫照会処理 (lines 69-73), SQLCODE is never checked. When no row matches (SQLCODE +100), `HOST-倉庫名` keeps the value of the previous record and processing continues with it. |
| 20 | SYKD020.jcl | 31 | Missing COND on a later step | STEP020 (line 31) runs the in-stream PROC SYKPRC01 with neither a COND parameter nor an enclosing IF, so it executes even after STEP010 (SYK006) ends abnormally, and SYK007 works on an extract that was never completed. |
| 21 | SYKD040.jcl | 1 | Operator parameter left on the JOB card | The JOB card carries `RESTART=STEP020`, written on its continuation line 2 and reported at the JOB card itself, line 1, which is the position the job model holds. Every step up to STEP020 is skipped, so SYK010 never runs and STEP020 works on the summary rows of the previous run. The level is WARNING: the job runs and does less than the member describes, rather than failing. |
| 22 | SYKD040.jcl | 20 | Undefined reference (symbol) | `PARM='&MODE'` on STEP010 names a symbol that no SET of the job, no PROC default and no system symbol gives a value to. The symbol reaches the step unreplaced and the job ends with a JCL error. |
| 23 | SYKD040.jcl | 23 | Duplicate DD name in one step | STEP010 writes `//SYSOUT DD SYSOUT=*` on line 22 and `//SYSOUT DD SYSOUT=A` on line 23. The program opens the first of the two, so the class A the second line asks for is never used. |
| 24 | SYKD040.jcl | 31 | Undefined reference (PROC override) | `//BADSTEP.SYSIN DD DUMMY` overrides a step named BADSTEP, which the in-stream PROC SYKPRC02 does not have: its only step is PRTSTEP. The override is left undone. |
| 25 | SYKD040.jcl | 33 | Undefined reference (PROC member) | STEP040 runs `EXEC SYKPRC99`, whose member is in no PROCLIB of the folder. The resolver records the miss on the job (`missingMembers`), the step stays unexpanded, and nothing inside it is analysed. The level is NOTE: the miss marks the edge of what was analysed rather than an error in the JCL. |
| 26 | SYKD040.jcl | 28 | Undefined reference (referback) | `//BACKREF DD DSN=*.STEP999.OUT1` under STEP020 points at a step STEP999 and a DD OUT1 that the job does not define. The dataset name is never resolved. It stands under STEP020 rather than under STEP040, whose PROC member is missing: a reference under a call whose member was never read may be answered by that member, so R050 leaves those alone. |
| 27 | SYK010.cbl | 16 | DD of a SELECT the step does not allocate | `SELECT SYKLOG ASSIGN TO SYKLOG` (line 16) names DD SYKLOG, and STEP010 of SYKD040.jcl allocates STEPLIB and SYSOUT only. The `OPEN OUTPUT SYKLOG` at line 61 fails with an I/O status of 35. |
| 28 | SYK010.cbl | 66 | SELECT INTO not narrowed to one row | The `SELECT ZAIKO_SU INTO :HOST-在庫数量 FROM SYKDB.ZAIKOSHUKEI` in 2000-在庫集計照会 (lines 66-69) carries no WHERE clause, no aggregate and no FETCH FIRST 1 ROW ONLY. A second matching row makes it fail with SQLCODE -811 and leaves the host variable unset. |
| 29 | SYK010.cbl | 76 | UPDATE with no WHERE | The `UPDATE SYKDB.ZAIKOSHUKEI SET HIKIATE_SU = :HOST-引当数量` in 3000-引当数量更新 (lines 76-79) has no WHERE clause, so the allocated quantity of every row of the summary table is overwritten with the one product's value. |
| 30 | SYK010.cbl | 85 | Column outside the table's declaration | The SELECT in 4000-集計年月照会 (lines 85-89) reads `SHUKEI_YMD`, while the DECLARE TABLE at lines 31-37 declares `SHUKEI_YM`. The precompile passes and the BIND fails with SQLCODE -206. |
| 31 | SYK010.cbl | 95 | INSERT with no column list | The INSERT in 5000-集計行追加 (lines 95-100) names no columns, so its five values are matched to the table's columns by position. Adding a column to ZAIKOSHUKEI moves every value one place along. |
| 32 | SYK011.cbl | 51 | Cursor FETCHed without an OPEN | In 2000-準備照会 the `OPEN CSR-JUNBI` at line 49 stands inside the `IF WS-処理区分 = '1'` at line 48, and the FETCH at lines 51-53 runs whatever the branch decided. On the branch that skips the OPEN the FETCH fails with SQLCODE -501. |
| 33 | SYK011.cbl | 73 | Cursor OPENed twice | In 3000-再開照会 CSR-SAIKAI is opened at line 66 and again at line 73 with no CLOSE and no synchronisation point in between. The second OPEN fails with SQLCODE -502. |
| 34 | SYK011.cbl | 77 | Cursor declared and never OPENed | 4000-明細宣言 declares CSR-MEISAI (lines 77-82) and nothing in the program opens it. The query never runs, so the rows it was written to read are never read. |
| 35 | SYK011.cbl | 93 | FETCH after a ROLLBACK | In 5000-取消後照会 the ROLLBACK at line 92 closes every cursor of the unit of work, WITH HOLD included, and the FETCH of CSR-TORIKESHI at lines 93-95 follows it. It fails with SQLCODE -501. |
| 36 | SYK011.cbl | 113 | Positioned UPDATE on a read-only cursor | CSR-KOSHIN is declared FOR READ ONLY (lines 102-107) and the UPDATE at lines 113-117 names `WHERE CURRENT OF CSR-KOSHIN`. It fails with SQLCODE -510. |

## 3. Breakdown by defect type

| Defect type | Count | No. |
|---|---|---|
| Reference to uninitialised variable | 2 | 1, 9 |
| Truncation on MOVE | 2 | 2, 5 |
| Subscript that can exceed OCCURS bounds | 2 | 3, 13 |
| Unchecked file status | 2 | 4, 6 |
| Unchecked SQLCODE | 3 | 12, 15, 19 |
| GO TO into a PERFORM THRU range | 1 | 7 |
| Unreachable code | 1 | 11 |
| Unused variable or paragraph | 2 | 8, 10 |
| Arithmetic without ON SIZE ERROR | 1 | 14 |
| Unchecked CICS response code (RESP/RESP2) | 1 | 16 |
| Reference to undefined BMS map | 1 | 17 |
| Pseudo-conversation broken by missing CICS RETURN | 1 | 18 |
| Missing COND on a later step | 1 | 20 |
| Operator parameter left on the JOB card | 1 | 21 |
| Undefined reference in JCL | 4 | 22, 24, 25, 26 |
| Duplicate DD name in one step | 1 | 23 |
| DD of a SELECT the step does not allocate | 1 | 27 |
| SELECT INTO not narrowed to one row | 1 | 28 |
| UPDATE with no WHERE | 1 | 29 |
| Column outside the table's declaration | 1 | 30 |
| INSERT with no column list | 1 | 31 |
| Cursor used out of order | 5 | 32, 33, 34, 35, 36 |
| Total | 36 | - |

## 4. JCL -> program -> subroutine -> dataset call relationships (ground truth)

The symbolic parameter `&CYCLE` is defined at the top of each JCL by `SET CYCLE=250718`;
the `&CYCLE` portion of the dataset names below is replaced with `250718` at run time
(e.g. `SYKT.D&CYCLE..ORDER.DAILY` -> `SYKT.D250718.ORDER.DAILY`).

### 4.1 SYKD010.jcl (order data validation and registration, daily batch)

- STEP010 (line 14, EXEC PGM=SYK001)
  - Input: `SYKT.D&CYCLE..ORDER.DAILY` (DD name ORDIN, line 16, QSAM)
  - Output: `SYKW.D&CYCLE..ORDER.VALID` (DD name ORDVALID, lines 17-20, QSAM)
  - Output: `SYKW.D&CYCLE..ORDER.ERROR` (DD name ORDERR, lines 21-24, QSAM)
  - SYK001 statically CALLs SYK003 during line-item validation (SYK001.cbl line 110).
- STEP020 (line 27, EXEC PGM=SYK002, COND=(4,LT,STEP010))
  - This step is skipped if STEP010's RC exceeds 4.
  - Input: `SYKW.D&CYCLE..ORDER.VALID` (DD name ORDVALID, line 29) = the same dataset as
    STEP010's output, i.e. a dataset link between steps.
  - Input/output: `SYKV.ORDER.MASTER` (DD name ORDMSTR, line 30, VSAM KSDS, keyed on
    `SYK2-受注番号`)
  - SYK002 dynamically CALLs SYK004 during new registration (SYK002.cbl line 113; the
    program name to CALL is set by MOVEing 'SYK004' into `WS-PROG-NAME` at line 70).

### 4.2 SYKD020.jcl (stock update and allocation confirmation, daily batch)

- In-stream PROC SYKPRC01 (defined at lines 15-20, runs PGM=SYK007)
- STEP010 (line 22, EXEC PGM=SYK006, PARM='&CYCLE')
  - Input: `SYKT.D&CYCLE..STOCK.DAILY` (DD name STKIN, line 24, QSAM)
  - Output: `SYKW.D&CYCLE..STOCK.EXTRACT` (DD name STKEXTR, lines 25-28, QSAM)
  - Performs SELECT INTO, UPDATE, and INSERT against the Db2 table `SYKDB.ZAIKOM`
    (stock master table), driving cursor `SYKZAIKOCUR` (SYK006.cbl lines 145-157) through
    DECLARE, OPEN, FETCH, and CLOSE, and writes the results to STKEXTR.
  - SYK006 statically CALLs SYK005 both when registering an error product and on an
    INSERT error (SYK006.cbl lines 133 and 142).
- STEP020 (line 31, EXEC SYKPRC01,CYCLE=&CYCLE)
  - Runs the in-stream PROC SYKPRC01, which internally starts PGM=SYK007 (line 16).
  - Input: `SYKW.D&CYCLE..STOCK.EXTRACT` (DD name STKEXTR, line 18) = the same dataset as
    STEP010's output, i.e. a dataset link between steps.
  - Performs UPDATE against the Db2 table `SYKDB.ZAIKOM` and SELECT INTO against
    `SYKDB.SOKOM` (warehouse master table).

### 4.3 SYKD030.jcl (order data error-record reprocessing batch, rerun-only)

- STEP010 (line 13, EXEC PGM=SYK001, PARM='&CYCLE,RERUN')
  - Input: `SYKW.D&CYCLE..ORDER.ERROR` (DD name ORDIN, line 15) = the same dataset as
    `SYKW.D&CYCLE..ORDER.ERROR` output by STEP010 of SYKD010.jcl (DD ORDERR at line 21 of
    SYKD010.jcl), i.e. a dataset link across jobs.
  - Output: `SYKW.D&CYCLE..ORDER.RERUN.VALID` (DD name ORDVALID, lines 16-19)
  - Output: `SYKW.D&CYCLE..ORDER.RERUN.ERROR` (DD name ORDERR, lines 20-23)
- STEP020 (line 26, EXEC PGM=SYK002, COND=(4,LT,STEP010))
  - Input: `SYKW.D&CYCLE..ORDER.RERUN.VALID` (DD name ORDVALID, line 28) = the same dataset
    as STEP010's output, i.e. a dataset link between steps.
  - Input/output: `SYKV.ORDER.MASTER` (DD name ORDMSTR, line 29) = the same VSAM KSDS
    master updated by STEP020 of SYKD010.jcl.

### 4.4 SYKD040.jcl (stock-summary recalculation batch)

- In-stream PROC SYKPRC02 (defined at lines 14-18, runs PGM=SYK005)
- STEP010 (line 20, EXEC PGM=SYK010, PARM='&MODE')
  - Allocates STEPLIB (line 21) and SYSOUT (lines 22 and 23) only. DD SYKLOG, which the
    program's SELECT names, is not there: seeded defect No. 27.
  - Performs SELECT INTO, UPDATE and INSERT against the Db2 table `SYKDB.ZAIKOSHUKEI`
    (stock-summary table), which the program also declares with a DECLARE TABLE. The INSERT is
    followed by a GET DIAGNOSTICS rather than by an SQLCODE test (lines 101-103): R018 and the
    declarative `checked-after` form both count that as the check, so no row is expected there
    and `CheckedAfterRuleAcceptanceTest` holds the two to the same lines.
- STEP020 (line 25, EXEC PGM=SYK011, COND=(4,LT,STEP010))
  - Allocates STEPLIB, SYSOUT and BACKREF; SYK011 opens no file.
  - Drives five cursors over `SYKDB.ZAIKOSHUKEI`, one per seeded cursor defect
    (No. 32 to 36).
  - DD BACKREF (line 28) names `*.STEP999.OUT1`, a step the job does not define: seeded
    defect No. 26. Nothing resolves the referback, so the DD names no data set at all and the
    graph draws neither a node nor an edge for it; STEP020 reaches only SYKDB.ZAIKOSHUKEI
    through SYK011. The text the DD was written as is kept in its JCL_DD row, which records
    what the JCL states.
- STEP030 (line 30, EXEC SYKPRC02,CYCLE=&CYCLE,COND=(4,LT,STEP010))
  - Runs the in-stream PROC SYKPRC02, whose step PRTSTEP starts PGM=SYK005 (line 15). The
    expanded step is therefore named STEP030.PRTSTEP.
  - The override on line 31 names a step BADSTEP the PROC does not have: seeded defect
    No. 24.
- STEP040 (line 33, EXEC SYKPRC99,COND=(4,LT,STEP010))
  - The PROC member SYKPRC99 is in no library of the folder, so the step stays unexpanded
    and is no node of the call graph: seeded defect No. 25. It carries no DD of its own,
    because R050 leaves a reference under an unread member alone.

## 5. Program call relationships (CALL) list

| Caller | Call style | Callee | Location |
|---|---|---|---|
| SYK001 | Static CALL (`CALL 'SYK003'`) | SYK003 | SYK001.cbl line 110 |
| SYK002 | Dynamic CALL (`CALL WS-PROG-NAME`, value 'SYK004') | SYK004 | SYK002.cbl line 70 (value set), line 113 (CALL) |
| SYK006 | Static CALL (`CALL 'SYK005'`) | SYK005 | SYK006.cbl lines 133, 142 |

Parameter passing via LINKAGE SECTION and USING exists in each of the subprograms SYK003
(line 26), SYK004 (line 29), and SYK005 (line 22).

SYK010 and SYK011 CALL nothing; SYK005 is reached from SYKD040 only as the program of the
PROC step STEP030.PRTSTEP.

## 6. Copybook usage

| Copybook | Using program | REPLACING | Location |
|---|---|---|---|
| SYKCPY1.cpy | SYK001 (FD ORDIN) | `SYK1-` replaced with `ORD1-` | SYK001.cbl line 32 |
| SYKCPY1.cpy | SYK002 (FD ORDVALID) | `SYK1-` replaced with `IN1-` | SYK002.cbl line 31 |
| SYKCPY1.cpy | SYK003 (LINKAGE SECTION) | No replacement | SYK003.cbl line 23 |
| SYKCPY2.cpy | SYK002 (FD ORDMSTR) | No replacement | SYK002.cbl line 35 |
| SYKCPY3.cpy | SYK006 (FD STKEXTR) | No replacement | SYK006.cbl line 40 |
| SYKCPY3.cpy | SYK007 (FD STKEXTR) | No replacement | SYK007.cbl line 25 |

SYKCPY1.cpy itself contains a REDEFINES (line 10, `SYK1-受注日-YMD`), an OCCURS (lines
17-18, `SYK1-明細行 OCCURS 10 TIMES`), COMP-3 items (lines 15, 16, 20, 21, 22), and
88-level items (lines 24-26).

## 7. Character-encoding verification results

### 7.1 UTF-8 and Shift_JIS versions

Taking `samples/encoding/SYKENC1_UTF8.cbl` as the master, it was converted to
`samples/encoding/SYKENC1_SJIS.cbl` from PowerShell using
`[System.Text.Encoding]::GetEncoding(932)`. Decoding the converted file with codepage 932
has been confirmed to reproduce the exact same string as the master (993 characters on the
UTF-8 side versus 1,124 bytes on the Shift_JIS side).

### 7.2 EBCDIC versions (IBM930 / IBM939)

`samples/encoding/SYKENC1_CP930.cbl` and `SYKENC1_CP939.cbl` were generated from the
master UTF-8 version using `tools/GenerateEbcdicSamples.java` (run as
`java tools/GenerateEbcdicSamples.java` from the repository root). They are encoded with
`x-IBM930` and `x-IBM939`, provided by JDK 21's `jdk.charsets` module; kanji and hiragana
are written as DBCS enclosed in SO (0x0E) / SI (0x0F), and line breaks are written as
NEL (0x15). Both files are 1,152 bytes, and `SampleDecodingTest` confirms that decoding
each with its respective code page reproduces the exact same string as the master.

Automatic detection only infers EBCDIC from the byte distribution and cannot distinguish
IBM930 from IBM939 (it returns IBM930 as the candidate in either case). The IBM939 version
must be read by manually specifying the code page.

Support for these was previously deferred because .NET's `System.Text.CodePagesEncodingProvider`
did not offer code pages 930/939, but since the JDK does support them, that policy has been
revised.

## 8. Known incidental findings (not included in the ground-truth defect list)

This section is reference information separate from the 36 intentional defects listed in
"2. List of injected defects". These are things that a strict code analysis could flag,
but they stem from common COBOL practice or from notational properties, and are not
intentional defects.

### 8.1 Variables assigned but never read afterward

| File | Variable | Declaration line | Assignment line |
|---|---|---|---|
| SYK002.cbl | WS-引当可否 | line 51 | line 115 (used as an output parameter of a CALL statement) |
| SYK002.cbl | WS-金額集計エリア | line 53 | line 118 |
| SYK007.cbl | HOST-倉庫名 | line 33 | line 70 (host variable of a SELECT INTO statement) |
| SYK007.cbl | WS-引当率 | line 46 | lines 79-80 (destination of a COMPUTE statement's result) |
| SYK008.cbl | WS-RESP2コード | line 30 | lines 60, 68, 78 (used as the output parameter of the RESP2 clause of EXEC CICS) |
| SYK009.cbl | WS-確認メッセージ | line 16 | line 20 (destination of a MOVE statement) |

A strict liveness analysis could flag these, but they are not intentional defects.

### 8.2 Unreferenced 88-level condition name

`88 WS-マスタ該当あり` at SYK002.cbl line 46 is never referenced anywhere in the
PROCEDURE DIVISION. It is defined as a pair with its counterpart at line 47,
`88 WS-マスタ該当なし` (referenced by the IF statement at line 80), which is common
practice; it is not a defect.

### 8.3 Lines where the closing `*` of the comment frame reaches display column 73-74 on a line containing full-width characters

The header comment frame in each COBOL source follows a style that places a closing `*` at
the right edge of lines containing Japanese text. Only when counting column position by
cp932 byte count do the following lines exceed column 72 (when counted by character count,
every line stays within 72 columns).

| File | Line | Character count | Byte count |
|---|---|---|---|
| SYK001.cbl | 8 | 51 | 73 |
| SYK002.cbl | 8 | 57 | 73 |
| SYK003.cbl | 3 | 51 | 73 |
| SYK003.cbl | 4 | 47 | 74 |
| SYK003.cbl | 5 | 51 | 74 |
| SYK004.cbl | 3 | 57 | 73 |
| SYK004.cbl | 4 | 47 | 74 |
| SYK004.cbl | 5 | 52 | 74 |
| SYK004.cbl | 6 | 61 | 74 |
| SYK005.cbl | 3 | 47 | 73 |
| SYK005.cbl | 4 | 47 | 74 |
| SYK005.cbl | 5 | 57 | 73 |
| SYK006.cbl | 4 | 50 | 73 |
| SYK006.cbl | 5 | 57 | 73 |
| SYK006.cbl | 6 | 54 | 74 |
| SYK006.cbl | 8 | 57 | 74 |
| SYK007.cbl | 4 | 53 | 73 |
| SYK007.cbl | 5 | 51 | 73 |
| SYK007.cbl | 6 | 57 | 74 |
| SYK007.cbl | 7 | 61 | 73 |
| SYKENC1_UTF8.cbl | 3 | 56 | 73 |
| SYKENC1_UTF8.cbl | 4 | 52 | 74 |
| SYKENC1_UTF8.cbl | 5 | 54 | 74 |
| SYKENC1_UTF8.cbl | 6 | 54 | 74 |

### 8.4 READ INTO whose destination is the FD's own record

| File | Line | Content |
|---|---|---|
| SYK001.cbl | line 85 | `READ ORDIN INTO ORD1-受注レコード` (ORD1-受注レコード is FD ORDIN's own record, brought in via the COPY expansion at line 32) |
| SYK002.cbl | line 73 | `READ ORDVALID INTO IN1-受注レコード` (IN1-受注レコード is FD ORDVALID's own record, brought in via the COPY expansion at line 31) |
| SYK006.cbl | line 87 | `READ STKIN INTO STKIN-レコード` (STKIN-レコード is FD STKIN's own record, declared at line 29) |

This is a form where the FD being read INTOs its own record area again, which is common
practice; it is not an intentional defect.

## 9. CICS asset call relationships

SYKMAP1.bms under samples/bms/, SYK008.cbl and SYK009.cbl under samples/cobol/, and
トランザクション定義表.csv under samples/cics/ are assets that model CICS pseudo-conversational
processing. SYK008 and SYK009 are started as CICS transactions and therefore have no
corresponding JCL (see "1.2 COBOL (samples/cobol/)"). The call relationships between the
two programs are recorded in this section.

### 9.1 BMS map composition

samples/bms/SYKMAP1.bms defines mapset SYKMAP1 (DFHMSD, lines 7-11). Mapset SYKMAP1 holds
a single map, SYKM01 (DFHMDI, line 13). Map SYKM01 has 2 fields: ORDNO for order-number
input (DFHMDF, POS=(3,10), LENGTH=8, ATTRB=(UNPROT,NUM), lines 15-17) and MSG for message
display (DFHMDF, POS=(22,5), LENGTH=40, ATTRB=(PROT,BRT), lines 19-21). No map SYKM99 is
defined in mapset SYKMAP1.

### 9.2 Call relationships of SYK008 and SYK009

| Caller | Call style | Callee / reference | Location |
|---|---|---|---|
| SYK008 | EXEC CICS RECEIVE MAP('SYKM01') MAPSET('SYKMAP1') | Map SYKM01 (mapset SYKMAP1) | SYK008.cbl lines 34-38 |
| SYK008 | EXEC CICS SEND MAP('SYKM99') MAPSET('SYKMAP1') | Map SYKM99 (not defined in mapset SYKMAP1; this is defect No. 17 in "2. List of injected defects") | SYK008.cbl lines 55-61 |
| SYK008 | EXEC CICS XCTL PROGRAM('SYK009') | SYK009 | SYK008.cbl lines 75-79 (XCTL at line 76) |
| SYK008 | EXEC CICS RETURN TRANSID('SYK8') | Transaction SYK8 (per the mapping in samples/cics/トランザクション定義表.csv, control passes to SYK008 the next time SYK8 is started) | SYK008.cbl lines 65-69 (RETURN at line 66) |

samples/cics/トランザクション定義表.csv maps transaction ID `SYK8` to program name `SYK008` in a
single row. Because of this mapping, `EXEC CICS RETURN TRANSID('SYK8')` at SYK008.cbl line
66 passes control back to SYK008 the next time transaction SYK8 is started, continuing the
pseudo-conversation.
