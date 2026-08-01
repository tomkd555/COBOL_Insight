"use strict";

const fs = require("node:fs");
const path = require("node:path");
const { execFileSync } = require("node:child_process");

const { path7za } = require("7zip-bin");
const { version } = require("../package.json");

/**
 * electron-builder の出力を release/ の 2 点構成へ整える。COBOL_Insight_v<版数>/ が
 * 展開済みの一式で、同名の zip はそのフォルダごと収める(展開すると同じフォルダが現れる)。
 *
 * zip を electron-builder の zip ターゲットに任せないのは、あちらが appOutDir の中身を
 * zip の直下へ並べてしまい、展開先にファイルが散らばるためである。
 */
module.exports = async (context) => {
  const outDir = context.outDir;
  const folder = `COBOL_Insight_v${version}`;
  const released = path.join(outDir, folder);
  const unpacked = path.join(outDir, "win-unpacked");

  if (fs.existsSync(unpacked)) {
    fs.rmSync(released, { recursive: true, force: true });
    fs.renameSync(unpacked, released);
  } else if (!fs.existsSync(released)) {
    // 収める対象が無いまま 7za を呼ぶと、原因の分からない圧縮エラーで止まる。
    throw new Error(`electron-builder の出力が見つからない: ${unpacked}`);
  }

  const zip = path.join(outDir, `${folder}.zip`);
  fs.rmSync(zip, { force: true });
  execFileSync(path7za, ["a", "-tzip", "-bso0", "-bsp0", zip, folder], { cwd: outDir });

  for (const leftover of ["builder-debug.yml", "builder-effective-config.yaml", ".icon-ico"]) {
    fs.rmSync(path.join(outDir, leftover), { recursive: true, force: true });
  }

  return [zip];
};
