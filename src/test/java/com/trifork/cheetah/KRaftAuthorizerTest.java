package com.trifork.cheetah;

import org.apache.kafka.common.acl.AclOperation;
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
import org.apache.kafka.server.authorizer.Action;
import org.apache.kafka.server.authorizer.AuthorizableRequestContext;
import org.apache.kafka.server.authorizer.AuthorizationResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.trifork.cheetah.CheetahKRaftAuthorizer.checkClusterJwtClaims;
import static com.trifork.cheetah.CheetahKRaftAuthorizer.checkTopicJwtClaims;
import static com.trifork.cheetah.CheetahKRaftAuthorizer.extractTopicAccesses;
import static com.trifork.cheetah.CheetahKRaftAuthorizer.extractClusterAccesses;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;

import io.strimzi.kafka.oauth.common.JSONUtil;
import io.strimzi.kafka.oauth.server.OAuthKafkaPrincipal;

class KRaftAuthorizerTest {

    CheetahKRaftAuthorizer authorizer;

    @BeforeEach
    void setUp() {
        authorizer = new CheetahKRaftAuthorizer();
        setUpCommonConfigs();
    }

    @AfterEach
    void tearDown() {
        try {
            authorizer.close();
        } catch (Exception ignore) {
        }
    }

    /**
     * Configures the authorizer with common settings.
     * This includes setting the claim name, claim type, prefix, and superUser.
     */
    private void setUpCommonConfigs() {
        authorizer.configure(Map.of("cheetah.authorization.claim.name", "roles",
                "cheetah.authorization.claim.is-list", "true",
                "cheetah.authorization.prefix", "Kafka_",
                "super.users", "User:doesntmatter"));
    }

    /**
     * Configures the authorizer with superUser settings.
     * This includes setting the claim name, claim type, prefix, and superUser.
     */
    private void setUpSuperUserConfigs() {
        authorizer.configure(Map.of("cheetah.authorization.claim.name", "roles",
                "cheetah.authorization.claim.is-list", "true",
                "cheetah.authorization.prefix", "Kafka_",
                "super.users", "User:superuser"));
    }

    /**
     * Creates an AuthorizableRequestContext with the given OAuthKafkaPrincipal.
     *
     * @param principal the OAuthKafkaPrincipal to be used in the request context
     * @return an AuthorizableRequestContext with the specified principal
     */
    private AuthorizableRequestContext getAuthorizableRequestContext(OAuthKafkaPrincipal principal) {
        return new RequestContext(
                new RequestHeader(ApiKeys.OFFSET_COMMIT, (short) 8, "", 1),
                "",
                null,
                principal,
                new ListenerName(""),
                SecurityProtocol.PLAINTEXT,
                new ClientInformation("", ""),
                false);
    }

    /**
     * Creates an OAuthKafkaPrincipal with the given principal name and custom claims.
     *
     * @param principalName the name of the principal
     * @param customClaims  the custom claims to be included in the principal's token
     * @return an OAuthKafkaPrincipal with the specified principal name and claims
     */
    private OAuthKafkaPrincipal getKafkaPrincipal(String principalName, ObjectNode customClaims) {
        ObjectNode claims = (customClaims != null) ? customClaims : JSONUtil.newObjectNode();
        long issuedAt = System.currentTimeMillis();
        long expiresAt = issuedAt + 60000;

        return new OAuthKafkaPrincipal("", principalName,
                new MockBearerTokenWithPayload(principalName,
                        new HashSet<>(List.of("")),
                        issuedAt,
                        expiresAt,
                        null,
                        "",
                        JSONUtil.asJson("{}"),
                        claims));
    }

    @Test
    void CheckAuthorizeFlowSuperUser() {
        setUpSuperUserConfigs();

        AuthorizableRequestContext context = new RequestContext(
                new RequestHeader(ApiKeys.CONTROLLER_REGISTRATION, (short) 1, "", 1),
                "",
                null,
                new KafkaPrincipal("User", "superuser"),
                new ListenerName(""),
                SecurityProtocol.PLAINTEXT,
                new ClientInformation("", ""),
                false);
        List<Action> actions = List.of(new Action(AclOperation.CLUSTER_ACTION,
                new ResourcePattern(ResourceType.CLUSTER, "kafka-cluster", PatternType.LITERAL),
                1, true, true));

        List<AuthorizationResult> result = authorizer.authorize(context, actions);
        assertEquals(List.of(AuthorizationResult.ALLOWED), result, "Super user should be allowed");

    }

