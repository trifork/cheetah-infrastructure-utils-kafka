package com.trifork.cheetah;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.strimzi.kafka.oauth.common.JSONUtil;
import io.strimzi.kafka.oauth.server.OAuthKafkaPrincipal;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.acl.AclPermissionType;
import org.apache.kafka.common.errors.AuthorizerNotReadyException;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.metrics.internals.PluginMetricsImpl;
import org.apache.kafka.common.network.ClientInformation;
import org.apache.kafka.common.network.ListenerName;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.requests.RequestContext;
import org.apache.kafka.common.requests.RequestHeader;
import org.apache.kafka.common.resource.PatternType;
import org.apache.kafka.common.resource.ResourcePattern;
import org.apache.kafka.common.resource.ResourceType;
import org.apache.kafka.common.security.auth.KafkaPrincipal;
import org.apache.kafka.common.security.auth.SecurityProtocol;
import org.apache.kafka.metadata.authorizer.StandardAcl;
import org.apache.kafka.server.authorizer.Action;
import org.apache.kafka.server.authorizer.AuthorizableRequestContext;
import org.apache.kafka.server.authorizer.AuthorizationResult;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.apache.kafka.server.authorizer.AuthorizationResult.ALLOWED;
import static org.apache.kafka.server.authorizer.AuthorizationResult.DENIED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests through {@code authorize}, so Kafka's {@code StandardAuthorizer} makes the decisions, as in a broker.
 * The role rules themselves are tested in {@link ClaimAclTranslatorTest}.
 */
class KRaftAuthorizerTest {

    private CheetahKRaftAuthorizer authorizer = newAuthorizer(true);

    @AfterEach
    void tearDown() throws Exception {
        authorizer.close();
    }

    // ---- Access from roles ------------------------------------------------------------------------------------

    /**
     * Roles, requested operation and expected result. Cases from the earlier tests are kept;
     * "changed" marks a deliberate difference to the earlier rules.
     */
    static Stream<Arguments> accessCases() {
        return Stream.of(
                Arguments.of(List.of("Kafka_*_all"), AclOperation.WRITE, ResourceType.TOPIC, "JobNameInputTopic", ALLOWED),
                Arguments.of(List.of("Kafka_*_all"), AclOperation.DESCRIBE, ResourceType.TOPIC, "Demo1_Topic", ALLOWED),
                Arguments.of(List.of("Kafka_*_all"), AclOperation.READ, ResourceType.GROUP, "", ALLOWED),
                Arguments.of(List.of("Kafka_*_read", "Kafka_*_write"), AclOperation.READ, ResourceType.GROUP, "", ALLOWED),
                Arguments.of(List.of("Kafka_*_read", "InvalidClaim"), AclOperation.READ, ResourceType.GROUP, "", ALLOWED),
                Arguments.of(List.of("Kafka_no_permission_to_read_from_this_topic"), AclOperation.READ, ResourceType.GROUP, "", DENIED),
                Arguments.of(List.of("InvalidClaim1", "InvalidClaim2"), AclOperation.READ, ResourceType.GROUP, "", DENIED),
                Arguments.of(List.of("Kafka_MyTopic*_All"), AclOperation.WRITE, ResourceType.TOPIC, "MyTopicTest", ALLOWED),
                Arguments.of(List.of("Kafka_MyTopic*_read"), AclOperation.WRITE, ResourceType.TOPIC, "MyTopicTest", DENIED),
                Arguments.of(List.of("Kafka_MyTopic*_all"), AclOperation.DESCRIBE, ResourceType.TOPIC, "NotMyTopic", DENIED),
                Arguments.of(List.of("Kafka_MyTopic*_describe"), AclOperation.DESCRIBE, ResourceType.TOPIC, "NotMyTopic", DENIED),
                Arguments.of(List.of("Kafka_MyTopic_describe"), AclOperation.DESCRIBE, ResourceType.TOPIC, "MyTopicTest", DENIED),
                Arguments.of(List.of("Kafka_Demo1_Write", "Kafka_Demo1_Read"), AclOperation.DESCRIBE, ResourceType.TOPIC, "Demo1", ALLOWED),
                Arguments.of(List.of("NotAKafkaRole", "Kafka_Demo1_Read"), AclOperation.READ, ResourceType.TOPIC, "Demo1", ALLOWED),
                Arguments.of(List.of("Kafka_Demo1_Topic_Read"), AclOperation.READ, ResourceType.TOPIC, "Demo1_Topic", ALLOWED),
                Arguments.of(List.of("Kafka_TestTopic*_READ"), AclOperation.DELETE, ResourceType.TOPIC, "TestTopic1", DENIED),
                Arguments.of(List.of("Kafka_TestTopic*_read"), AclOperation.DESCRIBE, ResourceType.GROUP, "TestGroup1", ALLOWED),
                Arguments.of(List.of("Kafka_TestTopic*_delete"), AclOperation.DELETE, ResourceType.GROUP, "TestGroup1", ALLOWED),
                Arguments.of(List.of("Kafka_Cluster_describe-configs"), AclOperation.DESCRIBE_CONFIGS, ResourceType.CLUSTER, "kafka-cluster", ALLOWED),
                Arguments.of(List.of("Kafka_Cluster_create"), AclOperation.CREATE, ResourceType.CLUSTER, "kafka-cluster", ALLOWED),
                // changed: a leading '*' (ends-with match) has no Kafka equivalent, so the role is skipped
                Arguments.of(List.of("Kafka_*MyTopic_describe"), AclOperation.DESCRIBE, ResourceType.TOPIC, "TestMyTopic", DENIED),
                // changed: topic roles no longer grant cluster permissions
                Arguments.of(List.of("Kafka_*_all"), AclOperation.CREATE, ResourceType.CLUSTER, "kafka-cluster", DENIED),
                Arguments.of(List.of("Kafka_Demo1_Topic_Write"), AclOperation.IDEMPOTENT_WRITE, ResourceType.CLUSTER, "kafka-cluster", DENIED)
        );
    }

