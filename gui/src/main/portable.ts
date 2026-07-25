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