    @Test
    void CheckAuthorizeFlowPerformanceTesterAllowTopic() {
        ObjectNode claims = JSONUtil.newObjectNode();
        claims.putArray("roles").add("Kafka_*_all");

        OAuthKafkaPrincipal principal = getKafkaPrincipal("User:doesntmatter", claims);
        AuthorizableRequestContext context = getAuthorizableRequestContext(principal);

        List<Action> actions = List.of(new Action(AclOperation.READ,
                new ResourcePattern(ResourceType.GROUP, "", PatternType.LITERAL),
                1, true, true));

        List<AuthorizationResult> result = authorizer.authorize(context, actions);
        assertEquals(result, List.of(AuthorizationResult.ALLOWED));
    }

    @Test
    void CheckAuthorizeFlowPerformanceTesterDenyTopic() {
        ObjectNode claims = JSONUtil.newObjectNode();
        claims.putArray("roles").add("Kafka_no_permission_to_read_from_this_topic");


        OAuthKafkaPrincipal principal = getKafkaPrincipal("User:doesntmatter", claims);
        AuthorizableRequestContext context = getAuthorizableRequestContext(principal);

        List<Action> actions = List.of(new Action(AclOperation.READ,
                new ResourcePattern(ResourceType.GROUP, "", PatternType.LITERAL),
                1, true, true));

        List<AuthorizationResult> result = authorizer.authorize(context, actions);
        assertEquals(result, List.of(AuthorizationResult.DENIED));
    }

    @Test
    void CheckJwtClaimsAllAllow() {
        List<TopicAccess> topicAccess = extractTopicAccesses(List.of("*_all"), "");
        boolean result = checkTopicJwtClaims(topicAccess, new Action(AclOperation.WRITE,
                new ResourcePattern(ResourceType.TOPIC, "JobNameInputTopic", PatternType.LITERAL), 1,
                false, false));
        Assertions.assertTrue(result);
    }

    @Test
    void testCheckJwtClaimsAllOneTopicAllow() {
        List<TopicAccess> topicAccess = extractTopicAccesses(List.of("MyTopic*_All"), "");
        boolean result = checkTopicJwtClaims(topicAccess, new Action(AclOperation.WRITE,
                new ResourcePattern(ResourceType.TOPIC, "MyTopicTest", PatternType.LITERAL), 1, false,
                false));
        Assertions.assertTrue(result);
    }

    @Test
    void testCheckJwtClaimsAllOneTopicDeny() {
        List<TopicAccess> topicAccess = extractTopicAccesses(List.of("MyTopic*_read"), "");
        boolean result = checkTopicJwtClaims(topicAccess, new Action(AclOperation.WRITE,
                new ResourcePattern(ResourceType.TOPIC, "MyTopicTest", PatternType.LITERAL), 1, false,
                false));
        assertFalse(result);
    }

    @Test
    void testCheckJwtClaimsAllOneTopic() {
        List<TopicAccess> topicAccess = extractTopicAccesses(List.of("MyTopic*_all"), "");
        boolean result = checkTopicJwtClaims(topicAccess, new Action(AclOperation.DESCRIBE,
                new ResourcePattern(ResourceType.TOPIC, "NotMyTopic", PatternType.LITERAL), 1, false,
                false));
        assertFalse(result);
    }

    @Test
    void testCheckJwtClaimsDeny() {
        List<TopicAccess> topicAccess = extractTopicAccesses(List.of("MyTopic*_describe"), "");
        boolean result = checkTopicJwtClaims(topicAccess, new Action(AclOperation.DESCRIBE,
                new ResourcePattern(ResourceType.TOPIC, "NotMyTopic", PatternType.LITERAL), 1, false,
                false));
        assertFalse(result);
    }

    @Test
    void testCheckJwtClaimsDenyWithoutWildcard() {
        List<TopicAccess> topicAccess = extractTopicAccesses(List.of("MyTopic_describe"), "");
        boolean result = checkTopicJwtClaims(topicAccess, new Action(AclOperation.DESCRIBE,
                new ResourcePattern(ResourceType.TOPIC, "MyTopicTest", PatternType.LITERAL), 1, false,
                false));
        assertFalse(result);
    }

