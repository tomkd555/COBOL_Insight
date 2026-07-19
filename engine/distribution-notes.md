# 配布構成の先行検証記録(M1)

本書は、`05_開発計画.md` §3.1が求める配布検証の先行実施として、jpackage app-imageと
electron-builder+NSISインストーラを実機Windows 11で生成・起動し、配布サイズ・起動時間を
測定した記録である。CLI・GUIともに骨格(スケルトン)段階での計測であり、機能実装が進むと
数値は変わる。M8での最終計測との対比のための基礎値として、本書の数値を用いる。

導入手順書(SmartScreen警告への対処手順を含む)はM8で作成する。SmartScreen挙動の記録は
手動確認事項として本書の対象外とする。

## 測定環境

- OS: Windows 11 Home 10.0.26200
- JDK: Eclipse Temurin 21.0.11+10 (jpackage・jlinkとも同一JDK同梱のものを使用)
- Node.js: v24.14.0 / npm: 11.9.0
- Electron: 33.4.11 / electron-builder: 25.1.8 / TypeScript: 5.9.3

## 1. jpackage app-image(Java CLIエンジン側)

`engine/cli`モジュールに暫定main(`jp.cobolinsight.cli.Main`、版数文字列を標準出力して終了
するのみ)を置き、application プラグインの`installDist`成果物を入力に、Gradleタスク
`:engine:cli:jpackageAppImage`(`engine/cli/build.gradle.kts`に定義)でWindows app-image
(`--type app-image`)を生成する。CLIのため`--win-console`を指定し、コンソール接続の起動
ランチャーにする(既定はGUI用の非コンソールランチャーで、標準出力が失われる)。

`installDist`のlib配下には、engine配下の各実装モジュール(cobol-frontend・jcl-frontend・
sql-frontend・bms-frontend・persistence等)がruntimeOnly依存として収める外部ライブラリ
一式(Che4z COBOL Language Support本体・ANTLR4・ICU4J・lsp4j・Guice・JSqlParser・
sqlite-jdbc等)が同梱される。他モジュールが並行開発中のため、この構成・ライブラリ集合は
今後の実装進捗により変わる。以下の数値は本記録時点のものである。

### 1.1 既定JREを同梱した場合

- ディスクサイズ: 202.14 MB(211,963,174バイト、`COBOLInsight`ディレクトリ配下の合計)
- 起動〜終了の所要時間: `Measure-Command`で3回計測。100.39ms・92.17ms・90.47ms

### 1.2 jlinkでJREを縮小した場合

`jdeps --multi-release 21 --print-module-deps --ignore-missing-deps --recursive`を
`installDist`のlib配下全jarに対して実行し、必要モジュールを`java.base,java.desktop,
java.instrument,java.management,java.naming,java.prefs,java.scripting,java.sql,
jdk.javadoc,jdk.unsupported`と判定した。このモジュール集合で`jlink --add-modules
(上記モジュール) --strip-debug --compress=2`によりカスタムランタイムを作成し、jpackage
の`--runtime-image`に渡してapp-imageを生成した。

- ディスクサイズ: 113.65 MB(119,167,468バイト)。既定JRE同梱と比べて88.49 MB少ない。
- 起動〜終了の所要時間: 3回計測。98.39ms・97.43ms・96.20ms。既定JRE同梱と比べ計測誤差の
  範囲内で有意差はない。

ディスクサイズの縮小効果は明確である一方、起動時間はいずれの方式でも実測値が100ms前後に
収まり、有意な差は認められない。

## 2. electron-builder + NSIS(GUI側)

`gui/`にElectron + TypeScriptの最小アプリを作成した。`gui/src/main.ts`がBrowserWindow
(800x600)を1枚開き、`gui/index.html`が製品名「COBOL Insight」の見出しを表示するのみで、
React画面本体はM8で導入する。`gui/package.json`の`build`フィールドにelectron-builder設定
を置き、`win.target`を`nsis`にした。署名なし配布のため、コード署名証明書は設定していない。

