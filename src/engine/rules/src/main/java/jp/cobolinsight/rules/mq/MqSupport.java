package jp.cobolinsight.rules.mq;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.rules.cfg.CfgSupport;
import jp.cobolinsight.rules.cobol.ServiceCalls;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** What the MQ rules share: which CALLs are MQI calls, and where one stands in the program. */
final class MqSupport {

    /** The MQI calls whose last two parameters are the completion code and the reason code. */
    static final Set<String> MQI = Set.of("MQCONN", "MQCONNX", "MQDISC", "MQOPEN", "MQCLOSE",
            "MQGET", "MQPUT", "MQPUT1", "MQINQ", "MQSET", "MQBEGIN", "MQCMIT", "MQBACK", "MQSUB",
            "MQSUBRQ", "MQCB", "MQCTL", "MQSTAT");

    private MqSupport() {
    }

    static Optional<ServiceCalls.Call> mqiCall(CobolSemanticModel model, CfgNode node) {
        return node.statement().flatMap(statement -> ServiceCalls.of(model, statement))
                .filter(call -> MQI.contains(call.target()));
    }

    /** The MQI calls of a program to one of the given entry points, in source order. */
    static List<ServiceCalls.Call> calls(CobolSemanticModel model, String... targets) {
        Set<String> wanted = Set.of(targets);
        return ServiceCalls.all(model).stream()
                .filter(call -> wanted.contains(call.target())).toList();
    }

    static Finding finding(RuleMeta meta, ServiceCalls.Call call, String message) {
        return Finding.of(meta.id(), meta.defaultSeverity().toLevel(), message,
                CfgSupport.at(call.statement().range()));
    }
}
