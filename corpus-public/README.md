# corpus-public

Third-party source files, checked in unchanged apart from line endings, that measure how much
real-world JCL and COBOL the engine reads. `MANIFEST.md` names the upstream repository, the commit,
the licence and every folder; the licence text sits beside the files it covers.

Four tests read this folder:

- `ParseRateReportTest` (`src/engine/app`) runs discovery, decoding, parsing and SQL analysis over
  it and rewrites `parse-report.md` with the parse rates and every failure.
- `RuleEvaluationReportTest` runs every rule over it; a finding has to be listed in `baseline.tsv`
  with a reason, so noise arrives as a reviewable diff.
- `JclModelSnapshotTest` and `SqlModelSnapshotTest` write what the JCL and SQL frontends read out
  of each file to `<file>.jcl.json` and `<file>.sql.json` beside it, so a change to a job's steps,
  DDs or statements arrives as a diff too. Those snapshots are the only files here the project
  writes; nothing checked in from upstream is touched, and discovery skips `.json`.

The files are teaching material, so a finding here may be a real defect in the upstream file; the
baseline row says which.