    @ParameterizedTest
    @MethodSource("accessCases")
    void decidesFromRoles(List<String> roles, AclOperation operation, ResourceType type, String name,
                          AuthorizationResult expected) {
        assertEquals(expected, authorize(oauth("svc", roles.toArray(String[]::new)), operation, type, name));
    }

    @Test
    void idempotentProducerIsAllowedWithWriteOnAnyTopic() {
        // When IDEMPOTENT_WRITE on the cluster is denied, Kafka asks authorizeByResourceType(WRITE, TOPIC).
        assertEquals(ALLOWED, authorizer.authorizeByResourceType(oauth("svc", "Kafka_orders_write"),
                AclOperation.WRITE, ResourceType.TOPIC));
        assertEquals(DENIED, authorizer.authorizeByResourceType(oauth("svc", "Kafka_orders_read"),
                AclOperation.WRITE, ResourceType.TOPIC));
    }

    @Test
    void missingRolesClaimIsDenied() {
        assertEquals(DENIED, authorize(oauthWithClaims("svc", JSONUtil.newObjectNode()), AclOperation.READ, ResourceType.TOPIC, "orders"));
    }

    @Test
    void onlyTheCurrentTokenCounts() {
        assertEquals(ALLOWED, authorize(oauth("svc", "Kafka_orders_all"), AclOperation.WRITE, ResourceType.TOPIC, "orders"));
        assertEquals(DENIED, authorize(oauth("svc", "Kafka_orders_read"), AclOperation.WRITE, ResourceType.TOPIC, "orders"));
        assertEquals(DENIED, authorize(oauth("other", "Kafka_payments_read"), AclOperation.READ, ResourceType.TOPIC, "orders"));
    }

    // ---- Super users and stored ACLs (handled by Kafka) ---------------------------------------------------------

    @Test
    void superUsersAreAllowed() {
        assertEquals(ALLOWED, authorize(plain("superuser"), AclOperation.CLUSTER_ACTION, ResourceType.CLUSTER, "kafka-cluster"));
        assertEquals(DENIED, authorize(plain("someone-else"), AclOperation.CLUSTER_ACTION, ResourceType.CLUSTER, "kafka-cluster"));
        // Kafka applies super.users to every principal, also OAuth principals.
        assertEquals(ALLOWED, authorize(oauth("superuser", "Kafka_*_read"), AclOperation.ALTER, ResourceType.CLUSTER, "kafka-cluster"));
    }

    @Test
    void storedAclsApplyAndDenyWins() {
        authorizer.loadSnapshot(Map.of(
                Uuid.randomUuid(), storedAcl("tls-user", "orders", AclPermissionType.ALLOW),
                Uuid.randomUuid(), storedAcl("svc", "secret", AclPermissionType.DENY)));

        assertEquals(ALLOWED, authorize(plain("tls-user"), AclOperation.READ, ResourceType.TOPIC, "orders"));
        assertEquals(ALLOWED, authorize(oauth("svc", "Kafka_*_read"), AclOperation.READ, ResourceType.TOPIC, "orders"));
        assertEquals(DENIED, authorize(oauth("svc", "Kafka_*_read"), AclOperation.READ, ResourceType.TOPIC, "secret"));
    }

