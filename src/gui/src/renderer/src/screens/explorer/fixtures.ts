/**
 * テスト用の資産インベントリ fixture。window.cobolInsight.readAssetInventory が返す
 * AssetInventoryItem[] を模す。値域は scan が実際に書く SQLite に合わせる。すなわち SOURCE は
 * bms/cobol/copy(copybook)/jcl の 4 種のみで、NODE.type は BMS/PROGRAM/COPYBOOK/JCL、
 * codepage は CodePage.charsetName(UTF-8 / windows-31j / x-IBM930 / x-IBM939)である。
 * findingCount は scan 由来(復号・構文解析の失敗)の件数であり、ルール指摘(lint)の件数ではない。
 */

import type { AssetInventoryItem } from "../../../../shared/engine-api";

export const SAMPLE_INVENTORY: readonly AssetInventoryItem[] = [
  { id: 1, path: "bms/SYKMAP1.bms", name: "SYKMAP1.bms", type: "BMS", codepage: "UTF-8", byteSize: 1140, findingCount: 0 },
  { id: 2, path: "cobol/SYK001.cbl", name: "SYK001.cbl", type: "PROGRAM", codepage: "windows-31j", byteSize: 4200, findingCount: 0 },
  // 復号は成功したが構文解析に失敗した資産。NODE は PROGRAM として登録され、
  // parse-failure の finding が 1 件記録される。
  { id: 3, path: "cobol/SYK002.cbl", name: "SYK002.cbl", type: "PROGRAM", codepage: "windows-31j", byteSize: 3800, findingCount: 1 },
  // 復号に失敗した資産。codepage は null、NODE 未登録(UNKNOWN)で decode-failure が 1 件記録される。
  { id: 4, path: "cobol/SYKENC1.cbl", name: "SYKENC1.cbl", type: "UNKNOWN", codepage: null, byteSize: 512, findingCount: 1 },
  { id: 5, path: "copybook/SYKCPY1.cpy", name: "SYKCPY1.cpy", type: "COPYBOOK", codepage: "UTF-8", byteSize: 640, findingCount: 0 },
  { id: 6, path: "jcl/SYKD010.jcl", name: "SYKD010.jcl", type: "JCL", codepage: "UTF-8", byteSize: 900, findingCount: 0 },
];