    @Test
    void testCheckJwtClaimsAllowWithStartWildcard() {
        List<TopicAccess> topicAccess = extractTopicAccesses(List.of("*MyTopic_describe"), "");
        boolean result = checkTopicJwtClaims(topicAccess, new Action(AclOperation.DESCRIBE,
                new ResourcePattern(ResourceType.TOPIC, "TestMyTopic", PatternType.LITERAL), 1, false,
                false));
        Assertions.assertTrue(result);
    }

    @Test
    void testClaimWithPrefix() {
        List<TopicAccess> topicAccess = extractTopicAccesses(List.of("Kafka_*MyTopic_describe"), "Kafka_");
        boolean result = checkTopicJwtClaims(topicAccess, new Action(AclOperation.DESCRIBE,
                new ResourcePattern(ResourceType.TOPIC, "TestMyTopic", PatternType.LITERAL), 1, false,
                false));
        Assertions.assertTrue(result);
    }

    @Test
    void testClaimsWithPrefix() {
        List<TopicAccess> topicAccess = extractTopicAccesses(
                List.of("Kafka_Demo1_Write", "Kafka_Demo3_Write", "Kafka_Demo2_Write",
                        "Kafka_Demo1_Read"),
                "Kafka_");
        boolean describeAccess = checkTopicJwtClaims(topicAccess, new Action(AclOperation.DESCRIBE,
                new ResourcePattern(ResourceType.TOPIC, "Demo1", PatternType.LITERAL), 1, false,
                false));
        boolean readAccess = checkTopicJwtClaims(topicAccess, new Action(AclOperation.READ,
                new ResourcePattern(ResourceType.TOPIC, "Demo1", PatternType.LITERAL), 1, false,
                false));
        Assertions.assertTrue(describeAccess);
        Assertions.assertTrue(readAccess);
    }

    @Test
    void testNonKafkaRolesFirst() {
        List<TopicAccess> topicAccess = extractTopicAccesses(List.of("NotAKafkaRole", "Kafka_Demo1_Write",
                "Kafka_Demo3_Write", "Kafka_Demo2_Write", "Kafka_Demo1_Read"), "Kafka_");
        boolean describeAccess = checkTopicJwtClaims(topicAccess, new Action(AclOperation.DESCRIBE,
                new ResourcePattern(ResourceType.TOPIC, "Demo1", PatternType.LITERAL), 1, false,
                false));
        boolean readAccess = checkTopicJwtClaims(topicAccess, new Action(AclOperation.READ,
                new ResourcePattern(ResourceType.TOPIC, "Demo1", PatternType.LITERAL), 1, false,
                false));
        Assertions.assertTrue(describeAccess);
        Assertions.assertTrue(readAccess);
    }

    @Test
    void testTopicWithUnderscore() {
        List<TopicAccess> topicAccess = extractTopicAccesses(List.of("Kafka_Demo1_Topic_Read"), "Kafka_");
        boolean readAccess = checkTopicJwtClaims(topicAccess, new Action(AclOperation.READ,
                new ResourcePattern(ResourceType.TOPIC, "Demo1_Topic", PatternType.LITERAL), 1, false,
                false));
        Assertions.assertTrue(readAccess);
    }

    @Test
    void testIdempotentWrite() {
        List<TopicAccess> topicAccess = extractTopicAccesses(List.of("Kafka_Demo1_Topic_Write"), "Kafka_");
        boolean readAccess = checkTopicJwtClaims(topicAccess, new Action(AclOperation.IDEMPOTENT_WRITE,
                new ResourcePattern(ResourceType.CLUSTER, "Demo1_Topic", PatternType.LITERAL), 1, false,
                false));
        Assertions.assertTrue(readAccess);
    }

    @Test
    void testDescribe() {
        List<TopicAccess> topicAccess = extractTopicAccesses(List.of("*_all"), "");
        boolean readAccess = checkTopicJwtClaims(topicAccess, new Action(AclOperation.DESCRIBE,
                new ResourcePattern(ResourceType.TOPIC, "Demo1_Topic", PatternType.LITERAL), 1, false,
                false));
        Assertions.assertTrue(readAccess);
    }

