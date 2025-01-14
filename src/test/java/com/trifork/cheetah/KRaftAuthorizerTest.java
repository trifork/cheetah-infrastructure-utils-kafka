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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static com.trifork.cheetah.CheetahKRaftAuthorizer.checkClusterJwtClaims;
import static com.trifork.cheetah.CheetahKRaftAuthorizer.checkTopicJwtClaims;
import static com.trifork.cheetah.CheetahKRaftAuthorizer.extractTopicAccesses;
import static com.trifork.cheetah.CheetahKRaftAuthorizer.extractClusterAccesses;

import com.fasterxml.jackson.databind.node.ObjectNode;

import io.strimzi.kafka.oauth.common.JSONUtil;
import io.strimzi.kafka.oauth.server.OAuthKafkaPrincipal;

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
                Assertions.assertEquals(result, List.of(AuthorizationResult.ALLOWED));

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
                                                new HashSet<>(Arrays.asList("")),
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
                Assertions.assertEquals(result, List.of(AuthorizationResult.ALLOWED));

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
                                                new HashSet<>(Arrays.asList("")),
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
                Assertions.assertEquals(result, List.of(AuthorizationResult.DENIED));

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

}
