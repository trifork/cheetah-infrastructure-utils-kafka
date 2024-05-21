package com.trifork.cheetah;

import org.apache.kafka.common.Endpoint;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.network.ClientInformation;
import org.apache.kafka.common.network.ListenerName;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.resource.PatternType;
import org.apache.kafka.common.resource.ResourcePattern;
import org.apache.kafka.common.resource.ResourceType;
import org.apache.kafka.common.security.auth.SecurityProtocol;
import org.apache.kafka.server.authorizer.Action;
import org.apache.kafka.server.authorizer.AuthorizableRequestContext;
import org.apache.kafka.server.authorizer.AuthorizationResult;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.strimzi.kafka.oauth.common.JSONUtil;
import io.strimzi.kafka.oauth.server.OAuthKafkaPrincipal;
import org.apache.kafka.common.requests.RequestContext;
import org.apache.kafka.common.requests.RequestHeader;
import org.apache.kafka.common.security.auth.KafkaPrincipal;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;
import org.junit.runner.RunWith;

import net.sourceforge.argparse4j.impl.Arguments;

class CheetahKRaftAuthorizerTest {
    @Test
    void CheckAuthorizeFlowSuperUser() {
        // TODO rework
        CheetahKRaftAuthorizer authorizer = new CheetahKRaftAuthorizer();
        authorizer.configure(Map.of("cheetah.authorization.claim.name", "roles",
                "cheetah.authorization.claim.is-list", "true",
                "cheetah.authorization.prefix", "Kafka",
                "super.users", "User:ANONYMOUS"));

        AuthorizableRequestContext context = new RequestContext(
                new RequestHeader(ApiKeys.CONTROLLER_REGISTRATION, (short) 1, "", 1),
                "",
                null,
                new KafkaPrincipal("User", "ANONYMOUS"),
                new ListenerName(""),
                SecurityProtocol.PLAINTEXT,
                new ClientInformation("", ""),
                false);
        List<Action> actions = List.of(new Action(AclOperation.CLUSTER_ACTION,
                new ResourcePattern(ResourceType.CLUSTER, "kafka-cluster", PatternType.LITERAL),
                1, true, true));

        List<AuthorizationResult> result = authorizer.authorize(context, actions);
        Assertions.assertEquals(result, List.of(AuthorizationResult.ALLOWED));

    }

    @Test
    void SampleTest() throws Exception {
        CheetahKRaftAuthorizer authorizer = createAndInitializeStandardAuthorizer();
        OAuthKafkaPrincipal principal = createPrincipal("bob", List.of("Kafka_mytopic_read"));
        List<AuthorizationResult> result = authorizer.authorize(
                new MockAuthorizableRequestContext.Builder().setPrincipal(principal).build(),
                List.of(newAction(AclOperation.READ, ResourceType.TOPIC, "mytopic")));

        assertEquals(List.of(AuthorizationResult.ALLOWED), result);
    }

