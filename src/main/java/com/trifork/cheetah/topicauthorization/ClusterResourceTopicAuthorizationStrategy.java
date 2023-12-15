package com.trifork.cheetah.topicauthorization;

import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.server.authorizer.Action;
import java.util.List;

import static org.apache.kafka.common.acl.AclOperation.*;

public class ClusterResourceTopicAuthorizationStrategy implements TopicAuthorizationStrategy {
    @Override
    public boolean authorize(Action requestedAction, List<TopicAccess> topicAccesses) {
        for (TopicAccess t : topicAccesses) {
            final var claimedOperation = t.operation();
            if (checkClusterAccess(claimedOperation, requestedAction)) {
                return true;
            }
        }
        return false;
    }

    private static boolean checkClusterAccess(AclOperation claimedOperation, Action requestedAction) {
        switch (requestedAction.operation()) {
            case IDEMPOTENT_WRITE:
                return List.of(ANY, ALL, WRITE).contains(claimedOperation);
            default:
                return false;
        }
    }

}