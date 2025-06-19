package com.trifork.cheetah;

import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.resource.PatternType;
import org.apache.kafka.common.resource.ResourcePattern;
import org.apache.kafka.common.resource.ResourceType;
import org.apache.kafka.server.authorizer.Action;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.trifork.cheetah.CheetahKafkaAuthorizer.checkClusterJwtClaims;
import static com.trifork.cheetah.CheetahKafkaAuthorizer.checkTopicJwtClaims;
import static com.trifork.cheetah.CheetahKafkaAuthorizer.extractTopicAccesses;
import static com.trifork.cheetah.CheetahKafkaAuthorizer.extractClusterAccesses;
import static com.trifork.cheetah.CheetahKafkaAuthorizer.checkGroupJwtClaims;
import static com.trifork.cheetah.CheetahKafkaAuthorizer.extractGroupAccesses;

class CheetahKafkaAuthorizerTest {

    // Test for topic ACL support

    @Test
    void CheckJwtClaimsAllAllow() {
        List<TopicAccess> topicAccess = extractTopicAccesses(List.of("*_all"), "");
        boolean result = checkTopicJwtClaims(topicAccess, new Action(AclOperation.WRITE,
                new ResourcePattern(ResourceType.TOPIC, "JobNameInputTopic", PatternType.LITERAL), 1, false, false));
        Assertions.assertTrue(result);
    }

    @Test
    void testCheckJwtClaimsAllOneTopicAllow() {
        List<TopicAccess> topicAccess = extractTopicAccesses(List.of("MyTopic*_All"), "");
        boolean result = checkTopicJwtClaims(topicAccess, new Action(AclOperation.WRITE,
                new ResourcePattern(ResourceType.TOPIC, "MyTopicTest", PatternType.LITERAL), 1, false, false));
        Assertions.assertTrue(result);
    }

    @Test
    void testCheckJwtClaimsAllOneTopicDeny() {
        List<TopicAccess> topicAccess = extractTopicAccesses(List.of("MyTopic*_read"), "");
        boolean result = checkTopicJwtClaims(topicAccess, new Action(AclOperation.WRITE,
                new ResourcePattern(ResourceType.TOPIC, "MyTopicTest", PatternType.LITERAL), 1, false, false));
        Assertions.assertFalse(result);
    }

    @Test
    void testCheckJwtClaimsAllOneTopic() {
        List<TopicAccess> topicAccess = extractTopicAccesses(List.of("MyTopic*_all"), "");
        boolean result = checkTopicJwtClaims(topicAccess, new Action(AclOperation.DESCRIBE,
                new ResourcePattern(ResourceType.TOPIC, "NotMyTopic", PatternType.LITERAL), 1, false, false));
        Assertions.assertFalse(result);
    }

    @Test
    void testCheckJwtClaimsDeny() {
        List<TopicAccess> topicAccess = extractTopicAccesses(List.of("MyTopic*_describe"), "");
        boolean result = checkTopicJwtClaims(topicAccess, new Action(AclOperation.DESCRIBE,
                new ResourcePattern(ResourceType.TOPIC, "NotMyTopic", PatternType.LITERAL), 1, false, false));
        Assertions.assertFalse(result);
    }

    @Test
    void testCheckJwtClaimsDenyWithoutWildcard() {
        List<TopicAccess> topicAccess = extractTopicAccesses(List.of("MyTopic_describe"), "");
        boolean result = checkTopicJwtClaims(topicAccess, new Action(AclOperation.DESCRIBE,
                new ResourcePattern(ResourceType.TOPIC, "MyTopicTest", PatternType.LITERAL), 1, false, false));
        Assertions.assertFalse(result);
    }

    @Test
    void testCheckJwtClaimsAllowWithStartWildcard() {
        List<TopicAccess> topicAccess = extractTopicAccesses(List.of("*MyTopic_describe"), "");
        boolean result = checkTopicJwtClaims(topicAccess, new Action(AclOperation.DESCRIBE,
                new ResourcePattern(ResourceType.TOPIC, "TestMyTopic", PatternType.LITERAL), 1, false, false));
        Assertions.assertTrue(result);
    }

    @Test
    void testClaimWithPrefix() {
        List<TopicAccess> topicAccess = extractTopicAccesses(List.of("Kafka_*MyTopic_describe"), "Kafka_");
        boolean result = checkTopicJwtClaims(topicAccess, new Action(AclOperation.DESCRIBE,
                new ResourcePattern(ResourceType.TOPIC, "TestMyTopic", PatternType.LITERAL), 1, false, false));
        Assertions.assertTrue(result);
    }

