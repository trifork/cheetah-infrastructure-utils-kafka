package com.trifork.cheetah;

import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.resource.PatternType;
import org.apache.kafka.common.resource.ResourcePattern;
import org.apache.kafka.common.resource.ResourceType;
import org.apache.kafka.server.authorizer.Action;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static com.trifork.cheetah.CheetahKafkaAuthorizer.checkJwtClaims;
import static com.trifork.cheetah.CheetahKafkaAuthorizer.extractAccesses;

class CheetahKafkaAuthorizerTest
{

    @Test
    void CheckJwtClaimsAllAllow ()
    {
        List<TopicAccess> topicAccess = extractAccesses(List.of("*_all "), "");
        boolean result = checkJwtClaims(topicAccess, new Action(AclOperation.WRITE, new ResourcePattern(ResourceType.TOPIC, "JobNameInputTopic", PatternType.LITERAL), 1, false, false));
        Assertions.assertTrue(result);
    }

    @Test
    void testCheckJwtClaimsAllOneTopicAllow ()
    {
        List<TopicAccess> topicAccess = extractAccesses(List.of("MyTopic*_All"), "");
        boolean result = checkJwtClaims(topicAccess, new Action(AclOperation.WRITE, new ResourcePattern(ResourceType.TOPIC, "MyTopicTest", PatternType.LITERAL), 1, false, false));
        Assertions.assertTrue(result);
    }

    @Test
    void testCheckJwtClaimsAllOneTopicDeny ()
    {
        List<TopicAccess> topicAccess = extractAccesses(List.of("MyTopic*_read"), "");
        boolean result = checkJwtClaims(topicAccess, new Action(AclOperation.WRITE, new ResourcePattern(ResourceType.TOPIC, "MyTopicTest", PatternType.LITERAL), 1, false, false));
        Assertions.assertFalse(result);
    }

    @Test
    void testCheckJwtClaimsAllOneTopic ()
    {
        List<TopicAccess> topicAccess = extractAccesses(List.of("MyTopic*_all"), "");
        boolean result = checkJwtClaims(topicAccess, new Action(AclOperation.DESCRIBE, new ResourcePattern(ResourceType.TOPIC, "NotMyTopic", PatternType.LITERAL), 1, false, false));
        Assertions.assertFalse(result);
    }

    @Test
    void testCheckJwtClaimsDeny ()
    {
        List<TopicAccess> topicAccess = extractAccesses(List.of("MyTopic*_describe"), "");
        boolean result = checkJwtClaims(topicAccess, new Action(AclOperation.DESCRIBE, new ResourcePattern(ResourceType.TOPIC, "NotMyTopic", PatternType.LITERAL), 1, false, false));
        Assertions.assertFalse(result);
    }

    @Test
    void testCheckJwtClaimsDenyWithoutWildcard ()
    {
        List<TopicAccess> topicAccess = extractAccesses(List.of("MyTopic_describe"), "");
        boolean result = checkJwtClaims(topicAccess, new Action(AclOperation.DESCRIBE, new ResourcePattern(ResourceType.TOPIC, "MyTopicTest", PatternType.LITERAL), 1, false, false));
        Assertions.assertFalse(result);
    }

    @Test
    void testCheckJwtClaimsAllowWithStartWildcard ()
    {
        List<TopicAccess> topicAccess = extractAccesses(List.of("*MyTopic_describe"), "");
        boolean result = checkJwtClaims(topicAccess, new Action(AclOperation.DESCRIBE, new ResourcePattern(ResourceType.TOPIC, "TestMyTopic", PatternType.LITERAL), 1, false, false));
        Assertions.assertTrue(result);
    }

    @Test
    void testClaimWithPrefix ()
    {
        List<TopicAccess> topicAccess = extractAccesses(List.of("Kafka_*MyTopic_describe"), "Kafka_");
        boolean result = checkJwtClaims(topicAccess, new Action(AclOperation.DESCRIBE, new ResourcePattern(ResourceType.TOPIC, "TestMyTopic", PatternType.LITERAL), 1, false, false));
        Assertions.assertTrue(result);
    }

    @Test
    void testClaimsWithPrefix ()
    {
        List<TopicAccess> topicAccess = extractAccesses(List.of("Kafka_Demo1_Write", "Kafka_Demo3_Write", "Kafka_Demo2_Write", "Kafka_Demo1_Read"), "Kafka_");
        boolean describeAccess = checkJwtClaims(topicAccess, new Action(AclOperation.DESCRIBE, new ResourcePattern(ResourceType.TOPIC, "Demo1", PatternType.LITERAL), 1, false, false));
        boolean readAccess = checkJwtClaims(topicAccess, new Action(AclOperation.READ, new ResourcePattern(ResourceType.TOPIC, "Demo1", PatternType.LITERAL), 1, false, false));
        Assertions.assertTrue(describeAccess);
        Assertions.assertTrue(readAccess);
    }

    @Test
    void testNonKafkaRolesFirst ()
    {
        List<TopicAccess> topicAccess = extractAccesses(List.of("NotAKafkaRole","Kafka_Demo1_Write", "Kafka_Demo3_Write", "Kafka_Demo2_Write", "Kafka_Demo1_Read"), "Kafka_");
        boolean describeAccess = checkJwtClaims(topicAccess, new Action(AclOperation.DESCRIBE, new ResourcePattern(ResourceType.TOPIC, "Demo1", PatternType.LITERAL), 1, false, false));
        boolean readAccess = checkJwtClaims(topicAccess, new Action(AclOperation.READ, new ResourcePattern(ResourceType.TOPIC, "Demo1", PatternType.LITERAL), 1, false, false));
        Assertions.assertTrue(describeAccess);
        Assertions.assertTrue(readAccess);
    }

    @Test
    void testTopicWithUnderscore ()
    {
        List<TopicAccess> topicAccess = extractAccesses(List.of("Kafka_Demo1_Topic_Read"), "Kafka_");
        boolean readAccess = checkJwtClaims(topicAccess, new Action(AclOperation.READ, new ResourcePattern(ResourceType.TOPIC, "Demo1_Topic", PatternType.LITERAL), 1, false, false));
        Assertions.assertTrue(readAccess);
    }


}