    @Test
    void storedAclChangesReachCachedAuthorizers() {
        AuthorizableRequestContext svc = oauth("svc", "Kafka_*_read");
        assertEquals(ALLOWED, authorize(svc, AclOperation.READ, ResourceType.TOPIC, "orders"));

        Uuid deny = Uuid.randomUuid();
        authorizer.addAcl(deny, storedAcl("svc", "orders", AclPermissionType.DENY));
        assertEquals(DENIED, authorize(svc, AclOperation.READ, ResourceType.TOPIC, "orders"));

        authorizer.removeAcl(deny);
        assertEquals(ALLOWED, authorize(svc, AclOperation.READ, ResourceType.TOPIC, "orders"));
    }

    @Test
    void roleAclThatIsAlsoStoredIsAccepted() {
        authorizer.addAcl(Uuid.randomUuid(), storedAcl("svc", "orders", AclPermissionType.ALLOW));
        assertEquals(ALLOWED, authorize(oauth("svc", "Kafka_orders_read"), AclOperation.READ, ResourceType.TOPIC, "orders"));
    }

    @Test
    void roleAclsAreNeverAddedToTheSharedAcls() {
        authorize(oauth("svc", "Kafka_*_all", "Kafka_Cluster_create"), AclOperation.READ, ResourceType.TOPIC, "orders");
        assertEquals(0, authorizer.aclCount());
    }

    @Test
    void beforeKafkaHasLoadedTheAclsOnlySuperUsersAreAllowed() throws Exception {
        authorizer.close();
        authorizer = newAuthorizer(false);

        assertEquals(ALLOWED, authorize(plain("superuser"), AclOperation.READ, ResourceType.TOPIC, "orders"));
        assertThrows(AuthorizerNotReadyException.class,
                () -> authorize(oauth("svc", "Kafka_*_all"), AclOperation.READ, ResourceType.TOPIC, "orders"));
    }

    // ---- Audit log --------------------------------------------------------------------------------------------

    @Test
    void kafkaWritesTheAuditLines() {
        List<String> lines = new CopyOnWriteArrayList<>();
        AbstractAppender capture = new AbstractAppender("audit-capture", null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                lines.add(event.getLevel() + " " + event.getMessage().getFormattedMessage());
            }
        };
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        LoggerConfig auditLogger = new LoggerConfig("kafka.authorizer.logger", Level.DEBUG, false);
        auditLogger.addAppender(capture, Level.DEBUG, null);
        capture.start();
        context.getConfiguration().addLogger("kafka.authorizer.logger", auditLogger);
        context.updateLoggers();
        try {
            authorize(oauth("svc", "Kafka_orders_read"), AclOperation.READ, ResourceType.TOPIC, "orders");
            authorize(plain("superuser"), AclOperation.CLUSTER_ACTION, ResourceType.CLUSTER, "kafka-cluster");
            authorize(oauth("svc", "Kafka_orders_read"), AclOperation.WRITE, ResourceType.TOPIC, "orders");
        } finally {
            context.getConfiguration().removeLogger("kafka.authorizer.logger");
            context.updateLoggers();
            capture.stop();
        }