    @Test
    void testIdempotentWriteWithWildcard() {
        List<ClusterAccess> clusterAccess = extractClusterAccesses(List.of("*_all"), "");
        boolean readAccess = checkClusterJwtClaims(clusterAccess, new Action(AclOperation.IDEMPOTENT_WRITE,
                new ResourcePattern(ResourceType.CLUSTER, "Demo1_Topic", PatternType.LITERAL), 1, false,
                false));
        Assertions.assertTrue(readAccess);
    }

    @Test
    void testDescribeConfigClaimWithCluster() {
        List<ClusterAccess> clusterAccess = extractClusterAccesses(List.of("Kafka_Cluster_describe-configs"),
                "Kafka_");
        boolean result = checkClusterJwtClaims(clusterAccess, new Action(AclOperation.DESCRIBE_CONFIGS,
                new ResourcePattern(ResourceType.CLUSTER, "Cluster", PatternType.LITERAL), 1, false,
                false));
        Assertions.assertTrue(result);
    }


    @Test
    void testSuperUserAccess() {
        setUpSuperUserConfigs();

        AuthorizableRequestContext context = new RequestContext(
                new RequestHeader(ApiKeys.CONTROLLER_REGISTRATION, (short) 1, "", 1),
                "",
                null,
                new KafkaPrincipal("User", "superuser"),
                new ListenerName(""),
                SecurityProtocol.PLAINTEXT,
                new ClientInformation("", ""),
                false);

        List<Action> actions = List.of(new Action(AclOperation.CLUSTER_ACTION,
                new ResourcePattern(ResourceType.CLUSTER, "kafka-cluster", PatternType.LITERAL),
                1, true, true));

        List<AuthorizationResult> result = authorizer.authorize(context, actions);

        assertEquals(List.of(AuthorizationResult.ALLOWED), result);

    }

    @Test
    void testRaceCondition() throws InterruptedException {
        ObjectNode claims1 = JSONUtil.newObjectNode();
        claims1.putArray("roles").add("Kafka_*_write");

        OAuthKafkaPrincipal principal1 = getKafkaPrincipal("User:doesntmatter", claims1);
        AuthorizableRequestContext context1 = getAuthorizableRequestContext(principal1);

        List<Action> actions1 = List.of(new Action(AclOperation.WRITE,
                new ResourcePattern(ResourceType.TOPIC, "TestTopic", PatternType.LITERAL),
                1, true, true));

        Thread thread1 = new Thread(() -> {
            List<AuthorizationResult> result1 = authorizer.authorize(context1, actions1);
            assertEquals(List.of(AuthorizationResult.ALLOWED), result1);
        });

        ObjectNode claims2 = JSONUtil.newObjectNode();
        claims2.putArray("roles").add("Kafka_*_read");

        OAuthKafkaPrincipal principal2 = getKafkaPrincipal("User:doesntmatter", claims2);
        AuthorizableRequestContext context2 = getAuthorizableRequestContext(principal2);

        List<Action> actions2 = List.of(new Action(AclOperation.WRITE,
                new ResourcePattern(ResourceType.TOPIC, "TestTopic", PatternType.LITERAL),
                1, true, true));

        Thread thread2 = new Thread(() -> {
            List<AuthorizationResult> result2 = authorizer.authorize(context2, actions2);
            assertEquals(List.of(AuthorizationResult.DENIED), result2);
        });

        thread1.start();
        thread2.start();

        thread1.join();
        thread2.join();
    }

    @Test
    void testHandleSuperUsersIndirectly() {
        setUpSuperUserConfigs();

        AuthorizableRequestContext context = new RequestContext(
                new RequestHeader(ApiKeys.CONTROLLER_REGISTRATION, (short) 1, "", 1),
                "",
                null,
                new KafkaPrincipal("User", "superuser"),
                new ListenerName(""),
                SecurityProtocol.PLAINTEXT,
                new ClientInformation("", ""),
                false);

        List<Action> actions = List.of(new Action(AclOperation.CLUSTER_ACTION,
                new ResourcePattern(ResourceType.CLUSTER, "kafka-cluster", PatternType.LITERAL),
                1, true, true));

        List<AuthorizationResult> result = authorizer.authorize(context, actions);
        assertEquals(List.of(AuthorizationResult.ALLOWED), result);
    }

    @Test
    void testExtractClusterAccesses() {
        List<String> accesses = List.of("Kafka_Cluster_create");
        List<ClusterAccess> result = CheetahKRaftAuthorizer.extractClusterAccesses(accesses, "Kafka_");
        assertFalse(result.isEmpty());
    }

