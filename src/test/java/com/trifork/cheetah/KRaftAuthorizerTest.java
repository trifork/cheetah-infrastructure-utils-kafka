package com.trifork.cheetah;

import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.acl.AccessControlEntry;
import org.apache.kafka.common.acl.AclBinding;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.acl.AclPermissionType;
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
import org.apache.kafka.metadata.authorizer.StandardAuthorizer;
import org.apache.kafka.server.authorizer.Action;
import org.apache.kafka.server.authorizer.AuthorizableRequestContext;
import org.apache.kafka.server.authorizer.AuthorizationResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.trifork.cheetah.CheetahKRaftAuthorizer.checkClusterJwtClaims;
import static com.trifork.cheetah.CheetahKRaftAuthorizer.checkTopicJwtClaims;
import static com.trifork.cheetah.CheetahKRaftAuthorizer.extractTopicAccesses;
import static com.trifork.cheetah.CheetahKRaftAuthorizer.extractClusterAccesses;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.fasterxml.jackson.databind.node.ObjectNode;

import io.strimzi.kafka.oauth.common.JSONUtil;
import io.strimzi.kafka.oauth.server.OAuthKafkaPrincipal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class KRaftAuthorizerTest {

        @Test
        void CheckAuthorizeFlowSuperUser() {
                CheetahKRaftAuthorizer authorizer = new CheetahKRaftAuthorizer();
                authorizer.configure(Map.of("cheetah.authorization.claim.name", "roles",
                                "cheetah.authorization.claim.is-list", "true",
                                "cheetah.authorization.prefix", "Kafka_",
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
                assertEquals(result, List.of(AuthorizationResult.ALLOWED));

                try {
                        authorizer.close();
                } catch (Exception ignore) { }
        }

        @Test
        void CheckAuthorizeFlowPerformanceTesterAllowTopic() {
                CheetahKRaftAuthorizer authorizer = new CheetahKRaftAuthorizer();
                authorizer.configure(Map.of("cheetah.authorization.claim.name", "roles",
                                "cheetah.authorization.claim.is-list", "true",
                                "cheetah.authorization.prefix", "Kafka_",
                                "super.users", "User:doesntmatter"));

                ObjectNode claims = JSONUtil.newObjectNode();
                claims.putArray("roles").add("Kafka_*_all");

                OAuthKafkaPrincipal principal = new OAuthKafkaPrincipal("", "",
                                new MockBearerTokenWithPayload("User:doesntmatter",
                                                new HashSet<>(List.of("")),
                                                System.currentTimeMillis(), System.currentTimeMillis() + 60000, null,
                                                "", JSONUtil.asJson("{}"),
                                                claims));

                AuthorizableRequestContext context = new RequestContext(
                                new RequestHeader(ApiKeys.OFFSET_COMMIT, (short) 8,
                                                "", 1),
                                "",
                                null,
                                principal,
                                new ListenerName(""),
                                SecurityProtocol.PLAINTEXT,
                                new ClientInformation("", ""),
                                false);
                List<Action> actions = List.of(new Action(AclOperation.READ,
                                new ResourcePattern(ResourceType.GROUP, "", PatternType.LITERAL),
                                1, true, true));

                List<AuthorizationResult> result = authorizer.authorize(context, actions);
                assertEquals(result, List.of(AuthorizationResult.ALLOWED));

                try {
                        authorizer.close();
                } catch (Exception ignore) { }
        }

        @Test
        void CheckAuthorizeFlowPerformanceTesterDenyTopic() {
                CheetahKRaftAuthorizer authorizer = new CheetahKRaftAuthorizer();
                authorizer.configure(Map.of("cheetah.authorization.claim.name", "roles",
                                "cheetah.authorization.claim.is-list", "true",
                                "cheetah.authorization.prefix", "Kafka_",
                                "super.users", "User:doesntmatter"));

                ObjectNode claims = JSONUtil.newObjectNode();
                claims.putArray("roles").add("Kafka_no_permission_to_read_from_this_topic");

                OAuthKafkaPrincipal principal = new OAuthKafkaPrincipal("", "",
                                new MockBearerTokenWithPayload("User:doesntmatter",
                                                new HashSet<>(List.of("")),
                                                System.currentTimeMillis(), System.currentTimeMillis() + 60000, null,
                                                "", JSONUtil.asJson("{}"),
                                                claims));

                AuthorizableRequestContext context = new RequestContext(
                                new RequestHeader(ApiKeys.OFFSET_COMMIT, (short) 8,
                                                "", 1),
                                "",
                                null,
                                principal,
                                new ListenerName(""),
                                SecurityProtocol.PLAINTEXT,
                                new ClientInformation("", ""),
                                false);

                List<Action> actions = List.of(new Action(AclOperation.READ,
                                new ResourcePattern(ResourceType.GROUP, "", PatternType.LITERAL),
                                1, true, true));

                List<AuthorizationResult> result = authorizer.authorize(context, actions);
                assertEquals(result, List.of(AuthorizationResult.DENIED));

                try {
                        authorizer.close();
                } catch (Exception ignore) { }
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
                Assertions.assertFalse(result);
        }

        @Test
        void testCheckJwtClaimsAllOneTopic() {
                List<TopicAccess> topicAccess = extractTopicAccesses(List.of("MyTopic*_all"), "");
                boolean result = checkTopicJwtClaims(topicAccess, new Action(AclOperation.DESCRIBE,
                                new ResourcePattern(ResourceType.TOPIC, "NotMyTopic", PatternType.LITERAL), 1, false,
                                false));
                Assertions.assertFalse(result);
        }

        @Test
        void testCheckJwtClaimsDeny() {
                List<TopicAccess> topicAccess = extractTopicAccesses(List.of("MyTopic*_describe"), "");
                boolean result = checkTopicJwtClaims(topicAccess, new Action(AclOperation.DESCRIBE,
                                new ResourcePattern(ResourceType.TOPIC, "NotMyTopic", PatternType.LITERAL), 1, false,
                                false));
                Assertions.assertFalse(result);
        }

        @Test
        void testCheckJwtClaimsDenyWithoutWildcard() {
                List<TopicAccess> topicAccess = extractTopicAccesses(List.of("MyTopic_describe"), "");
                boolean result = checkTopicJwtClaims(topicAccess, new Action(AclOperation.DESCRIBE,
                                new ResourcePattern(ResourceType.TOPIC, "MyTopicTest", PatternType.LITERAL), 1, false,
                                false));
                Assertions.assertFalse(result);
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
                CheetahKRaftAuthorizer authorizer = new CheetahKRaftAuthorizer();
                authorizer.configure(Map.of("cheetah.authorization.claim.name", "roles",
                        "cheetah.authorization.claim.is-list", "true",
                        "cheetah.authorization.prefix", "Kafka_",
                        "super.users", "User:superuser"));

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
                assertEquals(result, List.of(AuthorizationResult.ALLOWED));

                try {
                        authorizer.close();
                } catch (Exception ignore) { }
        }

        @Test
        void testInvalidJwtClaims() {
                CheetahKRaftAuthorizer authorizer = new CheetahKRaftAuthorizer();
                authorizer.configure(Map.of("cheetah.authorization.claim.name", "roles",
                        "cheetah.authorization.claim.is-list", "true",
                        "cheetah.authorization.prefix", "Kafka_",
                        "super.users", "User:doesntmatter"));

                ObjectNode claims = JSONUtil.newObjectNode();
                claims.putArray("roles").add("InvalidClaim");

                OAuthKafkaPrincipal principal = new OAuthKafkaPrincipal("", "",
                        new MockBearerTokenWithPayload("User:doesntmatter",
                                new HashSet<>(List.of("")),
                                System.currentTimeMillis(), System.currentTimeMillis() + 60000, null,
                                "", JSONUtil.asJson("{}"),
                                claims));

                AuthorizableRequestContext context = new RequestContext(
                        new RequestHeader(ApiKeys.OFFSET_COMMIT, (short) 8, "", 1),
                        "",
                        null,
                        principal,
                        new ListenerName(""),
                        SecurityProtocol.PLAINTEXT,
                        new ClientInformation("", ""),
                        false);
                List<Action> actions = List.of(new Action(AclOperation.READ,
                        new ResourcePattern(ResourceType.GROUP, "", PatternType.LITERAL),
                        1, true, true));

                List<AuthorizationResult> result = authorizer.authorize(context, actions);
                assertEquals(result, List.of(AuthorizationResult.DENIED));

                try {
                        authorizer.close();
                } catch (Exception ignore) { }
        }

        @Test
        void testMissingJwtClaims() {
                CheetahKRaftAuthorizer authorizer = new CheetahKRaftAuthorizer();
                authorizer.configure(Map.of("cheetah.authorization.claim.name", "roles",
                        "cheetah.authorization.claim.is-list", "true",
                        "cheetah.authorization.prefix", "Kafka_",
                        "super.users", "User:doesntmatter"));

                ObjectNode claims = JSONUtil.newObjectNode();

                OAuthKafkaPrincipal principal = new OAuthKafkaPrincipal("", "",
                        new MockBearerTokenWithPayload("User:doesntmatter",
                                new HashSet<>(List.of("")),
                                System.currentTimeMillis(), System.currentTimeMillis() + 60000, null,
                                "", JSONUtil.asJson("{}"),
                                claims));

                AuthorizableRequestContext context = new RequestContext(
                        new RequestHeader(ApiKeys.OFFSET_COMMIT, (short) 8, "", 1),
                        "",
                        null,
                        principal,
                        new ListenerName(""),
                        SecurityProtocol.PLAINTEXT,
                        new ClientInformation("", ""),
                        false);
                List<Action> actions = List.of(new Action(AclOperation.READ,
                        new ResourcePattern(ResourceType.GROUP, "", PatternType.LITERAL),
                        1, true, true));

                List<AuthorizationResult> result = authorizer.authorize(context, actions);
                assertEquals(result, List.of(AuthorizationResult.DENIED));

                try {
                        authorizer.close();
                } catch (Exception ignore) { }
        }

        @Test
        void testValidJwtClaims() {
                CheetahKRaftAuthorizer authorizer = new CheetahKRaftAuthorizer();
                authorizer.configure(Map.of("cheetah.authorization.claim.name", "roles",
                        "cheetah.authorization.claim.is-list", "true",
                        "cheetah.authorization.prefix", "Kafka_",
                        "super.users", "User:doesntmatter"));

                ObjectNode claims = JSONUtil.newObjectNode();
                claims.putArray("roles").add("Kafka_*_all");

                OAuthKafkaPrincipal principal = new OAuthKafkaPrincipal("", "",
                        new MockBearerTokenWithPayload("User:doesntmatter",
                                new HashSet<>(List.of("")),
                                System.currentTimeMillis(), System.currentTimeMillis() + 60000, null,
                                "", JSONUtil.asJson("{}"),
                                claims));

                AuthorizableRequestContext context = new RequestContext(
                        new RequestHeader(ApiKeys.OFFSET_COMMIT, (short) 8, "", 1),
                        "",
                        null,
                        principal,
                        new ListenerName(""),
                        SecurityProtocol.PLAINTEXT,
                        new ClientInformation("", ""),
                        false);
                List<Action> actions = List.of(new Action(AclOperation.READ,
                        new ResourcePattern(ResourceType.GROUP, "", PatternType.LITERAL),
                        1, true, true));

                List<AuthorizationResult> result = authorizer.authorize(context, actions);
                assertEquals(result, List.of(AuthorizationResult.ALLOWED));

                try {
                        authorizer.close();
                } catch (Exception ignore) { }
        }


        @Test
        void testRaceCondition() throws InterruptedException {
                CheetahKRaftAuthorizer authorizer = new CheetahKRaftAuthorizer();
                authorizer.configure(Map.of("cheetah.authorization.claim.name", "roles",
                        "cheetah.authorization.claim.is-list", "true",
                        "cheetah.authorization.prefix", "Kafka_",
                        "super.users", "User:doesntmatter"));

                ObjectNode claims1 = JSONUtil.newObjectNode();
                claims1.putArray("roles").add("Kafka_*_write");

                OAuthKafkaPrincipal principal1 = new OAuthKafkaPrincipal("", "",
                        new MockBearerTokenWithPayload("User:doesntmatter",
                                new HashSet<>(List.of("")),
                                System.currentTimeMillis(), System.currentTimeMillis() + 60000, null,
                                "", JSONUtil.asJson("{}"),
                                claims1));

                AuthorizableRequestContext context1 = new RequestContext(
                        new RequestHeader(ApiKeys.OFFSET_COMMIT, (short) 8, "", 1),
                        "",
                        null,
                        principal1,
                        new ListenerName(""),
                        SecurityProtocol.PLAINTEXT,
                        new ClientInformation("", ""),
                        false);

                List<Action> actions1 = List.of(new Action(AclOperation.WRITE,
                        new ResourcePattern(ResourceType.TOPIC, "TestTopic", PatternType.LITERAL),
                        1, true, true));

                Thread thread1 = new Thread(() -> {
                        List<AuthorizationResult> result1 = authorizer.authorize(context1, actions1);
                        assertEquals(result1, List.of(AuthorizationResult.ALLOWED));
                });

                ObjectNode claims2 = JSONUtil.newObjectNode();
                claims2.putArray("roles").add("Kafka_*_read");

                OAuthKafkaPrincipal principal2 = new OAuthKafkaPrincipal("", "",
                        new MockBearerTokenWithPayload("User:doesntmatter",
                                new HashSet<>(List.of("")),
                                System.currentTimeMillis(), System.currentTimeMillis() + 60000, null,
                                "", JSONUtil.asJson("{}"),
                                claims2));

                AuthorizableRequestContext context2 = new RequestContext(
                        new RequestHeader(ApiKeys.OFFSET_COMMIT, (short) 8, "", 1),
                        "",
                        null,
                        principal2,
                        new ListenerName(""),
                        SecurityProtocol.PLAINTEXT,
                        new ClientInformation("", ""),
                        false);

                List<Action> actions2 = List.of(new Action(AclOperation.WRITE,
                        new ResourcePattern(ResourceType.TOPIC, "TestTopic", PatternType.LITERAL),
                        1, true, true));

                Thread thread2 = new Thread(() -> {
                        List<AuthorizationResult> result2 = authorizer.authorize(context2, actions2);
                        assertEquals(result2, List.of(AuthorizationResult.DENIED));
                });

                thread1.start();
                thread2.start();

                thread1.join();
                thread2.join();

                try {
                        authorizer.close();
                } catch (Exception ignore) { }
        }

        @Test
        void testMultipleValidClaims() {
                CheetahKRaftAuthorizer authorizer = new CheetahKRaftAuthorizer();
                authorizer.configure(Map.of("cheetah.authorization.claim.name", "roles",
                        "cheetah.authorization.claim.is-list", "true",
                        "cheetah.authorization.prefix", "Kafka_",
                        "super.users", "User:doesntmatter"));

                ObjectNode claims = JSONUtil.newObjectNode();
                claims.putArray("roles").add("Kafka_*_read").add("Kafka_*_write");

                OAuthKafkaPrincipal principal = new OAuthKafkaPrincipal("", "",
                        new MockBearerTokenWithPayload("User:doesntmatter",
                                new HashSet<>(List.of("")),
                                System.currentTimeMillis(), System.currentTimeMillis() + 60000, null,
                                "", JSONUtil.asJson("{}"),
                                claims));

                AuthorizableRequestContext context = new RequestContext(
                        new RequestHeader(ApiKeys.OFFSET_COMMIT, (short) 8, "", 1),
                        "",
                        null,
                        principal,
                        new ListenerName(""),
                        SecurityProtocol.PLAINTEXT,
                        new ClientInformation("", ""),
                        false);
                List<Action> actions = List.of(new Action(AclOperation.READ,
                        new ResourcePattern(ResourceType.GROUP, "", PatternType.LITERAL),
                        1, true, true));

                List<AuthorizationResult> result = authorizer.authorize(context, actions);
                assertEquals(result, List.of(AuthorizationResult.ALLOWED));

                try {
                        authorizer.close();
                } catch (Exception ignore) { }
        }

        @Test
        void testMultipleInvalidClaims() {
                CheetahKRaftAuthorizer authorizer = new CheetahKRaftAuthorizer();
                authorizer.configure(Map.of("cheetah.authorization.claim.name", "roles",
                        "cheetah.authorization.claim.is-list", "true",
                        "cheetah.authorization.prefix", "Kafka_",
                        "super.users", "User:doesntmatter"));

                ObjectNode claims = JSONUtil.newObjectNode();
                claims.putArray("roles").add("InvalidClaim1").add("InvalidClaim2");

                OAuthKafkaPrincipal principal = new OAuthKafkaPrincipal("", "",
                        new MockBearerTokenWithPayload("User:doesntmatter",
                                new HashSet<>(List.of("")),
                                System.currentTimeMillis(), System.currentTimeMillis() + 60000, null,
                                "", JSONUtil.asJson("{}"),
                                claims));

                AuthorizableRequestContext context = new RequestContext(
                        new RequestHeader(ApiKeys.OFFSET_COMMIT, (short) 8, "", 1),
                        "",
                        null,
                        principal,
                        new ListenerName(""),
                        SecurityProtocol.PLAINTEXT,
                        new ClientInformation("", ""),
                        false);
                List<Action> actions = List.of(new Action(AclOperation.READ,
                        new ResourcePattern(ResourceType.GROUP, "", PatternType.LITERAL),
                        1, true, true));

                List<AuthorizationResult> result = authorizer.authorize(context, actions);
                assertEquals(result, List.of(AuthorizationResult.DENIED));

                try {
                        authorizer.close();
                } catch (Exception ignore) { }
        }

        @Test
        void testMixedValidAndInvalidClaims() {
                CheetahKRaftAuthorizer authorizer = new CheetahKRaftAuthorizer();
                authorizer.configure(Map.of("cheetah.authorization.claim.name", "roles",
                        "cheetah.authorization.claim.is-list", "true",
                        "cheetah.authorization.prefix", "Kafka_",
                        "super.users", "User:doesntmatter"));

                ObjectNode claims = JSONUtil.newObjectNode();
                claims.putArray("roles").add("Kafka_*_read").add("InvalidClaim");

                OAuthKafkaPrincipal principal = new OAuthKafkaPrincipal("", "",
                        new MockBearerTokenWithPayload("User:doesntmatter",
                                new HashSet<>(List.of("")),
                                System.currentTimeMillis(), System.currentTimeMillis() + 60000, null,
                                "", JSONUtil.asJson("{}"),
                                claims));


                AuthorizableRequestContext context = new RequestContext(
                        new RequestHeader(ApiKeys.OFFSET_COMMIT, (short) 8, "", 1),
                        "",
                        null,
                        principal,
                        new ListenerName(""),
                        SecurityProtocol.PLAINTEXT,
                        new ClientInformation("", ""),
                        false);
                List<Action> actions = List.of(new Action(AclOperation.READ,
                        new ResourcePattern(ResourceType.GROUP, "", PatternType.LITERAL),
                        1, true, true));

                List<AuthorizationResult> result = authorizer.authorize(context, actions);
                assertEquals(result, List.of(AuthorizationResult.ALLOWED));

                try {
                        authorizer.close();
                } catch (Exception ignore) {

                }
        }
        /**
         * Provides test cases for JWT claim validation.
         * Each case includes:
         * - The JWT claims (roles) assigned to the user.
         * - The resource type (CLUSTER, TOPIC, GROUP).
         * - The specific resource name.
         * - The requested ACL operation (READ, CREATE, DESCRIBE, etc.).
         * - The expected authorization result (true = allowed, false = denied).
         *
         * @return A stream of test arguments covering both valid and invalid cases.
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
         * Tests whether the {@code checkTopicJwtClaims} method correctly authorizes or denies access
         * based on the given JWT claims and requested action.
         * <p>
         * This test runs multiple scenarios using parameterized testing:
         * - It extracts access permissions from the given JWT claims.
         * - It checks if the provided action (READ, CREATE, etc.) is authorized.
         * - It asserts whether the result matches the expected authorization outcome.
         * </p>
         *
         * @param topicAccesses The list of JWT roles/claims assigned to the user.
         * @param resourceType  The type of Kafka resource (CLUSTER, TOPIC, GROUP).
         * @param resourceName  The name of the specific resource being accessed.
         * @param operation     The ACL operation the user is attempting (READ, WRITE, etc.).
         * @param expectedResult The expected result (true = authorized, false = denied).
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




}