electron-builderは既定で、Windows実行ファイルへのバージョン情報書き込み(rcedit)後に
signtool.exeによる署名試行を行う。この署名試行は証明書未設定であれば実際の署名処理を
行わずスキップされるが、signtool.exe自体の格納パスを解決するために`winCodeSign`パッケージ
(macOS向けdarwinライブラリを含む)のダウンロード・展開を要求する。この展開がWindows上で
シンボリックリンク作成の特権(SeCreateSymbolicLinkPrivilege。開発者モード未有効・非管理者
権限では付与されない)を要求し、本検証環境では展開に失敗しビルドが止まった。回避策として
`gui/package.json`の`build.win.signAndEditExecutable`を`false`にし、rcedit・署名試行の
両方をスキップした。これにより`winCodeSign`のダウンロードが発生しなくなり、ビルドが成功
した。実行ファイルのバージョン情報(製品名・説明等)は未設定のままになるため、この点はM8で
コード署名を導入する際にあわせて見直す。

インストールは`/S`(NSISのサイレントインストールオプション)と`/D=<インストール先>`
(インストール先を明示指定。管理者権限を要さないユーザー単位インストール)で行った。

### 2.1 インストーラのファイルサイズ

- `COBOL Insight Setup 0.1.0.exe`: 81,694,729バイト(77.90 MB)

### 2.2 インストール先のディスクサイズ

- サイレントインストール後の合計: 281,236,664バイト(268.21 MB)

### 2.3 インストール後アプリの起動時間

計測方法: `Start-Process`でインストール後の`COBOL Insight.exe`を起動し、起動直後から
`Process.MainWindowHandle`がゼロでなくなる(メインウィンドウが生成される)までの経過時間
を`Stopwatch`で計測した。3回連続実行した。

- 1回目(インストール直後の初回起動): 5,216.64ms
- 2回目: 140.44ms
- 3回目: 140.06ms

初回起動はディスクキャッシュが温まっていないため大きく遅く、2回目以降はおよそ140msで
安定する。M8での最終計測でも、初回起動と2回目以降を分けて記録する。

## 3. 遭遇した問題と回避策

- jpackage app-imageは既定でGUI用の非コンソールランチャーを生成し、標準出力が失われる。
  CLIとして標準出力を使うため`--win-console`を指定した。
- electron-builderのWindows向けビルドは、コード署名証明書が未設定でも`winCodeSign`
  パッケージの展開を要求し、非管理者・開発者モード未有効のWindows環境ではシンボリックリンク
  作成権限がなく展開に失敗する。`win.signAndEditExecutable: false`でrcedit・署名試行の
  両方をスキップして回避した。
- Gradleのマルチプロジェクト構成では、依存先プロジェクト(`engine:cobol-frontend`)が
  `mavenLocal()`に限定して宣言した外部依存(`org.eclipse.lsp.cobol:engine`、Che4z COBOL
  Language Support本体)は、依存元プロジェクト(`engine:cli`)が同じリポジトリ宣言を持たない
  限り、依存元の実行時クラスパス解決で「見つからない」扱いになる。`engine/cli/build.gradle.kts`
  に同内容の`mavenLocal`宣言を追加して解決した。

## 4. 残る課題

- SmartScreen警告への対処手順は手動確認事項として本書の対象外であり、M8の導入手順書側で
  扱う。
- `win.signAndEditExecutable: false`により、実行ファイルのバージョン情報(製品名・説明・
  発行者等)が未設定のままになっている。コード署名を導入するM8で、rcedit・署名の扱いを
  再設計する必要がある。
- jlinkのモジュール集合(`java.base,java.desktop,java.instrument,java.management,
  java.naming,java.prefs,java.scripting,java.sql,jdk.javadoc,jdk.unsupported`)は
  骨格main時点の`jdeps`解析に基づく暫定値であり、CLIサブコマンド群の本実装が入った時点で
  `jdeps`を再実行し見直す必要がある。
- 本検証時点では`engine`配下の複数モジュールが並行して開発中であり、`gradlew build`の
  全体成功は本タスクの対象外(`engine/cli`・`gui/`・本書のみが対象)である。実際に検証中も
  他モジュール(`engine-api`・`engine:cobol-frontend`)の一時的なコンパイルエラーに複数回
  遭遇したが、いずれも他モジュール側の編集中の状態によるものであり、`engine/cli`側の変更
  では再現しなかった。