    @Test
    void testCheckClusterJwtClaims() {
        setUpSuperUserConfigs();

        List<ClusterAccess> clusterAccesses = List.of(new ClusterAccess("READ"));
        ResourcePattern resourcePattern = new ResourcePattern(ResourceType.CLUSTER, "kafka-cluster", PatternType.LITERAL);
        Action action = new Action(AclOperation.READ, resourcePattern, 1, true, true);

        boolean result = CheetahKRaftAuthorizer.checkClusterJwtClaims(clusterAccesses, action);
        assertTrue(result);
    }

    @Test
    void testCheckTopicJwtClaims() {
        setUpSuperUserConfigs();

        List<TopicAccess> topicAccesses = extractTopicAccesses(List.of("*_all"), "");
        ResourcePattern resourcePattern = new ResourcePattern(ResourceType.TOPIC, "kafka-cluster", PatternType.LITERAL);
        Action action = new Action(AclOperation.READ, resourcePattern, 1, true, true);

        boolean result = CheetahKRaftAuthorizer.checkTopicJwtClaims(topicAccesses, action);
        assertTrue(result);
    }

    /**
     * Provides a stream of JWT claims and expected authorization results.
     * Each test case includes:
     * - The list of topic access roles from JWT claims.
     * - The type of resource (e.g., CLUSTER, TOPIC, GROUP).
     * - The name of the specific resource.
     * - The requested ACL operation (e.g., READ, WRITE, DESCRIBE).
     * - The expected authorization result (true = allowed, false = denied).
     *
     * @return A stream of test arguments covering various scenarios.
     */
    static Stream<Arguments> provideJwtClaimsAndExpectedResults() {
        return Stream.of(
                // Valid cases
                Arguments.of(List.of("*_all"), ResourceType.CLUSTER, "TestCluster", AclOperation.CREATE, true),
                Arguments.of(List.of("TestTopic*_read"), ResourceType.TOPIC, "TestTopic1", AclOperation.READ, true),
                Arguments.of(List.of("TestGroup*_describe"), ResourceType.GROUP, "TestGroup1", AclOperation.DESCRIBE, true),
                Arguments.of(List.of("TestGroup*_delete"), ResourceType.GROUP, "TestGroup1", AclOperation.DELETE, true),
                Arguments.of(List.of("TestCluster*_write"), ResourceType.CLUSTER, "TestCluster", AclOperation.IDEMPOTENT_WRITE, true),

                // Invalid cases
                Arguments.of(List.of("InvalidClusterAccess"), ResourceType.CLUSTER, "TestCluster", AclOperation.WRITE, false),
                Arguments.of(List.of("InvalidTopicAccess"), ResourceType.TOPIC, "TestTopic1", AclOperation.READ, false),
                Arguments.of(List.of("InvalidGroupAccess"), ResourceType.GROUP, "TestGroup1", AclOperation.DESCRIBE, false),
                Arguments.of(List.of("TestCluster*_DESCRIBE_CONFIGS"), ResourceType.CLUSTER, "TestCluster", AclOperation.IDEMPOTENT_WRITE, false),
                Arguments.of(List.of("TestTopic*_READ"), ResourceType.TOPIC, "TestTopic1", AclOperation.DELETE, false)
        );
    }

    /**
     * Parameterized test for checking topic JWT claims authorization.
     * <p>
     * This test runs multiple scenarios using parameterized testing:
     * - It extracts access permissions from the given JWT claims.
     * - It checks if the provided action (READ, CREATE, etc.) is authorized.
     * - It asserts whether the result matches the expected authorization outcome.
     * </p>
     *
     * @param topicAccesses  The list of topic access roles from JWT claims.
     * @param resourceType   The type of resource (e.g., CLUSTER, TOPIC, GROUP).
     * @param resourceName   The name of the specific resource being accessed.
     * @param operation      The requested ACL operation (e.g., READ, WRITE, DESCRIBE).
     * @param expectedResult The expected authorization result (true = allowed, false = denied).
     */
    @ParameterizedTest
    @MethodSource("provideJwtClaimsAndExpectedResults")
    @DisplayName("Check Topic JWT Claims Authorization")
    void testCheckTopicJwtClaims(List<String> topicAccesses, ResourceType resourceType, String resourceName, AclOperation operation, boolean expectedResult) {
        // Extract access permissions from the JWT claims
        List<TopicAccess> topicAccess = CheetahKRaftAuthorizer.extractTopicAccesses(topicAccesses, "");

        // Perform the authorization check
        boolean result = CheetahKRaftAuthorizer.checkTopicJwtClaims(topicAccess,
                new Action(operation, new ResourcePattern(resourceType, resourceName, PatternType.LITERAL), 1, false, false));

        // Verify that the authorization result matches expectations
        assertEquals(expectedResult, result,
                () -> String.format("Expected %s access to %s '%s' to be %s, but got %s",
                        operation, resourceType, resourceName, expectedResult ? "ALLOWED" : "DENIED", result ? "ALLOWED" : "DENIED"));
    }

