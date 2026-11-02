import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Sends sample records to the loggers configured in log4j2-audit.properties.
 * Run by Run-Spikes.ps1, which points the file's /mnt/logs/ at a local folder.
 *
 * Expected: the three "Principal = ..." lines only in kafka-authorizer.log, the two
 * "console-only" lines only on the console, and the two "SHOULD-NOT-APPEAR" lines nowhere.
 */
public class Log4jConfigCheck {
    public static void main(String[] args) {
        Logger audit = LogManager.getLogger("kafka.authorizer.logger");
        Logger op = LogManager.getLogger("com.trifork.cheetah.CheetahKRaftAuthorizer");
        Logger kafka = LogManager.getLogger("kafka.server.KafkaApis");

        // Same text format as Kafka 4.2.0 StandardAuthorizerData.buildAuditMessage.
        audit.debug("Principal = User:orders-service is Allowed operation = WRITE from host = 10.0.0.25 on resource = Topic:LITERAL:orders for request = Produce with resourceRefCount = 1 based on rule MatchingAcl(acl=x)");
        audit.debug("Principal = User:CN=my-cluster-kafka is Allowed operation = CLUSTER_ACTION from host = 10.0.0.26 on resource = Cluster:LITERAL:kafka-cluster for request = Fetch with resourceRefCount = 1 based on rule SuperUser");
        audit.info("Principal = User:orders-service is Denied operation = READ from host = 10.0.0.25 on resource = Topic:LITERAL:secret for request = Fetch with resourceRefCount = 1 based on rule DefaultDeny");
        audit.trace("TRACE-SHOULD-NOT-APPEAR");
        op.info("OPERATIONAL-INFO-console-only");
        op.debug("OPERATIONAL-DEBUG-SHOULD-NOT-APPEAR");
        kafka.info("KAFKA-INFO-console-only");
        LogManager.shutdown();
    }
}
