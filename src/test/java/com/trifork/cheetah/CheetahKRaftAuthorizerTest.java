package com.trifork.cheetah;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.strimzi.kafka.oauth.common.JSONUtil;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.acl.AclBindingFilter;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.acl.AclPermissionType;
import org.apache.kafka.common.errors.AuthorizerNotReadyException;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.metrics.internals.PluginMetricsImpl;
import org.apache.kafka.common.resource.ResourceType;
import org.apache.kafka.metadata.authorizer.StandardAcl;
import org.apache.kafka.server.authorizer.AuthorizableRequestContext;
import org.apache.kafka.server.authorizer.AuthorizationResult;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static com.trifork.cheetah.AuthorizerTestSupport.action;
import static com.trifork.cheetah.AuthorizerTestSupport.oauth;
import static com.trifork.cheetah.AuthorizerTestSupport.oauthWithClaims;
import static com.trifork.cheetah.AuthorizerTestSupport.plain;
import static com.trifork.cheetah.AuthorizerTestSupport.readyAuthorizer;
import static com.trifork.cheetah.AuthorizerTestSupport.storedAcl;
import static org.apache.kafka.server.authorizer.AuthorizationResult.ALLOWED;
import static org.apache.kafka.server.authorizer.AuthorizationResult.DENIED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests through {@code authorize}: Kafka's {@code StandardAuthorizer} makes the decisions.
 */
class CheetahKRaftAuthorizerTest {

    private CheetahKRaftAuthorizer authorizer = readyAuthorizer("User:superuser;User:oauth-admin");

    @AfterEach
    void tearDown() throws Exception {
        authorizer.close();
    }

    private AuthorizationResult authorize(AuthorizableRequestContext context, AclOperation operation,
                                          ResourceType type, String name) {
        return authorizer.authorize(context, action(operation, type, name)).get(0);
    }

