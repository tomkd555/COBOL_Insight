# Rule evaluation

What every built-in rule finds, measured over two asset folders, and what was decided about each
rule as a result.

`samples/` carries deliberate defects, listed row by row in `samples/expected-findings.tsv`.
`corpus/` carries none: every file in it is written to demonstrate the correct, defensive practice
for one family of rules, so a finding there is a false positive by construction. Both folders are
synthetic — hand-written for this repository, with nothing copied from a real system — so the
numbers below say how a rule behaves on the idioms these files use, not how often it is right in
the field.

`RuleEvaluationReportTest` produces the table below. It runs `lint` and `sql-lint` over both
folders with every rule forced on, ignoring any `rules.json`, so a rule that ships disabled is still
measured. Every corpus finding must be listed in `corpus/baseline.tsv` with a written reason; the
test fails on a finding that is not, and on a baseline row that no longer fires, so noise arrives as
a diff someone has to read.

## Verdicts

The rules the review put in question, and what the measurement decided. Every other built-in rule
was left as it was: none of them reported anything against the corpus, and each either matched its
expected defects exactly or was silent on both folders.

| rule | verdict | the number that decided it |
|---|---|---|
| R005 subscript out of OCCURS range | keep | 2 samples hits, both expected defects. Its 3 corpus findings are baselined: the interval analysis does not narrow on branches, and the CFG carries paragraph fall-through edges that a PERFORM-only paragraph never takes. Fixing either is a change to the analysis, not to the rule |
| R006 non-BINARY subscript | keep, on | 0 findings on either folder. The review proposed shipping it off as a preference; there is no measured noise to support that |
| R008 PERFORM without THRU | **default off** | 43 samples findings and 51 corpus findings, none of them a defect. Whether a PERFORM names its end is a house style. Still shipped, so a site with that rule can switch it on |
| R009 GO TO across sections | keep, on | 0 findings on either folder. CRP008's forward GO TO stays inside its own section and the rule does not report it, so the rule is precise on the one case the corpus exercises |
| R011 unreachable code / unused paragraph | keep, rule fixed | 1 corpus false positive (CRP002:144), now 0. A PERFORM inside a `READ … NOT INVALID KEY` never reached `CobolSemanticModel.performs()`, so a paragraph called only from there looked unreferenced. Fixed in the mapper, which is where every rule reading `performs()` gets it |
| R014 section fall-through | keep | 1 corpus finding, baselined. CRP008 uses SECTION headers as labels and no PERFORM names a section, so the fall-through is never taken; narrowing the rule to sections that are performed would cost it the trap it exists to catch |
| R016 STRING/UNSTRING overflow | keep, rule fixed | 1 corpus false positive (CRP009:142), now 0. The statement carried `ON OVERFLOW`, which is the rule's own stated remedy, so the rule now skips statements that handle the overflow |
| R017 FILE STATUS unchecked | keep as it is | 9 samples findings, 2 of them the expected defects; 1 corpus finding, baselined. Re-scoping it to "the status item is read nowhere in the program" clears the corpus finding **and loses expected defect No.6** (SYK002:130 — WS-MASTER-STATUS is read at SYK002:95), so the re-scope is rejected. Demoting the extra findings to NOTE fails on the same case: No.6 is the one that would be demoted |
| R024 COPY REPLACING never matches | keep, on | 0 findings on either folder, and `CopyReplacingRuleTest` already pins that a `REPLACING LEADING ==CP== BY ==WS==` against a copybook whose items start `CP-` is not reported. No change needed |
| R029 RETURN-CODE unchecked after CALL | keep, on | 0 findings on either folder. No measured noise to switch it off |
| R030 JCL COND unchecked | keep, on | 0 findings on either folder, over two JCL jobs that between them use COND and IF/THEN/ENDIF. No measured noise to switch it off |
| S002 non-SARGable predicate | **absorbs S003** | 0 findings on either folder for both rules. A function on a column is one way of being non-SARGable, the two gave the same advice, and a function on the left of a comparison landed in both signal lists and was reported twice. S002 now emits both message variants and skips the predicate it has already named |
| S003 function on an index column | **dropped, folded into S002** | see above |
| S005 FETCH FIRST missing | **dropped** | fired on every SELECT and every cursor declaration: 3 samples findings and 1 corpus finding, no defect behind any of them. A query that wants every row is the normal case, so the advice is noise by default |
| S006 OPTIMIZE FOR missing | **dropped** | fired on every cursor declaration: 1 samples finding and 1 corpus finding, no defect behind either |

`S003`, `S005` and `S006` are on `RemovedRules`, so a `rules.json` that still names one gets
"rule … was removed in V2" instead of the generic unknown-id warning. The catalogue is 34 rules.

<!-- generated by RuleEvaluationReportTest: begin -->

