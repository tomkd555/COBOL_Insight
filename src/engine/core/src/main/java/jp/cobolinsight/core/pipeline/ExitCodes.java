package jp.cobolinsight.core.pipeline;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;

import java.util.Collection;

/** CLI終了コード契約。成功=0・警告あり=1・エラー=2。 */
public final class ExitCodes {

    public static final int SUCCESS = 0;
    public static final int WARNINGS = 1;
    public static final int ERRORS = 2;

    private ExitCodes() {
    }

    /** findings に含まれる最も高いレベルから終了コードを決める。findings が空なら成功。 */
    public static int fromFindings(Collection<Finding> findings) {
        int code = SUCCESS;
        for (Finding finding : findings) {
            if (finding.level() == FindingLevel.ERROR) {
                return ERRORS;
            }
            if (finding.level() == FindingLevel.WARNING) {
                code = WARNINGS;
            }
        }
        return code;
    }
}
