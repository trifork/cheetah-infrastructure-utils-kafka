import com.trifork.cheetah.CheetahKRaftAuthorizer;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.resource.ResourceType;
import org.apache.kafka.common.security.auth.KafkaPrincipal;
import org.apache.kafka.common.security.auth.SecurityProtocol;
import org.apache.kafka.server.authorizer.AuthorizableRequestContext;

import java.net.InetAddress;
import java.util.Map;

/**
 * Calls authorizeByResourceType on the CheetahKRaftAuthorizer compiled from the
 * checked-out branch, configured the way Kafka configures it. Kafka never calls
 * withPluginMetrics on it (it is not Monitorable), so the wrapped StandardAuthorizer has no
 * metrics object. Expected on current main: NullPointerException "this.authorizerMetrics" is null.
 */
public class NpeCheck {
    public static void main(String[] args) {
        CheetahKRaftAuthorizer a = new CheetahKRaftAuthorizer();
        a.configure(Map.of("node.id", "1", "super.users", "User:admin",
                "cheetah.authorization.claim.name", "roles", "cheetah.authorization.prefix", "Kafka_",
                "cheetah.authorization.claim.is-list", "true"));
        a.completeInitialLoad();
        AuthorizableRequestContext ctx = new AuthorizableRequestContext() {
            public String listenerName() { return "OAUTH"; }
            public SecurityProtocol securityProtocol() { return SecurityProtocol.SASL_SSL; }
            public KafkaPrincipal principal() { return new KafkaPrincipal("User", "someone"); }
            public InetAddress clientAddress() { return InetAddress.getLoopbackAddress(); }
            public int requestType() { return 22; }
            public int requestVersion() { return 0; }
            public String clientId() { return "c"; }
            public int correlationId() { return 0; }
        };
        try {
            System.out.println("authorizeByResourceType -> " + a.authorizeByResourceType(ctx, AclOperation.WRITE, ResourceType.TOPIC));
        } catch (Throwable t) {
            System.out.println("authorizeByResourceType threw " + t);
        }
    }
}
