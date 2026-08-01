/**
 * 資産フォルダ選択ダイアログの純ロジック。Electron の dialog をインターフェースで受け取り、
 * 結果を「選択された1フォルダ」または null(キャンセル)へ正規化する。
 */

/** Electron の dialog.showOpenDialog が返す形のうち、本処理が用いる部分。 */
export interface OpenDialogOutcome {
  canceled: boolean;
  filePaths: string[];
}

/** openDirectory 指定の showOpenDialog を呼ぶ関数。main が Electron の dialog を束ねて渡す。 */
export type OpenDirectoryDialog = () => Promise<OpenDialogOutcome>;

/** フォルダ選択ダイアログを開き、選ばれた1フォルダを返す。キャンセル・未選択は null。 */
export async function selectInputFolder(dialog: OpenDirectoryDialog): Promise<string | null> {
  const outcome = await dialog();
  if (outcome.canceled) {
    return null;
  }
  return outcome.filePaths[0] ?? null;
}
