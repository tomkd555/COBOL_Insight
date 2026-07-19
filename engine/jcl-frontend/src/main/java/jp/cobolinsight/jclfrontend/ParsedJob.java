package jp.cobolinsight.jclfrontend;

import java.util.List;

/** JCL のジョブ。 */
public record ParsedJob(String jobName, List<ParsedStep> steps) {
}
