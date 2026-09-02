# Rule text

Every user-visible string a rule produces — its name, category, summary,
rationale, detection, remedy, example, finding message, code-flow label and fix
description — is written against the vocabulary below. The reader is a mainframe
engineer who meets the JIS COBOL standard's words every day; a tool that
improvises its own words for those concepts marks itself as foreign.

`RuleTextGlossaryTest` (`src/engine/rules/src/test/.../RuleTextGlossaryTest.java`)
scans the whole `rules` module and the `cli` and `pipeline` packages of the `app`
module for the wordings this page retires, and for a 常体 verb ending before 「。」,
and fails the build on any hit. Its `BANNED` list is the enforced list; this page is
the reference for what to write instead. Text with no 「。」 — picocli option and
subcommand descriptions — is not checked for register: those follow javac's help
and stay in the dictionary form (「〜を書き出す」), because they describe a command
rather than diagnose a program.

## Decisions

- Terminology follows **JIS X 3002** (the JIS COBOL standard). IBM Db2, CICS and
  z/OS Japanese manuals cover what JIS does not define.
- 文体 is **敬体** in every field, descriptions, messages and CLI output alike, in
  the shape Japanese compiler diagnostics use (see "Register" below). The 常体
  house rule that held until 2026-09-02 is retired.
- Column positions of the fixed format are **桁** (「8〜72桁」「第 7 桁」), as IBM's
  Japanese manuals write them; digit counts stay **けた** (けた数, けたあふれ).
- A section is named as the source writes it in a message (「WORKING-STORAGE
  SECTION」, because the reader searches for that text) and by its JIS word in
  prose (作業場所節).
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
| digits | けた in hiragana: 上位けた / 下位けた / けた数 / けたあふれ | 桁数, 桁あふれ, オーバーフロー |
| column position | 桁 after a number: 8〜72桁, 第 7 桁, 73桁以降 | けた for a column, カラム |
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
| RETURN-CODE, JCL | 戻りコード, COND パラメーター | 完了コード, リターンコード, 条件コード, COND 句 |
| CICS RESP | 応答コード | 戻りコード (CICS) |
| Db2 | 埋込みSQL文, 動的SQL文, ホスト変数, SQLCODE / SQLSTATE, 索引, オプティマイザー | インデックス (SQL), 動的SQLの文字列, 最適化器 |
| taint | 外部入力に由来する値 | 汚染, 汚染追跡, 汚染源 |
| source text | 原始プログラム | ソース |

## Register

文体 is 敬体 in every field, in the shape Japanese compiler diagnostics use
(javac 「シンボルを見つけられません」, MSVC 「'x': 定義されていない識別子です。」,
GCC 「'x' が宣言されていません」, Enterprise COBOL 「"X" は定義されていませんでした。」).
The GUI is 敬体 as well, so engine text and chrome read as one voice.

## Sentence shapes

- **name** — a noun phrase naming the defect. No 「〜の検出」「〜の回避」「〜の確認」
  「〜からの逸脱」 suffix. Full-width parentheses; a space between a reserved word
  and kana, as the body text writes it (「ALTER 文」「WHEN OTHER 句」).
- **summary** — one sentence: 「〜を検出します。」
- **rationale** — the consequence, one or two sentences: 「〜ます。」
- **detection** — the condition, then the exclusions: 「〜を検出します。〜は対象外です。」
  It names what the reader can check by hand, never the module, the signal
  name or the analysis (到達定義解析・区間値域解析・制御フローグラフ).
- **remedy** — one or two sentences in 「〜してください。」. `SarifWriter.helpTextOf`
  concatenates it behind 「直し方: 」, so keep it short.
- **fix description** — 「〜を挿入します」「〜を付けます」.
- **code-flow label** — a noun phrase or one short clause, no 「。」 inside; the
  role in parentheses: 「宣言（VALUE 句がなく初期値は不定）」.

## Message shape

A finding message is a diagnostic, not a paragraph. The problems table shows its
first ~14 full-width characters in the 内容 cell and the whole of it in the detail
pane, with the rule name already displayed beside it and the remedy already
displayed below it. What survives the ellipsis is the message.

- **Headline.** The message opens with the identifier the reader must look for —
  the data item, paragraph, section, cursor, map, JCL step or file name — followed
  by its state in 〜です / 〜ています / 〜されていません / 〜ありません. Nothing precedes
  the identifier: no rule-name restatement (「PERFORM 文が…」), no category prefix
  (「カーソル 」「機密項目 」「符号なし項目 」), no line number, no 「参照する」. Where
  the finding has no identifier, the statement's reserved word stands in its place
  (`MOVE`, `EVALUATE`, `EXEC CICS READ`).
- **Length.** The headline ends at its first 「。」 within 24 full-width units,
  and the identifier is complete within the first 14. A rule that cannot fit its
  identifier there is naming the wrong thing first.
- **Separator.** 「。」 only. No colon-introduced list in the headline; a list of
  matched items goes after the first 「。」 or into the code-flow steps.
- **Sentences.** Two at most: the headline, then the consequence in 「〜ます。」.
- **Never in a message.** The remedy — 直し方 is a field, `SarifWriter.helpTextOf`
  already puts it in the SARIF help text and the GUI shows it under the message.
  Also never: the rule name restated, the analysis that found it, and the reason
  the heuristic is trustworthy.
- **To the code-flow steps instead.** Every intermediate location and its role:
  the declaration and its PICTURE, each statement that sets the item, the point
  where a status is overwritten, the statement control jumps from. The message
  says what is wrong; the steps say along which path.
- **Hedging.** 「〜得ます」 at most once per message. It belongs in the headline
  only when the possibility is the defect itself (a subscript that may leave its
  OCCURS range, a result that may turn negative), as MSVC's C4701 「初期化されて
  いない可能性のあるローカル変数」 does; a hedge on the consequence goes to the
  second sentence. A disjunction the analysis could resolve (「超え得るか、0 以下に
  なり得る」) is the analyser talking about itself; pick the branch that matched.
- **Line references.** 「N行」, only where a second location is essential
  (「（宣言 12行）」「（119〜124行）」); the finding's own line is the 行 column.
  One half-width space before the number when it follows kana or kanji, none
  between the number and 行.
- **Column positions** are 桁 (「8〜72桁」「第 7 桁」); digits stay けた
  (けた数, けたあふれ).