    @TestFactory
    Stream<DynamicTest> TestAuthorize() {
        record Input(String principalName, String allowedRole, AclOperation requestedOperation,
                ResourceType requestedResourceType, String requestedResourceName) {
        }

        record Expected(List<AuthorizationResult> result) {
        }

        record TestCase(String testName, Input in, Expected expected) {

            public void run() throws Exception {
                // Construct the JWT
                OAuthKafkaPrincipal principal = createPrincipal(in.principalName,
                        List.of(in.allowedRole));
                // Construct the request context
                AuthorizableRequestContext requestContext = new MockAuthorizableRequestContext.Builder()
                        .setPrincipal(principal).build();
                // Construct the actions to be authorized
                List<Action> requestedActions = List
                        .of(newAction(in.requestedOperation, in.requestedResourceType,
                                in.requestedResourceName));
                // Initialize the authorizer
                CheetahKRaftAuthorizer authorizer = createAndInitializeStandardAuthorizer();

                // Run the authorization
                List<AuthorizationResult> result = authorizer.authorize(requestContext,
                        requestedActions);

                // Compare results
                assertEquals(expected.result, result);
            }
        }

        var testCases = Stream.of(
                new TestCase(
                        "Allow topic with exact name",
                        new Input("bob", "Kafka_mytopic_read", AclOperation.READ,
                                ResourceType.TOPIC, "mytopic"),
                        new Expected(List.of(AuthorizationResult.ALLOWED))),
                new TestCase(
                        "Deny different topic",
                        new Input("bob", "Kafka_no-permission-to-read-this_read",
                                AclOperation.READ,
                                ResourceType.TOPIC, "mytopic"),
                        new Expected(List.of(AuthorizationResult.DENIED))),
                new TestCase(
                        "Deny different resource type",
                        new Input("bob", "Kafka_mytopic_read", AclOperation.READ,
                                ResourceType.CLUSTER, "mytopic"),
                        new Expected(List.of(AuthorizationResult.DENIED))),
                new TestCase(
                        "Deny different operation",
                        new Input("bob", "Kafka_mytopic_read", AclOperation.WRITE,
                                ResourceType.TOPIC, "mytopic"),
                        new Expected(List.of(AuthorizationResult.DENIED))),
                new TestCase(
                        "Allow topic wildcard",
                        new Input("bob", "Kafka_*_read", AclOperation.READ,
                                ResourceType.TOPIC, "mytopic"),
                        new Expected(List.of(AuthorizationResult.ALLOWED))),
                new TestCase(
                        "Deny wildcard for a different operation",
                        new Input("bob", "Kafka_*_read", AclOperation.WRITE,
                                ResourceType.TOPIC, "mytopic"),
                        new Expected(List.of(AuthorizationResult.DENIED))),
                new TestCase(
                        "Allow cluster grant all",
                        new Input("bob", "Kafka_Cluster_all", AclOperation.READ,
                                ResourceType.CLUSTER, "doesntmatter"),
                        new Expected(List.of(AuthorizationResult.ALLOWED))),
                new TestCase(
                        "Allow cluster specific operation",
                        new Input("bob", "Kafka_Cluster_alter", AclOperation.ALTER,
                                ResourceType.CLUSTER, "doesntmatter"),
                        new Expected(List.of(AuthorizationResult.ALLOWED))),
                new TestCase(
                        "Cluster all doesn't allow topic operations",
                        new Input("bob", "Kafka_Cluster_all", AclOperation.READ,
                                ResourceType.TOPIC, "any-topic-name"),
                        new Expected(List.of(AuthorizationResult.DENIED))),
                new TestCase(
                        "Allow topic with prefix",
                        new Input("bob", "Kafka_myprefix*_read", AclOperation.READ,
                                ResourceType.TOPIC, "myprefix-and-whatever"),
                        new Expected(List.of(AuthorizationResult.ALLOWED))));

        return DynamicTest.stream(testCases, TestCase::testName, TestCase::run);
    }

    public static final Endpoint PLAINTEXT = new Endpoint("PLAINTEXT",
            SecurityProtocol.PLAINTEXT,
            "127.0.0.1",
            9020);

    static Action newAction(AclOperation aclOperation,
            ResourceType resourceType,
            String resourceName) {
        return new Action(aclOperation,
                new ResourcePattern(resourceType, resourceName, PatternType.LITERAL), 1, false, false);
    }

    static CheetahKRaftAuthorizer createAndInitializeStandardAuthorizer() {
        CheetahKRaftAuthorizer authorizer = new CheetahKRaftAuthorizer();
        authorizer.configure(Map.of(CheetahConfig.CHEETAH_AUTHORIZATION_CLAIM_NAME, "roles",
                CheetahConfig.CHEETAH_AUTHORIZATION_CLAIM_IS_LIST, "true",
                CheetahConfig.CHEETAH_AUTHORIZATION_PREFIX, "Kafka",
                CheetahConfig.CHEETAH_AUTHORIZATION_SUPER_USERS, "User:doesntmatter"));
        authorizer.start(new AuthorizerTestServerInfo(Collections.singletonList(PLAINTEXT)));
        authorizer.completeInitialLoad();
        return authorizer;
    }

    static OAuthKafkaPrincipal createPrincipal(String name, List<String> roles) {
        ObjectNode claims = JSONUtil.newObjectNode();
        ArrayNode rolesNode = claims.putArray("roles");
        for (String role : roles) {
            rolesNode.add(role);
        }

        return new OAuthKafkaPrincipal("User", name,
                new MockBearerTokenWithPayload(name,
                        new HashSet<>(Arrays.asList("")),
                        System.currentTimeMillis(), System.currentTimeMillis() + 60000, null,
                        "", JSONUtil.asJson("{}"),
                        claims));
    }
}
