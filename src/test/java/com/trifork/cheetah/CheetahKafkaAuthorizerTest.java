package com.trifork.cheetah;

import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.resource.PatternType;
import org.apache.kafka.common.resource.ResourcePattern;
import org.apache.kafka.common.resource.ResourceType;
import org.apache.kafka.server.authorizer.Action;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.trifork.cheetah.accessclaim.ClusterAccessExtractor;
import com.trifork.cheetah.accessclaim.TopicAccessExtractor;
import com.trifork.cheetah.clusterauthorization.ClusterAccess;
import com.trifork.cheetah.topicauthorization.TopicAccess;

import static com.trifork.cheetah.CheetahKafkaAuthorizer.checkClusterJwtClaims;
import static com.trifork.cheetah.CheetahKafkaAuthorizer.checkTopicJwtClaims;

import java.util.List;

class CheetahKafkaAuthorizerTest {

        private TopicAccessExtractor topicAccessExtractor;
        private ClusterAccessExtractor clusterAccessExtractor;

        @BeforeEach
        void setUp() {
                topicAccessExtractor = new TopicAccessExtractor();
                clusterAccessExtractor = new ClusterAccessExtractor();
        }

        @Test
        void testWildcardAllOperationsAllowWriteOnAnyTopic() {
                assertAuthorization("*_all", "", AclOperation.WRITE, "JobNameInputTopic", true);
        }

        private void assertAuthorization(String claim, String prefix, AclOperation operation, String resourceName,
                        boolean expectedResult) {
                final List<TopicAccess> topicAccess = topicAccessExtractor.extractAccesses(List.of(claim), prefix);
                final boolean result = checkTopicJwtClaims(topicAccess, new Action(operation,
                                new ResourcePattern(ResourceType.TOPIC, resourceName, PatternType.LITERAL), 1, false,
                                false));
                Assertions.assertEquals(expectedResult, result);
        }

        @Test
        void checkJwtClaimsAllAllow() {
                final List<TopicAccess> topicAccess = topicAccessExtractor.extractAccesses(List.of("*_all"), "");
                final boolean result = checkTopicJwtClaims(topicAccess, new Action(AclOperation.WRITE,
                                new ResourcePattern(ResourceType.TOPIC, "JobNameInputTopic", PatternType.LITERAL), 1,
                                false, false));
                Assertions.assertTrue(result);
        }

        @Test
        void testCheckJwtClaimsAllOneTopicAllow() {
                final List<TopicAccess> topicAccess = topicAccessExtractor.extractAccesses(List.of("MyTopic*_All"), "");
                final boolean result = checkTopicJwtClaims(topicAccess, new Action(AclOperation.WRITE,
                                new ResourcePattern(ResourceType.TOPIC, "MyTopicTest", PatternType.LITERAL), 1, false,
                                false));
                Assertions.assertTrue(result);
        }

        @Test
        void testCheckJwtClaimsAllOneTopicDeny() {
                final List<TopicAccess> topicAccess = topicAccessExtractor.extractAccesses(List.of("MyTopic*_read"),
                                "");
                final boolean result = checkTopicJwtClaims(topicAccess, new Action(AclOperation.WRITE,
                                new ResourcePattern(ResourceType.TOPIC, "MyTopicTest", PatternType.LITERAL), 1, false,
                                false));
                Assertions.assertFalse(result);
        }

        @Test
        void testCheckJwtClaimsAllOneTopic() {
                final List<TopicAccess> topicAccess = topicAccessExtractor.extractAccesses(List.of("MyTopic*_all"), "");
                final boolean result = checkTopicJwtClaims(topicAccess, new Action(AclOperation.DESCRIBE,
                                new ResourcePattern(ResourceType.TOPIC, "NotMyTopic", PatternType.LITERAL), 1, false,
                                false));
                Assertions.assertFalse(result);
        }

        @Test
        void testCheckJwtClaimsDeny() {
                final List<TopicAccess> topicAccess = topicAccessExtractor.extractAccesses(List.of("MyTopic*_describe"),
                                "");
                final boolean result = checkTopicJwtClaims(topicAccess, new Action(AclOperation.DESCRIBE,
                                new ResourcePattern(ResourceType.TOPIC, "NotMyTopic", PatternType.LITERAL), 1, false,
                                false));
                Assertions.assertFalse(result);
        }

        @Test
        void testCheckJwtClaimsDenyWithoutWildcard() {
                final List<TopicAccess> topicAccess = topicAccessExtractor.extractAccesses(List.of("MyTopic_describe"),
                                "");
                final boolean result = checkTopicJwtClaims(topicAccess, new Action(AclOperation.DESCRIBE,
                                new ResourcePattern(ResourceType.TOPIC, "MyTopicTest", PatternType.LITERAL), 1, false,
                                false));
                Assertions.assertFalse(result);
        }

        @Test
        void testCheckJwtClaimsAllowWithStartWildcard() {
                final List<TopicAccess> topicAccess = topicAccessExtractor.extractAccesses(List.of("*MyTopic_describe"),
                                "");
                final boolean result = checkTopicJwtClaims(topicAccess, new Action(AclOperation.DESCRIBE,
                                new ResourcePattern(ResourceType.TOPIC, "TestMyTopic", PatternType.LITERAL), 1, false,
                                false));
                Assertions.assertTrue(result);
        }

