package jp.cobolinsight.core.spi;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.source.DecodedSource;

import java.util.List;

/**
 * The contract for JCL analysis. Returns one job structure model per JOB card of the source, with
 * cataloged PROC expansion and symbolic parameter resolution already applied.
 *
 * <p>A statement the grammar cannot read costs that statement alone: the parser recovers, records
 * the spot as a diagnostic on the model and carries on. The only failure left is a source with no
 * JOB card the parser could read.
 */
public interface JclParser {

    ParseOutcome<List<JclJobModel>> parse(DecodedSource source, JclMemberResolver members);

    /**
     * Whether the source is a member rather than a job: JCL with no JOB card, a PROC and an
     * INCLUDE member alike. Such a member is analysed through the jobs that call it and has no
     * job model of its own, so it is not parsed on its own and is not a failure either.
     */
    default boolean isMember(DecodedSource source) {
        return false;
    }

    /**
     * What reading a source on its own reports, with the model it would build thrown away. This is
     * how a member no job of the run expands still has its syntax errors and its directive lines
     * reported; a member some job expands is read through that job instead.
     */
    default List<Finding> diagnose(DecodedSource source) {
        return List.of();
    }
}
