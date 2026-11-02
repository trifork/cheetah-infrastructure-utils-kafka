import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.acl.AclPermissionType;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.metrics.internals.PluginMetricsImpl;
import org.apache.kafka.common.resource.PatternType;
import org.apache.kafka.common.resource.ResourcePattern;
import org.apache.kafka.common.resource.ResourceType;
import org.apache.kafka.common.security.auth.KafkaPrincipal;
import org.apache.kafka.common.security.auth.SecurityProtocol;
import org.apache.kafka.metadata.authorizer.StandardAcl;
import org.apache.kafka.metadata.authorizer.StandardAuthorizer;
import org.apache.kafka.server.authorizer.Action;
import org.apache.kafka.server.authorizer.AuthorizableRequestContext;
import org.apache.kafka.server.authorizer.AuthorizationResult;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs PR #83's pattern (addAcl claim ACL -> authorize -> removeAcl) on the
 * shared Kafka 4.2.0 StandardAuthorizer from several threads at once.
 *
 * "wrongly denied": authorize returned DENIED right after the thread added its own ALLOW ACL.
 * "principals still granted afterwards": after all threads finished and removed their ACLs,
 * the principal is still ALLOWED, i.e. a temporary ACL leaked.
 */
public class AclRace {
    static AuthorizableRequestContext ctx(String user) {
        return new AuthorizableRequestContext() {
            public String listenerName() { return "OAUTH"; }
            public SecurityProtocol securityProtocol() { return SecurityProtocol.SASL_SSL; }
            public KafkaPrincipal principal() { return new KafkaPrincipal("User", user); }
            public InetAddress clientAddress() { return InetAddress.getLoopbackAddress(); }
            public int requestType() { return 1; }
            public int requestVersion() { return 0; }
            public String clientId() { return "c"; }
            public int correlationId() { return 0; }
        };
    }

    static void run(int threads, int iterations, boolean samePrincipal) throws Exception {
        StandardAuthorizer auth = new StandardAuthorizer();
        auth.configure(Map.of("node.id", "1"));
        // Kafka normally injects metrics; without them authorize() throws NullPointerException.
        auth.withPluginMetrics(new PluginMetricsImpl(new Metrics(), Map.of()));
        auth.completeInitialLoad();

        AtomicLong wrongDeny = new AtomicLong(), addErr = new AtomicLong(), removeErr = new AtomicLong();
        CountDownLatch start = new CountDownLatch(1), done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            final String user = samePrincipal ? "u0" : "u" + t, topic = samePrincipal ? "topic-0" : "topic-" + t;
            new Thread(() -> {
                try {
                    start.await();
                    AuthorizableRequestContext c = ctx(user);
                    List<Action> actions = List.of(new Action(AclOperation.READ,
                            new ResourcePattern(ResourceType.TOPIC, topic, PatternType.LITERAL), 1, true, true));
                    for (int i = 0; i < iterations; i++) {
                        Uuid id = Uuid.randomUuid();
                        try {
                            auth.addAcl(id, new StandardAcl(ResourceType.TOPIC, topic, PatternType.LITERAL,
                                    "User:" + user, "*", AclOperation.READ, AclPermissionType.ALLOW));
                        } catch (RuntimeException e) { addErr.incrementAndGet(); }
                        if (auth.authorize(c, actions).get(0) != AuthorizationResult.ALLOWED) wrongDeny.incrementAndGet();
                        try { auth.removeAcl(id); } catch (RuntimeException e) { removeErr.incrementAndGet(); }
                    }
                } catch (InterruptedException ignored) {
                } finally { done.countDown(); }
            }).start();
        }
        start.countDown();
        done.await();

        int leakedGrants = 0;
        for (int t = 0; t < (samePrincipal ? 1 : threads); t++) {
            List<Action> a = List.of(new Action(AclOperation.READ,
                    new ResourcePattern(ResourceType.TOPIC, "topic-" + t, PatternType.LITERAL), 1, true, true));
            if (auth.authorize(ctx("u" + t), a).get(0) == AuthorizationResult.ALLOWED) leakedGrants++;
        }
        System.out.printf("samePrincipal=%b threads=%d iterations/thread=%d -> wrongly denied=%d, removeAcl errors=%d, addAcl errors=%d, ACLs left in cache=%d, principals still granted afterwards=%d%n",
                samePrincipal, threads, iterations, wrongDeny.get(), removeErr.get(), addErr.get(), auth.aclCount(), leakedGrants);
    }

    public static void main(String[] args) throws Exception {
        run(1, 200_000, false);
        for (int i = 0; i < 3; i++) run(8, 25_000, false);
        run(2, 25_000, true);
        run(8, 25_000, true);
    }
}