    @Test
    void testClaimsWithPrefix() {
        List<TopicAccess> topicAccess = extractTopicAccesses(
                List.of("Kafka_Demo1_Write", "Kafka_Demo3_Write", "Kafka_Demo2_Write", "Kafka_Demo1_Read"), "Kafka_");
        boolean describeAccess = checkTopicJwtClaims(topicAccess, new Action(AclOperation.DESCRIBE,
                new ResourcePattern(ResourceType.TOPIC, "Demo1", PatternType.LITERAL), 1, false, false));
        boolean readAccess = checkTopicJwtClaims(topicAccess, new Action(AclOperation.READ,
                new ResourcePattern(ResourceType.TOPIC, "Demo1", PatternType.LITERAL), 1, false, false));
        Assertions.assertTrue(describeAccess);
        Assertions.assertTrue(readAccess);
    }

    @Test
    void testNonKafkaRolesFirst() {
        List<TopicAccess> topicAccess = extractTopicAccesses(List.of("NotAKafkaRole", "Kafka_Demo1_Write",
                "Kafka_Demo3_Write", "Kafka_Demo2_Write", "Kafka_Demo1_Read"), "Kafka_");
        boolean describeAccess = checkTopicJwtClaims(topicAccess, new Action(AclOperation.DESCRIBE,
                new ResourcePattern(ResourceType.TOPIC, "Demo1", PatternType.LITERAL), 1, false, false));
        boolean readAccess = checkTopicJwtClaims(topicAccess, new Action(AclOperation.READ,
                new ResourcePattern(ResourceType.TOPIC, "Demo1", PatternType.LITERAL), 1, false, false));
        Assertions.assertTrue(describeAccess);
        Assertions.assertTrue(readAccess);
    }

    @Test
    void testTopicWithUnderscore() {
        List<TopicAccess> topicAccess = extractTopicAccesses(List.of("Kafka_Demo1_Topic_Read"), "Kafka_");
        boolean readAccess = checkTopicJwtClaims(topicAccess, new Action(AclOperation.READ,
                new ResourcePattern(ResourceType.TOPIC, "Demo1_Topic", PatternType.LITERAL), 1, false, false));
        Assertions.assertTrue(readAccess);
    }

    @Test
    void testIdempotentWrite() {
        List<TopicAccess> topicAccess = extractTopicAccesses(List.of("Kafka_Demo1_Topic_Write"), "Kafka_");
        boolean readAccess = checkTopicJwtClaims(topicAccess, new Action(AclOperation.IDEMPOTENT_WRITE,
                new ResourcePattern(ResourceType.CLUSTER, "Demo1_Topic", PatternType.LITERAL), 1, false, false));
        Assertions.assertTrue(readAccess);
    }

    @Test
    void testDescribe() {
        List<TopicAccess> topicAccess = extractTopicAccesses(List.of("*_all"), "");
        boolean readAccess = checkTopicJwtClaims(topicAccess, new Action(AclOperation.DESCRIBE,
                new ResourcePattern(ResourceType.TOPIC, "Demo1_Topic", PatternType.LITERAL), 1, false, false));
        Assertions.assertTrue(readAccess);
    }

    // Test for cluster ACL support

    @Test
    void testIdempotentWriteWithWildcard() {
        List<TopicAccess> topicAccess = extractTopicAccesses(List.of("*_all"), "");
        boolean readAccess = checkTopicJwtClaims(topicAccess, new Action(AclOperation.IDEMPOTENT_WRITE,
                new ResourcePattern(ResourceType.CLUSTER, "Demo1_Topic", PatternType.LITERAL), 1, false, false));
        Assertions.assertTrue(readAccess);
    }

    @Test
    void testDescribeConfigClaimWithCluster() {
        List<ClusterAccess> topicAccess = extractClusterAccesses(List.of("Kafka_Cluster_describe-configs"), "Kafka_");
        boolean result = checkClusterJwtClaims(topicAccess, new Action(AclOperation.DESCRIBE_CONFIGS,
                new ResourcePattern(ResourceType.CLUSTER, "Cluster", PatternType.LITERAL), 1, false, false));
        Assertions.assertTrue(result);
    }

    // Test for (consumer)group ACL support

    @Test
    void testExtractGroupAccesses() {
        List<GroupAccess> groupAccess = extractGroupAccesses(List.of("Kafka_Group_MyGroup_read"), "Kafka_");
        Assertions.assertEquals(1, groupAccess.size());
        Assertions.assertEquals("MyGroup", groupAccess.get(0).pattern);
        Assertions.assertEquals(AclOperation.READ, groupAccess.get(0).operation);
    }

    @Test
    void testExtractGroupAccessesWithWildcard() {
        List<GroupAccess> groupAccess = extractGroupAccesses(List.of("Kafka_Group_My*_read"), "Kafka_");
        Assertions.assertEquals(1, groupAccess.size());
        Assertions.assertEquals("My*", groupAccess.get(0).pattern);
        Assertions.assertEquals(AclOperation.READ, groupAccess.get(0).operation);
    }

