/**
 * ソース本文の表示用復号。engine が SOURCE.codepage へ記録した検出値(または画面の手動指定)を
 * TextDecoder の encoding へ対応付けて復号する。復号は表示専用であり、構文解析・判定は engine CLI が担う。
 *
 * TextDecoder(Electron・Node の全 ICU)が持つのは UTF-8 と shift_jis であり、EBCDIC(CP930/CP939)の
 * 変換器は無い。したがって EBCDIC とコードページ不明はいずれも復号せず unsupported として返し、
 * 画面が表示非対応の旨を示す。
 */

import { isAbsolute, relative, resolve } from "node:path";
import type { SourceTextRequest, SourceTextResult } from "../../shared/engine-api";

/** 読取と実体パス解決に要する最小のファイルシステム。main が fs/promises を束ねて渡す。 */
export interface SourceFileSystem {
  /** 絶対パスのファイルをバイト列として読む。 */
  readBytes(absPath: string): Promise<Uint8Array>;
  /** symlink・接合を解決した実体の絶対パスを返す。存在しないパスでは例外を投げる。 */
  realPath(absPath: string): Promise<string>;
}

/** 復号に用いる TextDecoder の encoding と、画面へ返すコードページ表示名。 */
interface CodepageChoice {
  readonly encoding: string;
  readonly label: string;
}

/** 検出値・手動指定値(大文字小文字と別名の揺れを含む)から復号方法を決める。非対応は null。 */
function resolveCodepage(codepage: string | null): CodepageChoice | null {
  if (codepage === null) {
    return null;
  }
  const normalized = codepage.trim().toUpperCase().replace(/[_\s]/g, "-");
  if (normalized === "UTF-8" || normalized === "UTF8") {
    return { encoding: "utf-8", label: "UTF-8" };
  }
  if (
    normalized === "SHIFT-JIS" ||
    normalized === "SJIS" ||
    normalized === "MS932" ||
    normalized === "CP932" ||
    normalized === "WINDOWS-31J"
  ) {
    return { encoding: "shift_jis", label: "Shift_JIS" };
  }
  return null;
}

/**
 * 資産フォルダ配下に限って読取対象の絶対パスを解決する。相対パスは inputDir 基準で解く。
 * 上位へ抜ける相対パス・別フォルダの絶対パス・inputDir 自身(ファイルではない)は null を返す。
 */
export function resolveWithinInputDir(inputDir: string, path: string): string | null {
  const base = resolve(inputDir);
  const target = isAbsolute(path) ? resolve(path) : resolve(base, path);
  const rel = relative(base, target);
  if (rel === "" || rel.startsWith("..") || isAbsolute(rel)) {
    return null;
  }
  return target;
}

/** 改行(CRLF/CR/LF)で分割し、末尾改行が生む空要素だけを落とす。 */
function splitLines(text: string): string[] {
  const lines = text.split(/\r\n|\n|\r/);
  if (lines.length > 1 && lines[lines.length - 1] === "") {
    lines.pop();
  }
  return lines;
}

/**
 * バイト列をコードページに従って復号する。maxLines を与えた場合は先頭 N 行で打ち切り、
 * 打ち切ったことを truncated で示す。行の区切りは表示のため LF へそろえる。
 */
export function decodeSourceText(
  bytes: Uint8Array,
  codepage: string | null,
  maxLines?: number,
): SourceTextResult {
  const choice = resolveCodepage(codepage);
  if (choice === null) {
    return { text: "", codepage: codepage ?? "不明", truncated: false, unsupported: true };
  }
  const decoded = new TextDecoder(choice.encoding).decode(bytes);
  if (maxLines === undefined) {
    return { text: decoded, codepage: choice.label, truncated: false, unsupported: false };
  }
  const lines = splitLines(decoded);
  const truncated = lines.length > maxLines;
  return {
    text: (truncated ? lines.slice(0, maxLines) : lines).join("\n"),
    codepage: choice.label,
    truncated,
    unsupported: false,
  };
}

/**
 * 資産フォルダ配下のソースを読んで復号する。境界外のパスは読取前に拒み、復号非対応の
 * コードページではファイルへ触れずに unsupported を返す。
 *
 * 許可する基準フォルダ(request.inputDir)は project.inputDir と project.copybookPaths のいずれかで
 * ある。境界は字面の解決だけでなく、symlink・接合を解決した実体パスでも判定する(基準フォルダの
 * 中に外部を指す symlink を置いても抜けられない)。
 */
export async function readSourceText(
  fs: SourceFileSystem,
  request: SourceTextRequest,
): Promise<SourceTextResult> {
  const absPath = resolveWithinInputDir(request.inputDir, request.path);
  if (absPath === null) {
    throw new Error(`資産フォルダの外にあるため読み取れません: ${request.path}`);
  }
  if (resolveCodepage(request.codepage) === null) {
    return {
      text: "",
      codepage: request.codepage ?? "不明",
      truncated: false,
      unsupported: true,
    };
  }
  const [realBase, realTarget] = await Promise.all([
    fs.realPath(resolve(request.inputDir)),
    fs.realPath(absPath),
  ]);
  if (resolveWithinInputDir(realBase, realTarget) === null) {
    throw new Error(`資産フォルダの外にあるため読み取れません: ${request.path}`);
  }
  return decodeSourceText(await fs.readBytes(realTarget), request.codepage, request.maxLines);
}
