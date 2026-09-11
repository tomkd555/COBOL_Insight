package jp.cobolinsight.core.spi;

import jp.cobolinsight.core.source.DecodedSource;

import java.util.Optional;

/**
 * Where a PROC or INCLUDE member is looked up. The caller owns the search space and the decoding,
 * so the frontend never reads a file itself and a member is decoded with the same code-page
 * detection as every other asset.
 */
@FunctionalInterface
public interface JclMemberResolver {

    /** A resolver for a run with no member library at all. */
    JclMemberResolver NONE = memberName -> Optional.empty();

    /** The member of that name, or empty when the search space holds none. */
    Optional<DecodedSource> resolve(String memberName);
}