    /**
     * Roles, requested operation and expected result. Cases from the earlier test suite are kept;
     * "changed" marks a deliberate difference to the earlier hand-written rules.
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
                Arguments.of(List.of("Kafka_orders_write"), AclOperation.READ, ResourceType.GROUP, "any-group", DENIED),
                Arguments.of(List.of("Kafka_orders_alter-configs"), AclOperation.DESCRIBE_CONFIGS, ResourceType.TOPIC, "orders", ALLOWED),
                Arguments.of(List.of("Kafka_Cluster_describe-configs"), AclOperation.DESCRIBE_CONFIGS, ResourceType.CLUSTER, "kafka-cluster", ALLOWED),
                Arguments.of(List.of("Kafka_Cluster_create"), AclOperation.CREATE, ResourceType.CLUSTER, "kafka-cluster", ALLOWED),
                // changed: a leading '*' (suffix match) has no Kafka equivalent; the role is skipped
                Arguments.of(List.of("Kafka_*MyTopic_describe"), AclOperation.DESCRIBE, ResourceType.TOPIC, "TestMyTopic", DENIED),
                // changed: topic roles no longer grant cluster operations
                Arguments.of(List.of("Kafka_*_all"), AclOperation.CREATE, ResourceType.CLUSTER, "kafka-cluster", DENIED),
                Arguments.of(List.of("Kafka_*_all"), AclOperation.CLUSTER_ACTION, ResourceType.CLUSTER, "kafka-cluster", DENIED),
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
    void idempotentProducerIsAllowedThroughWriteOnAnyTopic() {
        // InitProducerId falls back to authorizeByResourceType(WRITE, TOPIC) when IDEMPOTENT_WRITE is denied.
        assertEquals(ALLOWED, authorizer.authorizeByResourceType(oauth("svc", "Kafka_orders_write"),
                AclOperation.WRITE, ResourceType.TOPIC));
        assertEquals(DENIED, authorizer.authorizeByResourceType(oauth("svc", "Kafka_orders_read"),
                AclOperation.WRITE, ResourceType.TOPIC));
    }

    @Test
    void missingRolesClaimIsDenied() {
        ObjectNode noRoles = JSONUtil.newObjectNode();
        assertEquals(DENIED, authorize(oauthWithClaims("svc", noRoles), AclOperation.READ, ResourceType.TOPIC, "orders"));
    }

    @Test
    void rolesOfOneUserNeverGrantAnotherUser() {
        assertEquals(ALLOWED, authorize(oauth("a", "Kafka_orders_read"), AclOperation.READ, ResourceType.TOPIC, "orders"));
        assertEquals(DENIED, authorize(oauth("b", "Kafka_other_read"), AclOperation.READ, ResourceType.TOPIC, "orders"));
    }

    @Test
    void currentTokenDecidesNotAnEarlierOne() {
        assertEquals(ALLOWED, authorize(oauth("svc", "Kafka_orders_all"), AclOperation.WRITE, ResourceType.TOPIC, "orders"));
        assertEquals(DENIED, authorize(oauth("svc", "Kafka_orders_read"), AclOperation.WRITE, ResourceType.TOPIC, "orders"));
    }

    @Test
    void superUsersAreDecidedByKafka() {
        assertEquals(ALLOWED, authorize(plain("superuser"), AclOperation.CLUSTER_ACTION, ResourceType.CLUSTER, "kafka-cluster"));
        assertEquals(DENIED, authorize(plain("someone-else"), AclOperation.CLUSTER_ACTION, ResourceType.CLUSTER, "kafka-cluster"));
        // Kafka applies super.users to every principal type, including OAuth principals without roles.
        assertEquals(ALLOWED, authorize(oauth("oauth-admin"), AclOperation.ALTER, ResourceType.CLUSTER, "kafka-cluster"));
    }

    @Test
    void storedAclsApplyAndDenyWins() {
        authorizer.loadSnapshot(Map.of(
                Uuid.randomUuid(), storedAcl("tls-user", ResourceType.TOPIC, "orders", AclOperation.READ, AclPermissionType.ALLOW),
                Uuid.randomUuid(), storedAcl("svc", ResourceType.TOPIC, "secret", AclOperation.READ, AclPermissionType.DENY)));

        assertEquals(ALLOWED, authorize(plain("tls-user"), AclOperation.READ, ResourceType.TOPIC, "orders"));
        assertEquals(DENIED, authorize(oauth("svc", "Kafka_*_read"), AclOperation.READ, ResourceType.TOPIC, "secret"));
        assertEquals(ALLOWED, authorize(oauth("svc", "Kafka_*_read"), AclOperation.READ, ResourceType.TOPIC, "orders"));
    }

    @Test
    void storedAclChangesReachCachedPrivateAuthorizers() {
        AuthorizableRequestContext svc = oauth("svc", "Kafka_*_read");
        assertEquals(ALLOWED, authorize(svc, AclOperation.READ, ResourceType.TOPIC, "orders"));

        Uuid deny = Uuid.randomUuid();
        authorizer.addAcl(deny, storedAcl("svc", ResourceType.TOPIC, "orders", AclOperation.READ, AclPermissionType.DENY));
        assertEquals(DENIED, authorize(svc, AclOperation.READ, ResourceType.TOPIC, "orders"));

        authorizer.removeAcl(deny);
        assertEquals(ALLOWED, authorize(svc, AclOperation.READ, ResourceType.TOPIC, "orders"));
    }

    @Test
    void roleAclIdenticalToAStoredAclIsAccepted() {
        StandardAcl same = new ClaimAclTranslator("Kafka_")
                .toAcls(oauth("svc").principal(), List.of("Kafka_orders_write")).iterator().next();
        authorizer.addAcl(Uuid.randomUuid(), same);

        assertEquals(ALLOWED, authorize(oauth("svc", "Kafka_orders_write"), AclOperation.WRITE, ResourceType.TOPIC, "orders"));
    }

    @Test
    void sharedAclStateOnlyHoldsStoredAcls() {
        authorizer.addAcl(Uuid.randomUuid(), storedAcl("tls-user", ResourceType.TOPIC, "orders", AclOperation.READ, AclPermissionType.ALLOW));
        for (int i = 0; i < 20; i++) {
            authorize(oauth("svc-" + i, "Kafka_*_all", "Kafka_Cluster_create"), AclOperation.READ, ResourceType.TOPIC, "orders");
        }

        assertEquals(1, authorizer.aclCount());
        assertEquals(1, StreamSupport.stream(authorizer.acls(AclBindingFilter.ANY).spliterator(), false).count());
    }

    @Test
    void beforeInitialLoadOnlySuperUsersAreAuthorized() throws Exception {
        authorizer.close();
        authorizer = new CheetahKRaftAuthorizer();
        authorizer.configure(AuthorizerTestSupport.configs("User:superuser"));

        assertEquals(ALLOWED, authorize(plain("superuser"), AclOperation.READ, ResourceType.TOPIC, "orders"));
        assertThrows(AuthorizerNotReadyException.class,
                () -> authorize(oauth("svc", "Kafka_*_all"), AclOperation.READ, ResourceType.TOPIC, "orders"));
    }

    @Test
    void worksWithoutMetricsFromKafka() {
        // Earlier versions threw NullPointerException here, because Kafka only injects metrics into Monitorable plugins.
        assertEquals(DENIED, authorizer.authorizeByResourceType(plain("someone"), AclOperation.WRITE, ResourceType.TOPIC));
    }

    @Test
    void sharesKafkaMetricsBetweenDelegateAndPrivateAuthorizers() {
        Metrics metrics = new Metrics();
        authorizer.withPluginMetrics(new PluginMetricsImpl(metrics, Map.of("role", "broker")));

        assertEquals(ALLOWED, authorize(oauth("a", "Kafka_*_read"), AclOperation.READ, ResourceType.TOPIC, "orders"));
        assertEquals(ALLOWED, authorize(oauth("b", "Kafka_*_write"), AclOperation.WRITE, ResourceType.TOPIC, "orders"));
        assertEquals(DENIED, authorize(plain("someone"), AclOperation.READ, ResourceType.TOPIC, "orders"));

        // Registering the same sensor twice on Kafka's PluginMetrics would throw; here it is registered once and shared.
        assertEquals(1, metricCount(metrics, "authorization-request-rate-per-minute"));
        assertEquals(1, metricCount(metrics, "acls-total-count"));
        assertTrue(metricValue(metrics, "authorization-request-rate-per-minute") > 0);
    }

    private static long metricCount(Metrics metrics, String name) {
        return metrics.metrics().keySet().stream().filter(n -> n.name().equals(name)).count();
    }

    private static double metricValue(Metrics metrics, String name) {
        return metrics.metrics().entrySet().stream()
                .filter(e -> e.getKey().name().equals(name))
                .mapToDouble(e -> (double) e.getValue().metricValue())
                .findFirst().orElseThrow();
    }

    @Test
    void kafkaWritesTheAuditLines() {
        try (AuditLogCapture audit = new AuditLogCapture()) {
            authorize(oauth("svc", "Kafka_orders_read"), AclOperation.READ, ResourceType.TOPIC, "orders");
            authorize(plain("superuser"), AclOperation.CLUSTER_ACTION, ResourceType.CLUSTER, "kafka-cluster");
            authorize(oauth("svc", "Kafka_orders_read"), AclOperation.WRITE, ResourceType.TOPIC, "orders");

            assertEquals(List.of(
                    "DEBUG Principal = User:svc is Allowed operation = READ from host = 127.0.0.1 on resource = Topic:LITERAL:orders for request = Fetch with resourceRefCount = 1 based on rule MatchingAcl(acl=StandardAcl[resourceType=TOPIC, resourceName=orders, patternType=LITERAL, principal=User:svc, host=*, operation=READ, permissionType=ALLOW])",
                    "DEBUG Principal = User:superuser is Allowed operation = CLUSTER_ACTION from host = 127.0.0.1 on resource = Cluster:LITERAL:kafka-cluster for request = Fetch with resourceRefCount = 1 based on rule SuperUser",
                    "INFO Principal = User:svc is Denied operation = WRITE from host = 127.0.0.1 on resource = Topic:LITERAL:orders for request = Fetch with resourceRefCount = 1 based on rule DefaultDeny"),
                    audit.lines());
        }
    }

    /** Captures {@code kafka.authorizer.logger} at DEBUG, the level needed to see allowed and super-user decisions. */
    private static final class AuditLogCapture implements AutoCloseable {
        private static final String AUDIT_LOGGER = "kafka.authorizer.logger";
        private final List<String> lines = new CopyOnWriteArrayList<>();
        private final LoggerContext context = (LoggerContext) LogManager.getContext(false);
        private final AbstractAppender appender = new AbstractAppender("audit-capture", null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                lines.add(event.getLevel() + " " + event.getMessage().getFormattedMessage());
            }
        };

        AuditLogCapture() {
            appender.start();
            Configuration configuration = context.getConfiguration();
            LoggerConfig loggerConfig = new LoggerConfig(AUDIT_LOGGER, Level.DEBUG, false);
            loggerConfig.addAppender(appender, Level.DEBUG, null);
            configuration.addLogger(AUDIT_LOGGER, loggerConfig);
            context.updateLoggers();
        }

        List<String> lines() {
            return lines;
        }

        @Override
        public void close() {
            context.getConfiguration().removeLogger(AUDIT_LOGGER);
            context.updateLoggers();
            appender.stop();
        }
    }
}
