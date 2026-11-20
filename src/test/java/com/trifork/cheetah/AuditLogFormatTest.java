package com.trifork.cheetah;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.strimzi.kafka.oauth.common.JSONUtil;
import io.strimzi.kafka.oauth.server.OAuthKafkaPrincipal;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.metrics.PluginMetrics;
import org.apache.kafka.common.network.ClientInformation;
import org.apache.kafka.common.network.ListenerName;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.requests.RequestContext;
import org.apache.kafka.common.requests.RequestHeader;
import org.apache.kafka.common.resource.PatternType;
import org.apache.kafka.common.resource.ResourcePattern;
import org.apache.kafka.common.resource.ResourceType;
import org.apache.kafka.common.security.auth.SecurityProtocol;
import org.apache.kafka.common.utils.AppInfoParser;
import org.apache.kafka.metadata.authorizer.StandardAuthorizer;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

/**
 * Our audit line must look like the one Kafka's StandardAuthorizer writes to the same logger.
 * This test lets a real StandardAuthorizer write a line, lets {@link CheetahKRaftAuthorizer#audit} write one for the
 * same request, and compares text and level. Only the text after " based on rule " may differ.
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
    void ourAuditLineMatchesKafkas() throws Exception {
        StandardAuthorizer kafka = new StandardAuthorizer();
        kafka.configure(Map.of("super.users", "User:alice"));
        kafka.withPluginMetrics(mock(PluginMetrics.class, RETURNS_DEEP_STUBS));
        kafka.completeInitialLoad();

        // alice is a super user (allowed), carol has no access (denied); explicit and non-explicit checks log differently.
        for (String user : List.of("alice", "carol")) {
            for (boolean explicit : List.of(true, false)) {
                AuthorizableRequestContext context = requestContext(user);
                Action action = new Action(AclOperation.WRITE,
                        new ResourcePattern(ResourceType.TOPIC, "orders", PatternType.LITERAL), 1, explicit, explicit);

                AuthorizationResult result = kafka.authorize(context, List.of(action)).get(0);
                LogEvent kafkaEvent = takeEvent();
                CheetahKRaftAuthorizer.audit(context, action, result, "TestRule");
                LogEvent ourEvent = takeEvent();

                String kafkaLine = kafkaEvent.getMessage().getFormattedMessage();
                String ourLine = ourEvent.getMessage().getFormattedMessage();
                if (!kafkaLine.substring(0, kafkaLine.indexOf(RULE)).equals(ourLine.substring(0, ourLine.indexOf(RULE)))
                        || kafkaEvent.getLevel() != ourEvent.getLevel()) {
                    fail("""

                            AUDIT LOG DRIFT: CheetahKRaftAuthorizer writes a different audit line than Kafka's StandardAuthorizer.
                            (user=%s, explicit check=%s)

                              Kafka: [%s] %s
                              Ours : [%s] %s

                            Why it matters: we write to Kafka's own logger '%s', so tools reading that log expect Kafka's format.
                            Only the text after '%s' may differ.

                            How to fix:
                              1. Read buildAuditMessage and logAuditMessage in StandardAuthorizerData, in the Kafka version of pom.xml:
                                 https://github.com/apache/kafka/blob/%s/metadata/src/main/java/org/apache/kafka/metadata/authorizer/StandardAuthorizerData.java
                              2. Update CheetahKRaftAuthorizer#buildAuditMessage (text) and #audit (levels) to match.
                              3. If the change is intended, say so in the pull request and adjust this test.
                            """.formatted(user, explicit, kafkaEvent.getLevel(), kafkaLine, ourEvent.getLevel(), ourLine,
                            AUDIT_LOGGER, RULE.trim(), AppInfoParser.getVersion()));
                }
            }
        }
    }

    private LogEvent takeEvent() {
        assertEquals(1, events.size(), "Expected exactly one audit line on '" + AUDIT_LOGGER + "': " + events);
        return events.remove(0);
    }

    /** An OAuth principal, because its toString() holds groups and a token that Kafka (and we) must leave out of the log. */
    private static AuthorizableRequestContext requestContext(String user) throws Exception {
        ObjectNode claims = JSONUtil.newObjectNode();
        long now = System.currentTimeMillis();
        OAuthKafkaPrincipal principal = new OAuthKafkaPrincipal("User", user,
                new MockBearerTokenWithPayload(user, new HashSet<>(List.of("a-group")), now, now + 60000, null,
                        "secret-token", JSONUtil.asJson("{}"), claims));
        return new RequestContext(new RequestHeader(ApiKeys.PRODUCE, (short) 11, "client", 1), "connection-1",
                InetAddress.getByAddress(new byte[]{10, 1, 2, 3}), principal, new ListenerName("test"),
                SecurityProtocol.SASL_PLAINTEXT, new ClientInformation("", ""), false);
    }
}