    /**
     * Parameterized test for various authorization scenarios.
     *
     * @param user           The user to test.
     * @param claims         The claims to test.
     * @param operation      The operation to test.
     * @param expectedResult The expected result.
     */
    @ParameterizedTest
    @MethodSource("provideAuthorizationTestCases")
    @DisplayName("Authorization Test Scenarios")
    void testAuthorization(String user, ObjectNode claims, AclOperation operation, AuthorizationResult expectedResult) {
        setUpSuperUserConfigs();

        OAuthKafkaPrincipal principal = getKafkaPrincipal(user, claims);
        AuthorizableRequestContext context = getAuthorizableRequestContext(principal);

        List<Action> actions = List.of(new Action(operation,
                new ResourcePattern(ResourceType.GROUP, "", PatternType.LITERAL),
                1, true, true));

        List<AuthorizationResult> result = authorizer.authorize(context, actions);
        assertEquals(List.of(expectedResult), result);
    }

    /**
     * Provides a stream of test cases for authorization testing.
     * Each test case includes:
     * - The user to test.
     * - The claims to test.
     * - The operation to test.
     * - The expected result.
     *
     * @return A stream of test arguments covering various scenarios.
     */
    static Stream<Arguments> provideAuthorizationTestCases() {
        ObjectNode validClaims = JSONUtil.newObjectNode();
        validClaims.putArray("roles").add("Kafka_*_all");

        ObjectNode invalidClaims = JSONUtil.newObjectNode();
        invalidClaims.putArray("roles").add("Kafka_no_permission_to_read_from_this_topic");

        ObjectNode missingClaims = JSONUtil.newObjectNode();

        ObjectNode multipleValidClaims = JSONUtil.newObjectNode();
        multipleValidClaims.putArray("roles").add("Kafka_*_read").add("Kafka_*_write");

        ObjectNode multipleInvalidClaims = JSONUtil.newObjectNode();
        multipleInvalidClaims.putArray("roles").add("InvalidClaim1").add("InvalidClaim2");

        ObjectNode mixedClaims = JSONUtil.newObjectNode();
        mixedClaims.putArray("roles").add("Kafka_*_read").add("InvalidClaim");

        ObjectNode allowClaims = JSONUtil.newObjectNode();
        allowClaims.putArray("roles").add("Kafka_*_all");

        ObjectNode denyClaims = JSONUtil.newObjectNode();
        denyClaims.putArray("roles").add("Kafka_no_permission_to_read_from_this_topic");

        return Stream.of(
                Arguments.of("superuser", validClaims, AclOperation.READ, AuthorizationResult.ALLOWED),
                Arguments.of("validUser", validClaims, AclOperation.READ, AuthorizationResult.ALLOWED),
                Arguments.of("invalidUser", invalidClaims, AclOperation.READ, AuthorizationResult.DENIED),
                Arguments.of("missingUser", missingClaims, AclOperation.READ, AuthorizationResult.DENIED),
                Arguments.of("multipleValidUser", multipleValidClaims, AclOperation.READ, AuthorizationResult.ALLOWED),
                Arguments.of("multipleInvalidUser", multipleInvalidClaims, AclOperation.READ, AuthorizationResult.DENIED),
                Arguments.of("mixedUser", mixedClaims, AclOperation.READ, AuthorizationResult.ALLOWED),
                Arguments.of("User:doesntmatter", allowClaims, AclOperation.READ, AuthorizationResult.ALLOWED),
                Arguments.of("User:doesntmatter", denyClaims, AclOperation.READ, AuthorizationResult.DENIED)
        );
    }

}