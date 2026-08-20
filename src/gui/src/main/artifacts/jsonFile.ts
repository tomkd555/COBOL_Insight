/**
 * userData 直下に置く JSON の設定ファイルの読取。利用者定義ルール・ルールの有効無効・画面の設定は
 * どれも同じ形で読む。
 *
 * **読めない設定ファイルで画面を止めない**。未作成・空・壊れている・読み取りに失敗したのいずれも
 * 既定値を返し、画面の初期値で始める。壊れた中身をどう伝えるかは読取の担当ではない
 * (engine が同じファイルを読んだときの注意として、ルールの一覧が示す)。
 */

/** 設定ファイルの読み書きに使う fs の束ね。テストでは差し替える。 */
export interface JsonFileSystem {
  readText(path: string): Promise<string>;
  writeText(path: string, text: string): Promise<void>;
  exists(path: string): Promise<boolean>;
}

/**
 * JSON の設定ファイルを読み、読めた値を normalize へ通して返す。読めなかったときは empty を返す。
 */
export async function readJsonFile<T>(
  fs: JsonFileSystem,
  path: string,
  normalize: (value: unknown) => T,
  empty: () => T,
): Promise<T> {
  if (!(await fs.exists(path))) {
    return empty();
  }
  try {
    const text = await fs.readText(path);
    if (text.trim() === "") {
      return empty();
    }
    return normalize(JSON.parse(text));
  } catch {
    return empty();
  }
}
