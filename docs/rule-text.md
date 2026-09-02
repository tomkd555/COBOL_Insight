# Rule text

Every user-visible string a rule produces — its name, category, summary,
rationale, detection, remedy, example, finding message, code-flow label and fix
description — is written against the vocabulary below. The reader is a mainframe
engineer who meets the JIS COBOL standard's words every day; a tool that
improvises its own words for those concepts marks itself as foreign.

`RuleTextGlossaryTest` (`src/engine/rules/src/test/.../RuleTextGlossaryTest.java`)
scans the rule sources for the wordings this page retires and fails the build on
any hit. Its `BANNED` array is the enforced list; this page is the reference for
what to write instead. Config-file errors in `RuleSet.java` and `RulesFile.java`
are outside the scan.

## Decisions

- Terminology follows **JIS X 3002** (the JIS COBOL standard). IBM Db2, CICS and
  z/OS Japanese manuals cover what JIS does not define.
- 文体 is **常体** in every field, descriptions and messages alike. No です・ます.
- MOVE sides are 送り出し側項目 / 受け取り側項目 — the JIS 4.188/4.189 stem with
  作用対象 shortened to 項目.
- 変数 does not appear in rule text; the item is a データ項目. The one exception
  is ホスト変数, which is the Db2 term.
- Taint is written out as 外部入力に由来する値; 汚染 does not appear.
- Line references are 「N行」, never 「N行目」.
- The field labels shared by the CLI (`rules` subcommand), the SARIF help text
  and the GUI are 検出する内容 / なぜ問題か / 検出条件 / 直し方 / 該当する例 / 直した例.

## Vocabulary

| Concept | Write | Never write |
|---|---|---|
| MOVE | 転記, 転記する; the statement stays MOVE 文 | 移送 |
| MOVE sides | 送り出し側項目 / 受け取り側項目 | 送信項目 / 受信項目 / 転記元 / 転記先 |
| truncation | 切り捨て, 切り捨てられる | 桁落ち |
| digits | けた in hiragana: 上位けた / 下位けた / けた数 / けたあふれ | 桁, オーバーフロー |
| STRING / UNSTRING overflow | あふれ (ON OVERFLOW) | オーバーフロー |
| I-O status | 入出力状態; the clause stays FILE STATUS 句 | ファイル状態, FILE STATUS 変数 |
| record area | レコード領域 | レコード域 |
| I-O statements | 入出力文 (READ・WRITE・REWRITE・DELETE) | — |
| data item | データ項目 / 基本項目 / 集団項目 | 変数, フィールド |
| table, subscript, index | 表, 添字, 指標 | 配列, テーブル, インデックス (COBOL) |
| reference modification | 部分参照 | 参照修飾 |
| figurative constant, literal | 表意定数, 定数 (文字定数, 数字定数) | 図形定数, リテラル |
| condition-name | 条件名 | 88レベル項目 |
| sections | 作業場所節 / 局所記憶節 / 連絡節 / ファイル節 / 手続き部 | WORKING-STORAGE 節, PROCEDURE DIVISION 内 |
| paragraph / section / procedure | 段落 / 節 / 手続き | パラグラフ / セクション |
| transfer of control | 制御が移る, 制御の移行, 移行先; 制御が到達しない | 制御が届く, 制御が離れる, 流下, 流れ落ちる, 飛び先 |
| fall-through | 次の段落へ制御が移る / 次の節へ制御が移る | フォールスルー |
| dead code | 到達不能コード | デッドコード |
| operand | 作用対象 | オペランド |
| the program checks a status | 検査する (未検査) | 判定する |
| the tool finds a defect | 検出する | 指摘する (指摘 stays the GUI noun for a finding) |
| "may" | 〜得る | 〜可能性がある |
| CALL | 呼び出す, 呼び出し先, 呼び出し元 | コール, 呼出 |
| RETURN-CODE, JCL | 戻りコード, COND パラメーター | 完了コード, リターンコード, COND 句 |
| CICS RESP | 応答コード | 戻りコード (CICS) |
| Db2 | 埋込みSQL文, ホスト変数, SQLCODE / SQLSTATE, 索引 | インデックス (SQL), 動的SQLの文字列 |
| taint | 外部入力に由来する値 | 汚染, 汚染追跡, 汚染源 |
| source text | 原始プログラム | ソース |

## Sentence shapes

- **name** — a noun phrase naming the defect. No 「〜の検出」「〜の回避」「〜の確認」
  「〜からの逸脱」 suffix.
- **summary** — one sentence: 「〜を検出する。」
- **rationale** — the consequence, one or two sentences.
- **detection** — the condition, then the exclusions: 「〜は対象外とする。」
- **remedy** — imperative 常体, one or two sentences. `SarifWriter.helpTextOf`
  concatenates it behind 「直し方: 」, so keep it short.
- **message** — at most three sentences: what happens, with the identifier and
  the line; the consequence; the remedy in one clause. Identifiers, PICTURE
  strings and reserved words stay as written in the source.
- **code-flow label** — a noun phrase or one short clause, no period.
- **fix description** — 「〜を挿入する」「〜を付ける」.
