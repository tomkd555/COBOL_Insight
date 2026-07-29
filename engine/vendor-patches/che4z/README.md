# Che4z 日本語識別子パッチ

vendor/che4z(Eclipse Che4z COBOL Language Support、tag 2.5.1)に対するパッチ集である。COBOL の利用者定義語(データ名・段落名・コピー句名など)に日本語の文字を使えるようにする。COBOL Insight の解析対象には、日本語のデータ名(例: `WS-検証金額`)を含むソースがある。パッチを当てない Che4z ではこのデータ名が字句解析を通らず Syntax error になるため、パッチを適用したビルドを使う。

## 拡張する文字集合

各レクサの識別子規則(先頭文字と後続文字の両方)に、次の Unicode 範囲を追加する。

| 範囲 | 内容 |
|---|---|
| U+3040-U+309F | ひらがな |
| U+30A0-U+30FF | カタカナ(長音 U+30FC を含む) |
| U+4E00-U+9FFF | CJK 統合漢字 |
| U+FF10-U+FF19 | 全角数字 |
| U+FF21-U+FF3A, U+FF41-U+FF5A | 全角英字 |
| U+FF66-U+FF9F | 半角カナ |

## パッチの対象

`0001-japanese-identifiers.patch`(vendor/che4z リポジトリのルート相対)が次の7ファイルを変更する。

| ファイル | 規則 | 内容 |
|---|---|---|
| server/parser/.../core/CobolLexer.g4 | `IDENTIFIER` | 主レクサの識別子文字集合を拡張 |
| server/engine/.../core/TechnicalLexer.g4 | `IDENTIFIER`, `COPYBOOK_IDENTIFIER` | プリプロセッサ用レクサを拡張 |
| server/engine/.../core/CompilerDirectivesLexer.g4 | `IDENTIFIER` | コンパイラ指示文レクサを拡張 |
| server/engine/.../implicitDialects/cics/CICSLexer.g4 | `WORD_IDENTIFIER`, `COPYBOOK_IDENTIFIER` | CICS 暗黙方言レクサを拡張 |
| server/dialect-daco/.../daco/TechnicalLexer.g4 | `IDENTIFIER`, `COPYBOOK_IDENTIFIER` | DaCo 方言レクサを拡張 |
| server/dialect-idms/.../idms/IdmsTechnicalLexer.g4 | `IDENTIFIER`, `COPYBOOK_IDENTIFIER` | IDMS 方言レクサを拡張 |
| server/engine/.../replacement/ReplacingServiceImpl.java | 置換照合の `Pattern.compile` | `UNICODE_CHARACTER_CLASS` を追加し、COPY REPLACING の照合(`\b` 境界・大文字小文字無視)を日本語識別子でも機能させる |

SQL 系レクサ(Db2SqlLexer.g4・Db2SqlExecLexer.g4)は上流で `\p{Alnum}\p{Other_Letter}` を使っており日本語を受理するため、パッチの対象外である。

## 適用手順

変更を加えていない vendor/che4z に対して次を実行する。

```powershell
pwsh -File engine\vendor-patches\che4z\apply-patches.ps1
```

スクリプトは次を自動で行う。

1. `git apply` によるパッチ適用(適用済みのパッチはスキップする)
2. `vendor\che4z\server` で `mvn -DskipTests install`(全モジュールのビルドとローカルリポジトリへのインストール)
3. `mvn -DskipTests "-Dassembly.skipAssembly=true" install -pl engine`(engine は既定で fat jar が主成果物になるため、依存を同梱しない jar をローカルリポジトリへ入れ直す)

ビルド後、`org.eclipse.lsp.cobol:engine:1.0.0-SNAPSHOT` としてローカル Maven リポジトリから参照できる。

## 既知の限界

- 診断の位置(行・カラム)は文字単位(UTF-16 コード単位)で数える。日本語1文字はカラム1つと数えるため、固定形式の桁判定(72桁など)や表示幅・バイト幅とは一致しない。
- COPY REPLACING の擬似テキスト `==XXXX-==` は IBM 仕様上、単語の部分文字列を置換しないため、この形の置換は成立しない。置換が成立しないことに由来する未定義参照の診断は残る。
