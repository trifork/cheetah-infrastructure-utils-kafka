package com.trifork.cheetah;

import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.resource.PatternType;
import org.apache.kafka.common.resource.ResourcePattern;
import org.apache.kafka.common.resource.ResourceType;
import org.apache.kafka.server.authorizer.Action;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.trifork.cheetah.CheetahKafkaAuthorizer.checkJwtClaims;
import static com.trifork.cheetah.CheetahKafkaAuthorizer.extractAccesses;

class CheetahKafkaAuthorizerTest
{

    @Test
    void CheckJwtClaimsAllAllow ()
    {
        List<TopicAccess> topicAccess = extractAccesses("*_all");
        boolean result = checkJwtClaims(topicAccess, new Action(AclOperation.WRITE, new ResourcePattern(ResourceType.TOPIC, "JobNameInputTopic", PatternType.LITERAL), 1, false, false));
        Assertions.assertTrue(result);
    }

    @Test
    void testCheckJwtClaimsAllOneTopicAllow ()
    {
        List<TopicAccess> topicAccess = extractAccesses("MyTopic*_all");
        boolean result = checkJwtClaims(topicAccess, new Action(AclOperation.WRITE, new ResourcePattern(ResourceType.TOPIC, "MyTopicTest", PatternType.LITERAL), 1, false, false));
        Assertions.assertTrue(result);
    }

    @Test
    void testCheckJwtClaimsAllOneTopicDeny ()
    {
        List<TopicAccess> topicAccess = extractAccesses("MyTopic*_read");
        boolean result = checkJwtClaims(topicAccess, new Action(AclOperation.DESCRIBE, new ResourcePattern(ResourceType.TOPIC, "MyTopicTest", PatternType.LITERAL), 1, false, false));
        Assertions.assertFalse(result);
    }

    @Test
    void testCheckJwtClaimsAllOneTopic ()
    {
        List<TopicAccess> topicAccess = extractAccesses("MyTopic*_all");
        boolean result = checkJwtClaims(topicAccess, new Action(AclOperation.DESCRIBE, new ResourcePattern(ResourceType.TOPIC, "NotMyTopic", PatternType.LITERAL), 1, false, false));
        Assertions.assertFalse(result);
    }

    @Test
    void testCheckJwtClaimsDeny ()
    {
        List<TopicAccess> topicAccess = extractAccesses("MyTopic*_describe");
        boolean result = checkJwtClaims(topicAccess, new Action(AclOperation.DESCRIBE, new ResourcePattern(ResourceType.TOPIC, "NotMyTopic", PatternType.LITERAL), 1, false, false));
        Assertions.assertFalse(result);
    }

    @Test
    void testCheckJwtClaimsDenyWithoutWildcard ()
    {
        List<TopicAccess> topicAccess = extractAccesses("MyTopic_describe");
        boolean result = checkJwtClaims(topicAccess, new Action(AclOperation.DESCRIBE, new ResourcePattern(ResourceType.TOPIC, "MyTopicTest", PatternType.LITERAL), 1, false, false));
        Assertions.assertFalse(result);
    }

    @Test
    void testCheckJwtClaimsAllowWithStartWildcard ()
    {
        List<TopicAccess> topicAccess = extractAccesses("*MyTopic_describe");
        boolean result = checkJwtClaims(topicAccess, new Action(AclOperation.DESCRIBE, new ResourcePattern(ResourceType.TOPIC, "TestMyTopic", PatternType.LITERAL), 1, false, false));
        Assertions.assertTrue(result);
    }
}