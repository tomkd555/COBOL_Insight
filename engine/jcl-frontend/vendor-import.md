# MAPA 取込記録

## 取込元

- リポジトリ内の `vendor/mapa`(MIT License、commit 762a2adb)の `jcl/src` ディレクトリ。
- `vendor/mapa` は読み取り専用の参照元であり、改変しない。ローカルパッチはすべて本モジュールへの複製に対して当てる。

## 取込範囲

- 手書き Java: `jcl/src` にある手書きの 50 ファイルのうち 48 ファイルを
  `src/main/java/jp/cobolinsight/jclfrontend/mapa/` へ複製する。
  - 除外: `Demo01.java`(CLI デモ。同じパース処理は `jp.cobolinsight.jclfrontend.JclFrontend` が実装する)、
    `TheCLI.java`(commons-cli 依存。下記の置き換え版を本モジュールで実装する)。
- ANTLR 文法: `JCLLexer.g4` `JCLParser.g4` `JCLPPLexer.g4` `JCLPPParser.g4` `DSNTSOLexer.g4`
  `DSNTSOParser.g4` `TSOLexer.g4` `TSOParser.g4` `JCLDDAMPLexer.g4` `JCLDDAMPParser.g4` の 10 ファイルを
  `src/main/antlr/` へ複製する。
  - 除外: `JCLNotifyWhenLexer.g4` `JCLNotifyWhenParser.g4`(上流 Makefile の `all` ターゲットに含まれず未使用)。
- 上流の生成物(`*.tokens` `*.interp` と ANTLR 生成 Java)は複製せず、Gradle の antlr プラグイン
  (ANTLR 4.13.2、`-package jp.cobolinsight.jclfrontend.mapa` 指定)でビルド時に生成する。

## 複製に対する改変点

1. 全 48 Java ファイル: 先頭に `package jp.cobolinsight.jclfrontend.mapa;` を付与する。
   上流の著作権表示コメントは 2 行目以降にそのまま保持する。
2. `TheCLI.java`: commons-cli によるコマンドライン解析を持たない同名・同フィールドのクラスとして
   本モジュールで実装する(取込元の複製ではない)。取り込んだクラス群が参照するフィールド
   (`staticProcPaths` `mappedProcPaths` `mappedCntlPaths` `saveTemp` `PPsetSym` `setSym` ほか)と
   ユーティリティメソッド(`newTempDir` `setPosixAttributes` `getSanity` `lookForPPSetSymbols`
   `lookForSetSymbols`)は上流と同じ意味を保つ。
3. `PPSingleOrMultipleValueWrapper.java`: `Demo01.LOGGER` への参照 1 箇所を `this.LOGGER` に変更する
   (`Demo01` は取込対象外のため)。
4. `JCLPPLexer.g4` / `JCLLexer.g4`(上流欠陥へのローカルパッチ): `NEWLINE` と `QS_NEWLINE` を
   `[\n\r]`(1 文字)から `('\r'? '\n' | '\r')` に変更し、CRLF を 1 トークンで消費する。
   上流定義では CRLF の CR だけを消費して継続用レクサモード
   (`COMMA_NEWLINE_MODE` `QS_SS_MODE` など。改行規則を持たない)へ遷移し、残った LF が
   ERROR_CHAR トークンになって `jobCard` 規則や引用文字列の行継続を打ち切る。
   この欠陥により、CRLF 環境では、JOB カードが 2 行に継続する JCL でジョブ終了行が
   1 行目に確定し、後続の SET/EXEC/DD が範囲外になる。
   MAPA 自身が PrintWriter.println で書き出す中間ファイルは、Windows では常に CRLF である。
5. `PPJclStep.java`(上流欠陥へのローカルパッチ): `resolveParms` の EXEC PROC 分岐で、
   EXEC 文の実引数(`this.setSym`。例: `CYCLE=&CYCLE`)自体のシンボリックを、受け取った
   SET 値で解決する処理を追加する。上流はこの解決を行わないため、PROC 内 DD の DSN へ
   `&CYCLE` が文字どおり置換される。

## モジュール入口

- `jp.cobolinsight.jclfrontend.JclFrontend#parse(Path, Map<String, String>, List<String>)` が
  ジョブ→ステップ→PGM/PROC→DD(解決済み DSN)の結果モデル
  (`JclParseResult` `ParsedJob` `ParsedStep` `ParsedDd`)を返す。
- `jp.cobolinsight.jclfrontend.MapaJclParser` が engine-api の SPI
  `jp.cobolinsight.engineapi.spi.JclParser` を実装し、上記の結果モデルを
  `JclJobModel`(EXEC PROC は「ステップ名.PROC内ステップ名」で平坦化)へ変換する。