    @Test
    void testCheckGroupJwtClaimsExactMatch() {
        List<GroupAccess> groupAccesses = extractGroupAccesses(List.of("Kafka_Group_MyGroup_read"), "Kafka_");
        boolean result = checkGroupJwtClaims(groupAccesses, new Action(AclOperation.READ,
                new ResourcePattern(ResourceType.GROUP, "MyGroup", PatternType.LITERAL), 1, false, false));
        Assertions.assertTrue(result);
    }

    @Test
    void testCheckGroupJwtClaimsWildcardSuffix() {
        List<GroupAccess> groupAccesses = extractGroupAccesses(List.of("Kafka_Group_My*_read"), "Kafka_");
        boolean result = checkGroupJwtClaims(groupAccesses, new Action(AclOperation.READ,
                new ResourcePattern(ResourceType.GROUP, "MyConsumerGroup", PatternType.LITERAL), 1, false, false));
        Assertions.assertTrue(result);
    }

    @Test
    void testCheckGroupJwtClaimsWildcardPrefix() {
        List<GroupAccess> groupAccesses = extractGroupAccesses(List.of("Kafka_Group_*Group_read"), "Kafka_");
        boolean result = checkGroupJwtClaims(groupAccesses, new Action(AclOperation.READ,
                new ResourcePattern(ResourceType.GROUP, "ConsumerGroup", PatternType.LITERAL), 1, false, false));
        Assertions.assertTrue(result);
    }

    @Test
    void testCheckGroupJwtClaimsAllWildcard() {
        List<GroupAccess> groupAccesses = extractGroupAccesses(List.of("Kafka_Group_*_read"), "Kafka_");
        boolean result = checkGroupJwtClaims(groupAccesses, new Action(AclOperation.READ,
                new ResourcePattern(ResourceType.GROUP, "AnyGroup", PatternType.LITERAL), 1, false, false));
        Assertions.assertTrue(result);
    }

    @Test
    void testCheckGroupJwtClaimsDeny() {
        List<GroupAccess> groupAccesses = extractGroupAccesses(List.of("Kafka_Group_MyGroup_read"), "Kafka_");
        boolean result = checkGroupJwtClaims(groupAccesses, new Action(AclOperation.READ,
                new ResourcePattern(ResourceType.GROUP, "OtherGroup", PatternType.LITERAL), 1, false, false));
        Assertions.assertFalse(result);
    }

    @Test
    void testCheckGroupJwtClaimsDenyOperation() {
        List<GroupAccess> groupAccesses = extractGroupAccesses(List.of("Kafka_Group_MyGroup_read"), "Kafka_");
        boolean result = checkGroupJwtClaims(groupAccesses, new Action(AclOperation.WRITE,
                new ResourcePattern(ResourceType.GROUP, "MyGroup", PatternType.LITERAL), 1, false, false));
        Assertions.assertFalse(result);
    }

    @Test
    void testCheckGroupJwtClaimsDescribeAccess() {
        List<GroupAccess> groupAccesses = extractGroupAccesses(List.of("Kafka_Group_MyGroup_read"), "Kafka_");
        boolean result = checkGroupJwtClaims(groupAccesses, new Action(AclOperation.DESCRIBE,
                new ResourcePattern(ResourceType.GROUP, "MyGroup", PatternType.LITERAL), 1, false, false));
        Assertions.assertTrue(result);
    }

    @Test
    void testCheckGroupJwtClaimsAllOperation() {
        List<GroupAccess> groupAccesses = extractGroupAccesses(List.of("Kafka_Group_MyGroup_all"), "Kafka_");
        boolean result = checkGroupJwtClaims(groupAccesses, new Action(AclOperation.READ,
                new ResourcePattern(ResourceType.GROUP, "MyGroup", PatternType.LITERAL), 1, false, false));
        Assertions.assertTrue(result);

        result = checkGroupJwtClaims(groupAccesses, new Action(AclOperation.DESCRIBE,
                new ResourcePattern(ResourceType.GROUP, "MyGroup", PatternType.LITERAL), 1, false, false));
        Assertions.assertTrue(result);

        result = checkGroupJwtClaims(groupAccesses, new Action(AclOperation.ALL,
                new ResourcePattern(ResourceType.GROUP, "MyGroup", PatternType.LITERAL), 1, false, false));
        Assertions.assertTrue(result);
    }

    @Test
    void testCheckGroupJwtClaimsWithUnderscore() {
        List<GroupAccess> groupAccesses = extractGroupAccesses(List.of("Kafka_Group_My_Group_read"), "Kafka_");
        boolean result = checkGroupJwtClaims(groupAccesses, new Action(AclOperation.READ,
                new ResourcePattern(ResourceType.GROUP, "My_Group", PatternType.LITERAL), 1, false, false));
        Assertions.assertTrue(result);
    }
}