        assertEquals(3, lines.size(), lines::toString);
        assertTrue(lines.get(0).startsWith("DEBUG Principal = User:svc is Allowed operation = READ from host = 127.0.0.1 on resource = Topic:LITERAL:orders for request = Fetch"), lines.get(0));
        assertTrue(lines.get(1).startsWith("DEBUG Principal = User:superuser is Allowed operation = CLUSTER_ACTION"), lines.get(1));
        assertTrue(lines.get(1).endsWith("based on rule SuperUser"), lines.get(1));
        assertTrue(lines.get(2).startsWith("INFO Principal = User:svc is Denied operation = WRITE"), lines.get(2));
    }

    // ---- Parallel requests ------------------------------------------------------------------------------------

    /**
     * Kafka authorizes on several request threads at once (num.io.threads, default 8), while its metadata thread
     * adds and removes stored ACLs. Adding and removing role ACLs on the shared delegate per request makes this test
     * fail with wrong decisions, exceptions and lost ACL changes; this is why the private authorizers exist.
     * Each thread makes a fixed number of requests, so the test does not depend on machine speed (the snapshot
     * build runs the tests under emulation for arm64).
     */
    @Test
    void parallelRequestsAndAclChangesStayCorrect() throws Exception {
        int threads = 8;
        int requestsPerThread = 500;
        int users = 20;
        AtomicLong wrong = new AtomicLong();
        AtomicLong aclChanges = new AtomicLong();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        CountDownLatch requestsDone = new CountDownLatch(threads);

        Thread metadataThread = new Thread(() -> {
            try {
                do {
                    Uuid id = Uuid.randomUuid();
                    authorizer.addAcl(id, storedAcl("tls-user", "churn", AclPermissionType.ALLOW));
                    authorizer.removeAcl(id);
                    aclChanges.incrementAndGet();
                } while (requestsDone.getCount() > 0);
            } catch (Throwable e) {
                errors.add(e);
            }
        });
        metadataThread.start();

        for (int t = 0; t < threads; t++) {
            final int thread = t;
            new Thread(() -> {
                try {
                    for (int i = 0; i < requestsPerThread; i++) {
                        int u = (thread * 3 + i) % users;   // threads overlap on users
                        AuthorizableRequestContext user = oauth("svc-" + u, "Kafka_svc-" + u + "_read");
                        boolean ownTopic = i % 2 == 0;
                        String topic = "svc-" + (ownTopic ? u : (u + 1) % users);
                        AuthorizationResult result = authorize(user, AclOperation.READ, ResourceType.TOPIC, topic);
                        if (result != (ownTopic ? ALLOWED : DENIED)) {
                            wrong.incrementAndGet();
                        }
                    }
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    requestsDone.countDown();
                }
            }).start();
        }

        assertTrue(requestsDone.await(5, TimeUnit.MINUTES), "request threads did not finish");
        metadataThread.join(TimeUnit.MINUTES.toMillis(1));

        assertEquals(List.of(), List.copyOf(errors));
        assertEquals(0, wrong.get(), "wrong decisions out of " + threads * requestsPerThread);
        assertTrue(aclChanges.get() > 0, "the metadata thread did not change any ACLs");
        assertEquals(0, authorizer.aclCount());
    }

    // ---- Helpers ----------------------------------------------------------------------------------------------

    /** Configured as Kafka does it: configure, then metrics, then (when loaded) completeInitialLoad. */
    private static CheetahKRaftAuthorizer newAuthorizer(boolean loaded) {
        Map<String, Object> configs = new HashMap<>();
        configs.put("cheetah.authorization.claim.name", "roles");
        configs.put("cheetah.authorization.claim.is-list", "true");
        configs.put("cheetah.authorization.prefix", "Kafka_");
        configs.put("super.users", "User:superuser");

        CheetahKRaftAuthorizer authorizer = new CheetahKRaftAuthorizer();
        authorizer.configure(configs);
        authorizer.withPluginMetrics(new PluginMetricsImpl(new Metrics(), Map.of()));
        if (loaded) {
            authorizer.completeInitialLoad();
        }
        return authorizer;
    }

    private AuthorizationResult authorize(AuthorizableRequestContext context, AclOperation operation,
                                          ResourceType type, String name) {
        Action action = new Action(operation, new ResourcePattern(type, name, PatternType.LITERAL), 1, true, true);
        return authorizer.authorize(context, List.of(action)).get(0);
    }

    private static AuthorizableRequestContext oauth(String name, String... roles) {
        ObjectNode claims = JSONUtil.newObjectNode();
        ArrayNode roleArray = claims.putArray("roles");
        for (String role : roles) {
            roleArray.add(role);
        }
        return oauthWithClaims(name, claims);
    }

    private static AuthorizableRequestContext oauthWithClaims(String name, ObjectNode claims) {
        long now = System.currentTimeMillis();
        MockBearerTokenWithPayload token = new MockBearerTokenWithPayload(name, new HashSet<>(), now, now + 60_000,
                null, "", JSONUtil.asJson("{}"), claims);
        return context(new OAuthKafkaPrincipal(KafkaPrincipal.USER_TYPE, name, token));
    }

    private static AuthorizableRequestContext plain(String name) {
        return context(new KafkaPrincipal(KafkaPrincipal.USER_TYPE, name));
    }

    private static AuthorizableRequestContext context(KafkaPrincipal principal) {
        return new RequestContext(
                new RequestHeader(ApiKeys.FETCH, ApiKeys.FETCH.latestVersion(), "", 1),
                "",
                InetAddress.getLoopbackAddress(),
                principal,
                new ListenerName(""),
                SecurityProtocol.SASL_PLAINTEXT,
                new ClientInformation("", ""),
                false);
    }

    private static StandardAcl storedAcl(String user, String topic, AclPermissionType permission) {
        return new StandardAcl(ResourceType.TOPIC, topic, PatternType.LITERAL, "User:" + user, "*",
                AclOperation.READ, permission);
    }
}
