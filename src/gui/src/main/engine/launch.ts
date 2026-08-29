import { join } from "node:path";

/** engine 起動対象の解決に要する実行環境情報。 */
export interface EngineLaunchInput {
  /** app.isPackaged。配布物か開発実行かを分ける。 */
  isPackaged: boolean;
  platform: NodeJS.Platform;
  /** process.resourcesPath。配布時に同梱物を解決する起点。 */
  resourcesPath: string;
  /** app.getAppPath()。開発時に installDist を解決する起点(gui/ を指す)。 */
  appRoot: string;
  /** process.env.JAVA_HOME。開発時に java を解決する。未設定なら PATH 上の java を使う。 */
  javaHome?: string;
}

/** spawn の起動コマンドと、サブコマンド引数の前に置く固定引数。 */
export interface EngineLaunch {
  command: string;
  prefixArgs: string[];
}

/** jpackage app-image のメインクラス名(installDist を java 直接起動する場合に指定する)。 */
const MAIN_CLASS = "jp.cobolinsight.app.cli.Main";

/**
 * engine CLI の起動対象を解決する純関数。ネットワークやソケットは用いず、ローカルの
 * サブプロセスのみを起動する。
 *
 * <ul>
 *   <li>配布時: extraResources で同梱した jpackage app-image(内蔵 JRE)の実行ファイルを
 *       直接起動する。system の JRE を要さない。</li>
 *   <li>開発時: gradle :engine:cli:installDist の成果物 lib を classpath に、JAVA_HOME
 *       (無ければ PATH)の java でメインクラスを起動する。appRoot(src/gui)の親が src であり、
 *       その配下の engine/app/build/install/app を参照する。</li>
 * </ul>
 *
 * classpath 末尾の `lib/*` は java 自身が展開するワイルドカードである。spawn はシェルを介さない
 * ため、この形のまま渡す必要がある。
 */
export function resolveEngineLaunch(input: EngineLaunchInput): EngineLaunch {
  const isWindows = input.platform === "win32";
  if (input.isPackaged) {
    const exe = isWindows ? "COBOLInsight.exe" : "COBOLInsight";
    return { command: join(input.resourcesPath, "engine", exe), prefixArgs: [] };
  }
  const installDir = join(
    input.appRoot,
    "..",
    "engine",
    "app",
    "build",
    "install",
    "app",
  );
  const classpath = join(installDir, "lib", "*");
  const javaName = isWindows ? "java.exe" : "java";
  const command =
    input.javaHome !== undefined && input.javaHome !== ""
      ? join(input.javaHome, "bin", javaName)
      : javaName;
  return { command, prefixArgs: ["-classpath", classpath, MAIN_CLASS] };
}
