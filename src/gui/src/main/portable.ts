import { mkdirSync, rmdirSync, unlinkSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";

/** ポータブル運用の保存先解決に要する実行環境情報。 */
export interface PortableUserDataInput {
  /** app.isPackaged。配布物か開発実行かを分ける。 */
  isPackaged: boolean;
  /** app.getPath("exe")。配布物では展開先直下の実行ファイルを指す。 */
  exePath: string;
  /** 保存先を作成し、書込可能かを確かめる。作れなければ false を返す。 */
  ensureWritable: (dir: string) => boolean;
}

/**
 * userData(設定・キャッシュ・ローカルストレージの保存先)を、配布物を展開した位置の直下
 * `data/` へ向ける。展開したフォルダを丸ごと移動・削除すれば痕跡が残らず、導入作業も
 * 利用者プロファイルへの書込も要さない。
 *
 * 読み取り専用の共有フォルダから直接起動した場合は `data/` を作れないため null を返し、
 * Electron 既定の保存先(Windows では %APPDATA% 配下)へ委ねる。
 */
export function resolvePortableUserData(input: PortableUserDataInput): string | null {
  if (!input.isPackaged) {
    return null;
  }
  const dataDir = join(dirname(input.exePath), "data");
  return input.ensureWritable(dataDir) ? dataDir : null;
}

/**
 * 保存先ディレクトリを作成し、書込可能かを確かめる。Windows の ACL を fs.access は
 * 評価せず、書込を禁じられた場所でも成功を返すため、試し書きで判定する。
 * 副作用として、判定に失敗したときは、この関数が作成したディレクトリを取り除く
 * (親ディレクトリまではさかのぼらない)。
 */
export function ensureWritable(dir: string): boolean {
  // 同一フォルダからの二重起動で試し書きが衝突しないよう、プロセスごとに名前を分ける。
  const probe = join(dir, `.write-probe-${process.pid}`);
  let created = false;
  try {
    created = mkdirSync(dir, { recursive: true }) !== undefined;
    writeFileSync(probe, "");
  } catch (error) {
    console.warn("[main] 保存先への試し書きに失敗した。既定の保存先を使う", dir, error);
    if (created) {
      try {
        rmdirSync(dir);
      } catch {
        // 片付けの成否は判定に影響しない。
      }
    }
    return false;
  }
  try {
    unlinkSync(probe);
  } catch {
    // 試し書きを消せなくても、書込可能である判定は変わらない。
  }
  return true;
}
