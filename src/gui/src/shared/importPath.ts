/**
 * Where a pasted source lands in the asset folder.
 *
 * The renderer shows the destination from this rule and main checks the request against it before
 * writing, so the dialog offers only names main accepts, and main writes only names the dialog
 * accepts.
 */

/** Characters a file or folder name cannot hold: the path separators and what Windows reserves. */
const FORBIDDEN_IN_NAME = /[\\/:*?"<>|]/;

/**
 * The names Windows keeps for devices. Windows 10 opens the device for such a name even with an
 * extension, and with blanks ahead of the extension, so a write to `NUL.cbl` stores nothing there;
 * Windows 11 stores a file. The names are refused on both. The superscript digits are device
 * numbers too.
 */
const DEVICE_NAME =
  /^(?:CON|PRN|AUX|NUL|CONIN\$|CONOUT\$|COM[0-9¹²³]|LPT[0-9¹²³])\s*(?:\..*)?$/i;

/** The longest name Windows keeps, in UTF-16 code units. */
const MAX_NAME_LENGTH = 255;

/**
 * Whether one name of a path can be used: it holds 1 to 255 characters, none of them reserved or a
 * control character, it is the name of no device, it ends in no dot or space, which Windows drops
 * silently, and it starts with no dot.
 */
function usableName(name: string): boolean {
  return (
    name !== "" &&
    name.length <= MAX_NAME_LENGTH &&
    !FORBIDDEN_IN_NAME.test(name) &&
    !DEVICE_NAME.test(name) &&
    !name.startsWith(".") &&
    !/[. ]$/.test(name) &&
    !Array.from(name).some((char) => (char.codePointAt(0) ?? 0) < 0x20)
  );
}

/**
 * The destination relative to the asset folder, with forward slashes, or null when the folder or
 * the name cannot be used.
 */
export function importRelPath(destDir: string, fileName: string): string | null {
  const name = importFileName(fileName);
  const segments = importDirSegments(destDir);
  return name === null || segments === null ? null : [...segments, name].join("/");
}

/** The file name as it will be written, or null when it cannot be used. */
export function importFileName(fileName: string): string | null {
  const name = fileName.trim();
  return usableName(name) ? name : null;
}

/**
 * The folders of a destination, outermost first, or null where the destination cannot be used: a
 * path that starts at a root or a drive, or a folder name that cannot be used. An empty destination
 * is the asset folder itself. The dialog reads it on its own, so a folder that cannot be used is
 * reported at the folder.
 */
export function importDirSegments(destDir: string): string[] | null {
  const typed = destDir.trim();
  if (/^[\\/]/.test(typed)) {
    return null;
  }
  const segments = typed
    .split(/[\\/]/)
    .map((segment) => segment.trim())
    .filter((segment) => segment !== "");
  return segments.every(usableName) ? segments : null;
}
