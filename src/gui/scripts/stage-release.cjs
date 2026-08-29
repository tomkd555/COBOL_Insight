"use strict";

const fs = require("node:fs");
const path = require("node:path");
const { execFileSync } = require("node:child_process");

const { path7za } = require("7zip-bin");

/**
 * The version electron-builder actually stamped on the build.
 *
 * afterAllArtifactBuild receives a BuildResult, which carries no packager of its own; the packager
 * hangs off each target. Reading it there picks up any version electron-builder resolved rather than
 * took verbatim (extraMetadata, for one). package.json is the fallback, and is the same value in the
 * ordinary case.
 */
function resolveVersion(context) {
  for (const targets of context.platformToTargets.values()) {
    for (const target of targets.values()) {
      const version = target.packager?.appInfo?.version;
      if (typeof version === "string" && version !== "") {
        return version;
      }
    }
  }
  return require("../package.json").version;
}

/**
 * Reshapes the electron-builder output into the two-item layout under release/:
 * COBOL_Insight_v<version>/ holds the unpacked application, and the zip of the same name contains
 * that folder (so extracting it yields the folder rather than loose files).
 *
 * electron-builder's own zip target is not used: it places the contents of appOutDir at the root of
 * the archive, scattering files across whatever directory the user extracts into.
 */
module.exports = async (context) => {
  const outDir = context.outDir;
  const version = resolveVersion(context);
  const folder = `COBOL_Insight_v${version}`;
  const released = path.join(outDir, folder);
  const unpacked = path.join(outDir, "win-unpacked");

  if (fs.existsSync(unpacked)) {
    fs.rmSync(released, { recursive: true, force: true });
    fs.renameSync(unpacked, released);
  } else if (!fs.existsSync(released)) {
    // Calling 7za with nothing to archive fails with an opaque compression error.
    throw new Error(`electron-builder produced no output at ${unpacked}`);
  }

  const zip = path.join(outDir, `${folder}.zip`);
  fs.rmSync(zip, { force: true });
  execFileSync(path7za, ["a", "-tzip", "-bso0", "-bsp0", zip, folder], { cwd: outDir });

  for (const leftover of ["builder-debug.yml", "builder-effective-config.yaml", ".icon-ico"]) {
    fs.rmSync(path.join(outDir, leftover), { recursive: true, force: true });
  }

  return [zip];
};