| rule | samples hits | matched expected | unmatched samples | corpus hits | baseline verdict |
|---|--:|--:|--:|--:|---|
| R001 | 2 | 2/2 | 0 | 0 | no false positive |
| R002 | 1 | 1/1 | 0 | 0 | no false positive |
| R003 | 2 | 2/2 | 0 | 0 | no false positive |
| R004 | 1 | 1/1 | 0 | 0 | no false positive |
| R005 | 2 | 2/2 | 0 | 3 | 3 accepted, see below |
| R006 | 0 | 0/0 | 0 | 0 | no false positive |
| R007 | 1 | 1/1 | 0 | 0 | no false positive |
| R008 (off) | 43 | 0/0 | 43 | 51 | 51 accepted, see below |
| R009 | 0 | 0/0 | 0 | 0 | no false positive |
| R010 | 0 | 0/0 | 0 | 0 | no false positive |
| R011 | 2 | 2/2 | 0 | 0 | no false positive |
| R012 | 0 | 0/0 | 0 | 0 | no false positive |
| R013 | 0 | 0/0 | 0 | 0 | no false positive |
| R014 | 0 | 0/0 | 0 | 1 | 1 accepted, see below |
| R015 | 0 | 0/0 | 0 | 0 | no false positive |
| R016 | 0 | 0/0 | 0 | 0 | no false positive |
| R017 | 9 | 2/2 | 7 | 1 | 1 accepted, see below |
| R018 | 2 | 2/2 | 0 | 0 | no false positive |
| R019 | 0 | 0/0 | 0 | 0 | no false positive |
| R020 | 0 | 0/0 | 0 | 0 | no false positive |
| R021 | 1 | 1/1 | 0 | 0 | no false positive |
| R022 | 1 | 1/1 | 0 | 0 | no false positive |
| R023 | 0 | 0/0 | 0 | 0 | no false positive |
| R024 | 0 | 0/0 | 0 | 0 | no false positive |
| R025 | 0 | 0/0 | 0 | 0 | no false positive |
| R026 | 0 | 0/0 | 0 | 0 | no false positive |
| R027 | 0 | 0/0 | 0 | 0 | no false positive |
| R028 | 0 | 0/0 | 0 | 0 | no false positive |
| R029 | 0 | 0/0 | 0 | 0 | no false positive |
| R030 | 0 | 0/0 | 0 | 0 | no false positive |
| R031 | 1 | 1/1 | 0 | 0 | no false positive |
| S001 | 0 | 0/0 | 0 | 0 | no false positive |
| S002 | 0 | 0/0 | 0 | 0 | no false positive |
| S004 | 1 | 0/0 | 1 | 0 | no false positive |

Corpus findings and why each is accepted. A rule the baseline accepts wholesale gets one row listing every place it fired:

| rule | where | why it is accepted |
|---|---|---|
| R005 | CRP001.cbl:163 | The subscript is bounded by the IF above it, which caps WS-検証件数 at WS-明細上限 (10). The interval analysis does not narrow on branches — the CFG carries no true/false edge labels — so it keeps the PICTURE bound of CRP1-明細件数 instead. |
| R005 | CRP004.cbl:48 | WS-IDX is the control variable of a paragraph-level PERFORM VARYING and is in range on every path that runs the paragraph. The CFG also carries the fall-through edge from the paragraph above, which is never taken because that paragraph is only ever entered by PERFORM; the state on it has WS-IDX past the loop bound. |
| R005 | CRP004.cbl:56 | Same PERFORM VARYING, same never-taken fall-through edge, at the DISPLAY of the computed amount. |
| R008 | CRP001.cbl:109, CRP001.cbl:125, CRP001.cbl:126, CRP001.cbl:127, CRP001.cbl:135, CRP001.cbl:137, CRP001.cbl:82, CRP001.cbl:83, CRP001.cbl:84, CRP002.cbl:101, CRP002.cbl:103, CRP002.cbl:105, CRP002.cbl:111, CRP002.cbl:141, CRP002.cbl:63, CRP002.cbl:64, CRP002.cbl:65, CRP002.cbl:66, CRP004.cbl:32, CRP004.cbl:33, CRP004.cbl:36, CRP005.cbl:125, CRP005.cbl:127, CRP005.cbl:133, CRP005.cbl:180, CRP005.cbl:75, CRP005.cbl:76, CRP005.cbl:77, CRP005.cbl:78, CRP005.cbl:97, CRP006.cbl:47, CRP006.cbl:50, CRP006.cbl:52, CRP007.cbl:34, CRP007.cbl:35, CRP008.cbl:52, CRP008.cbl:53, CRP008.cbl:54, CRP008.cbl:65, CRP008.cbl:82, CRP008.cbl:87, CRP008.cbl:90, CRP009.cbl:113, CRP009.cbl:135, CRP009.cbl:136, CRP009.cbl:137, CRP009.cbl:72, CRP009.cbl:73, CRP009.cbl:74, CRP009.cbl:75, CRP009.cbl:89 | R008 is off by default. It reported 51 findings over the corpus and 43 over samples for no defect at all; whether a PERFORM names its end with THRU is a house style, not a bug. Kept measurable by forcing every rule on in the harness. |
| R014 | CRP008.cbl:73 | CRP008 uses SECTION headers as labels: every paragraph is entered by a PERFORM naming the paragraph, and no PERFORM names a section, so the fall-through from 1000-初期化SECTION into 2000-検証SECTION is never taken. R014 does not ask whether anything performs the section, and narrowing it to sections that are performed would cost it the latent trap it exists to catch in section-structured programs. |
| R017 | CRP002.cbl:154 | The REWRITE handles its failure with INVALID KEY / NOT INVALID KEY, which R017 deliberately does not count as a check because it catches one event and not an I/O error in general. WS-MASTER-STATUS is tested after OPEN but on no path forward from this REWRITE. Re-scoping R017 to "the status item is read nowhere in the program" would clear this, and would lose expected defect No.6 (SYK002:130), whose WS-MASTER-STATUS is read at SYK002:95. |

<!-- generated by RuleEvaluationReportTest: end -->
