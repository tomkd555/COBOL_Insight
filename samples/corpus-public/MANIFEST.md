# corpus-public manifest

Public source files checked in as a parse-rate benchmark. Every file is byte-for-byte the upstream
file except that CRLF line endings were turned into LF (the repository forces LF).

| Folder | Upstream repository | Commit | Licence | Files |
|---|---|---|---|---|
| cobol-programming-course/ | https://github.com/openmainframeproject/cobol-programming-course | 61c573dd13688f25e615e7cc4f9595cee38cd6a0 | CC-BY-4.0 (LICENSE kept beside the files; attribution: Open Mainframe Project, COBOL Programming Course contributors) | 73 |

Upstream path → folder here:

- `COBOL Programming Course #2 - Learning COBOL/Labs/{cbl,jcl,jclproc}` → `course2/{cbl,jcl,jclproc}`
- `COBOL Programming Course #3 - Advanced Topics/Labs/{cbl,jcl,jclproc}` → `course3/{cbl,jcl,jclproc}`
- `COBOL Programming Course #3 - Advanced Topics/Challenges/Debugging/{cbl,jcl}` → `course3/debug/{cbl,jcl}`
- `COBOL Programming Course #4 - Testing/Labs/{cbl,jcl}` → `course4/{cbl,jcl}` (the `.cut` unit-test definitions are not source and were left out)

Candidates examined and left out on 2026-09-10, with the reason:

- altitude80ai/zoscode (Apache-2.0 at the repository level): every JCL, COBOL and copybook file carries an IBM "LICENSED MATERIALS - PROPERTY OF IBM / RESTRICTED MATERIALS OF IBM" banner, which is treated as the operative notice.
- IBM/zopeneditor-sample: same in-file banner.
- cicsdev/cics-banking-sample-application-cbsa, IBM/Bank-of-Z: no licence file found.
- MVS 3.8j TK4-/TK5, CBT Tape: no licence; usable only locally through COBOL_INSIGHT_BENCH_DIR.
- IBM Redbooks additional materials: AS-IS terms, no redistribution clause.
- Rocket/Micro Focus BankDemo: proprietary licence.
- hackob/jcl-examples, diegonemi/jcl-task-library: no licence file.
- IBM/db2-samples, opensourcecobol/Open-COBOL-ESQL: not z/OS JCL or Db2 for z/OS.
