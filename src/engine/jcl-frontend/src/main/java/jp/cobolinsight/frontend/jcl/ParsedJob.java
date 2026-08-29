package jp.cobolinsight.frontend.jcl;

import java.util.List;

/** JCL のジョブ。 */
public record ParsedJob(String jobName, List<ParsedStep> steps) {
}
