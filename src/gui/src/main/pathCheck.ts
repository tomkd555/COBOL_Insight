/**
 * コピー句探索パスなど、利用者が入力したフォルダパスの実在確認。main の fs.stat を注入し、
 * ディレクトリとして存在するかだけを返す。存在しない・アクセスできない・ファイルであるは
 * すべて false であり、呼び手はこれ以上の理由の判別を要さない。
 */

/** fs.stat が返す情報のうち、本処理が用いる部分。 */
export interface DirectoryStat {
  stat(path: string): Promise<{ isDirectory(): boolean }>;
}

/** 指定パスがディレクトリとして存在するか。 */
export async function checkDirectoryExists(fs: DirectoryStat, path: string): Promise<boolean> {
  try {
    const stats = await fs.stat(path);
    return stats.isDirectory();
  } catch {
    return false;
  }
}