        @Test
        void testClaimWithPrefix() {
                final List<TopicAccess> topicAccess = topicAccessExtractor.extractAccesses(
                                List.of("Kafka_*MyTopic_describe"),
                                "Kafka_");
                final boolean result = checkTopicJwtClaims(topicAccess, new Action(AclOperation.DESCRIBE,
                                new ResourcePattern(ResourceType.TOPIC, "TestMyTopic", PatternType.LITERAL), 1, false,
                                false));
                Assertions.assertTrue(result);
        }

        @Test
        void testClaimsWithPrefix() {
                final List<TopicAccess> topicAccess = topicAccessExtractor.extractAccesses(
                                List.of("Kafka_Demo1_Write", "Kafka_Demo3_Write", "Kafka_Demo2_Write",
                                                "Kafka_Demo1_Read"),
                                "Kafka_");
                final boolean describeAccess = checkTopicJwtClaims(topicAccess, new Action(AclOperation.DESCRIBE,
                                new ResourcePattern(ResourceType.TOPIC, "Demo1", PatternType.LITERAL), 1, false,
                                false));
                final boolean readAccess = checkTopicJwtClaims(topicAccess, new Action(AclOperation.READ,
                                new ResourcePattern(ResourceType.TOPIC, "Demo1", PatternType.LITERAL), 1, false,
                                false));
                Assertions.assertTrue(describeAccess);
                Assertions.assertTrue(readAccess);
        }

        @Test
        void testNonKafkaRolesFirst() {
                final List<TopicAccess> topicAccess = topicAccessExtractor
                                .extractAccesses(List.of("NotAKafkaRole", "Kafka_Demo1_Write",
                                                "Kafka_Demo3_Write", "Kafka_Demo2_Write", "Kafka_Demo1_Read"),
                                                "Kafka_");
                final boolean describeAccess = checkTopicJwtClaims(topicAccess, new Action(AclOperation.DESCRIBE,
                                new ResourcePattern(ResourceType.TOPIC, "Demo1", PatternType.LITERAL), 1, false,
                                false));
                final boolean readAccess = checkTopicJwtClaims(topicAccess, new Action(AclOperation.READ,
                                new ResourcePattern(ResourceType.TOPIC, "Demo1", PatternType.LITERAL), 1, false,
                                false));
                Assertions.assertTrue(describeAccess);
                Assertions.assertTrue(readAccess);
        }

        @Test
        void testTopicWithUnderscore() {
                final List<TopicAccess> topicAccess = topicAccessExtractor.extractAccesses(
                                List.of("Kafka_Demo1_Topic_Read"),
                                "Kafka_");
                final boolean readAccess = checkTopicJwtClaims(topicAccess, new Action(AclOperation.READ,
                                new ResourcePattern(ResourceType.TOPIC, "Demo1_Topic", PatternType.LITERAL), 1, false,
                                false));
                Assertions.assertTrue(readAccess);
        }

        @Test
        void testIdempotentWrite() {
                final List<TopicAccess> topicAccess = topicAccessExtractor.extractAccesses(
                                List.of("Kafka_Demo1_Topic_Write"),
                                "Kafka_");
                final boolean readAccess = checkTopicJwtClaims(topicAccess, new Action(AclOperation.IDEMPOTENT_WRITE,
                                new ResourcePattern(ResourceType.CLUSTER, "Demo1_Topic", PatternType.LITERAL), 1, false,
                                false));
                Assertions.assertTrue(readAccess);
        }

        @Test
        void testDescribe() {
                final List<TopicAccess> topicAccess = topicAccessExtractor.extractAccesses(List.of("*_all"), "");
                final boolean readAccess = checkTopicJwtClaims(topicAccess, new Action(AclOperation.DESCRIBE,
                                new ResourcePattern(ResourceType.TOPIC, "Demo1_Topic", PatternType.LITERAL), 1, false,
                                false));
                Assertions.assertTrue(readAccess);
        }

        @Test
        void testIdempotentWriteWithWildcard() {
                final List<TopicAccess> topicAccess = topicAccessExtractor.extractAccesses(List.of("*_all"), "");
                final boolean readAccess = checkTopicJwtClaims(topicAccess, new Action(AclOperation.IDEMPOTENT_WRITE,
                                new ResourcePattern(ResourceType.CLUSTER, "Demo1_Topic", PatternType.LITERAL), 1, false,
                                false));
                Assertions.assertTrue(readAccess);
        }

        @Test
        void testDescribeConfigClaimWithCluster() {
                final List<ClusterAccess> topicAccess = clusterAccessExtractor
                                .extractAccesses(List.of("Kafka_Cluster_describe-configs"), "Kafka_");
                final boolean result = checkClusterJwtClaims(topicAccess, new Action(AclOperation.DESCRIBE_CONFIGS,
                                new ResourcePattern(ResourceType.CLUSTER, "Cluster", PatternType.LITERAL), 1, false,
                                false));
                Assertions.assertTrue(result);
        }

}