package com.trifork.cheetah;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.strimzi.kafka.oauth.common.JSONUtil;
import io.strimzi.kafka.oauth.server.OAuthKafkaPrincipal;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.acl.AclPermissionType;
import org.apache.kafka.common.metrics.PluginMetrics;
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
import org.apache.kafka.common.utils.AppInfoParser;
import org.apache.kafka.metadata.authorizer.StandardAcl;
import org.apache.kafka.metadata.authorizer.StandardAuthorizer;
import org.apache.kafka.server.authorizer.Action;
import org.apache.kafka.server.authorizer.AuthorizableRequestContext;
import org.apache.kafka.server.authorizer.Authorizer;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

/**
 * Black-box check that our plugin writes the same audit log as Kafka's StandardAuthorizer.
 * Both authorizers get the same requests through the public Authorizer interface. Every line they write to
 * 'kafka.authorizer.logger' (level and text) and every decision must be identical; only the text after
 * " based on rule " may differ. If this fails after a Kafka upgrade, Kafka changed its logging: change the plugin to
 * match, do not loosen the test.
 */
class AuditLogFormatTest {
    private static final String AUDIT_LOGGER = "kafka.authorizer.logger";
    private static final String RULE = " based on rule ";

    private final List<LogEvent> events = new CopyOnWriteArrayList<>();
    private LoggerContext logContext;

    @BeforeEach
    void captureAuditLog() {
        AbstractAppender appender = new AbstractAppender("capture", null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                events.add(event.toImmutable());
            }
        };
        appender.start();
        LoggerConfig config = new LoggerConfig(AUDIT_LOGGER, Level.TRACE, false);
        config.addAppender(appender, null, null);
        logContext = (LoggerContext) LogManager.getContext(false);
        logContext.getConfiguration().addLogger(AUDIT_LOGGER, config);
        logContext.updateLoggers();
    }

    @AfterEach
    void releaseAuditLog() {
        logContext.getConfiguration().removeLogger(AUDIT_LOGGER);
        logContext.updateLoggers();
    }

    @Test
    void ourAuditLogIsIdenticalToKafkas() throws Exception {
        // Kafka decides from ACLs, we from JWT roles. The setups are equivalent:
        // alice super user, bob may WRITE topic orders, carol may READ topic orders, dave has a JWT without roles.
        StandardAuthorizer kafka = new StandardAuthorizer();
        kafka.configure(Map.of("super.users", "User:alice"));
        kafka.withPluginMetrics(mock(PluginMetrics.class, RETURNS_DEEP_STUBS));
        kafka.completeInitialLoad();
        kafka.addAcl(Uuid.randomUuid(), allow("bob", ResourceType.TOPIC, "orders", AclOperation.WRITE));
        kafka.addAcl(Uuid.randomUuid(), allow("bob", ResourceType.CLUSTER, "kafka-cluster", AclOperation.IDEMPOTENT_WRITE)); // a WRITE role implies it in our plugin
        kafka.addAcl(Uuid.randomUuid(), allow("carol", ResourceType.TOPIC, "orders", AclOperation.READ));

        CheetahKRaftAuthorizer ours = new CheetahKRaftAuthorizer();
        ours.configure(Map.of("cheetah.authorization.claim.name", "roles", "cheetah.authorization.claim.is-list", "true",
                "cheetah.authorization.prefix", "Kafka_", "super.users", "User:alice"));

        Map<String, KafkaPrincipal> principals = new LinkedHashMap<>();
        principals.put("alice", new KafkaPrincipal("User", "alice"));
        principals.put("bob", jwtPrincipal("bob", "Kafka_orders_write"));
        principals.put("carol", jwtPrincipal("carol", "Kafka_orders_read"));
        principals.put("dave", jwtPrincipal("dave", null));

        StringBuilder drift = new StringBuilder();
        for (Map.Entry<String, KafkaPrincipal> user : principals.entrySet()) {
            for (ApiKeys api : List.of(ApiKeys.PRODUCE, ApiKeys.INIT_PRODUCER_ID)) {
                AuthorizableRequestContext context = requestContext(api, user.getValue());
                String who = user.getKey() + " / " + api.name;
                // Every combination of "log if allowed" / "log if denied", over a topic, another topic and the cluster.
                for (boolean ifAllowed : List.of(true, false)) {
                    for (boolean ifDenied : List.of(true, false)) {
                        List<Action> actions = List.of(
                                action(AclOperation.WRITE, ResourceType.TOPIC, "orders", ifAllowed, ifDenied),
                                action(AclOperation.READ, ResourceType.TOPIC, "other", ifAllowed, ifDenied),
                                action(AclOperation.IDEMPOTENT_WRITE, ResourceType.CLUSTER, "kafka-cluster", ifAllowed, ifDenied));
                        compare(drift, who + " / authorize logIfAllowed=" + ifAllowed + " logIfDenied=" + ifDenied,
                                kafka, ours, a -> a.authorize(context, actions));
                    }
                }
                // What Kafka calls for an idempotent producer after IDEMPOTENT_WRITE on the cluster is denied.
                for (AclOperation op : List.of(AclOperation.WRITE, AclOperation.READ)) {
                    compare(drift, who + " / authorizeByResourceType " + op, kafka, ours,
                            a -> a.authorizeByResourceType(context, op, ResourceType.TOPIC));
                }
            }
        }

        if (drift.length() > 0) {
            fail("""

                    AUDIT LOG DRIFT: CheetahKRaftAuthorizer does not behave like Kafka's StandardAuthorizer.
                    Each case shows the decision(s) and the audit lines (level, text without the rule).
                    %s
                    Why it matters: we write to Kafka's own logger '%s', so tools reading that log expect Kafka's output.
                    How to fix:
                      1. Read StandardAuthorizerData (logAuditMessage, buildAuditMessage) and the default
                         Authorizer#authorizeByResourceType in the Kafka version of pom.xml (%s):
                         https://github.com/apache/kafka/tree/%s
                      2. Change CheetahKRaftAuthorizer to write the same lines. Only the text after '%s' is ours.
                      3. If Kafka changed intentionally, say so in the pull request. Do not loosen this test.
                    """.formatted(drift, AUDIT_LOGGER, AppInfoParser.getVersion(), AppInfoParser.getVersion(), RULE.trim()));
        }
    }

    /** Runs the same call on both authorizers and records the case if decisions or audit lines differ. */
    private void compare(StringBuilder drift, String name, Authorizer kafka, Authorizer ours, Function<Authorizer, Object> call) {
        String expected = run(kafka, call);
        String actual = run(ours, call);
        if (!expected.equals(actual)) {
            drift.append("\n--- ").append(name).append("\nKafka:\n").append(expected).append("\nOurs:\n").append(actual).append('\n');
        }
    }

    private String run(Authorizer authorizer, Function<Authorizer, Object> call) {
        events.clear();
        StringBuilder out = new StringBuilder("  decision: " + call.apply(authorizer));
        for (LogEvent event : events) {
            String line = event.getMessage().getFormattedMessage();
            out.append("\n  ").append(event.getLevel()).append(' ').append(line, 0, Math.max(line.indexOf(RULE), 0));
        }
        events.clear();
        return out.toString();
    }

    private static StandardAcl allow(String user, ResourceType type, String name, AclOperation operation) {
        return new StandardAcl(type, name, PatternType.LITERAL, "User:" + user, "*", operation, AclPermissionType.ALLOW);
    }

    private static Action action(AclOperation op, ResourceType type, String name, boolean logIfAllowed, boolean logIfDenied) {
        return new Action(op, new ResourcePattern(type, name, PatternType.LITERAL), 1, logIfAllowed, logIfDenied);
    }

    /** An OAuth principal; its toString() holds groups and a token that Kafka (and we) must leave out of the log. */
    private static OAuthKafkaPrincipal jwtPrincipal(String user, String role) {
        ObjectNode claims = JSONUtil.newObjectNode();
        if (role != null) {
            claims.putArray("roles").add(role);
        }
        long now = System.currentTimeMillis();
        return new OAuthKafkaPrincipal("User", user, new MockBearerTokenWithPayload(user, new HashSet<>(List.of("a-group")),
                now, now + 60000, null, "secret-token", JSONUtil.asJson("{}"), claims));
    }

    private static AuthorizableRequestContext requestContext(ApiKeys api, KafkaPrincipal principal) throws Exception {
        return new RequestContext(new RequestHeader(api, api.latestVersion(), "client", 1), "connection-1",
                InetAddress.getByAddress(new byte[]{10, 1, 2, 3}), principal, new ListenerName("test"),
                SecurityProtocol.SASL_PLAINTEXT, new ClientInformation("", ""), false);
    }
}
