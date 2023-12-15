package com.trifork.cheetah.topicauthorization;

import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.server.authorizer.Action;
import java.util.List;

import static org.apache.kafka.common.acl.AclOperation.*;

public class GroupResourceTopicAuthorizationStrategy implements TopicAuthorizationStrategy {
    @Override
    public boolean authorize(Action requestedAction, List<TopicAccess> topicAccesses) {
        for (TopicAccess t : topicAccesses) {
            final var claimedOperation = t.operation();
            if (checkGroupAccess(claimedOperation, requestedAction)) {
                return true;
            }
        }
        return false;
    }

    private static boolean checkGroupAccess(AclOperation claimedOperation, Action requestedAction) {
        switch (requestedAction.operation()) {
            case READ:
                return List.of(ANY, ALL, READ).contains(claimedOperation);
            case DESCRIBE:
                return List.of(ANY, ALL, READ, WRITE, DESCRIBE).contains(claimedOperation);
            default:
                return false;
        }
    }

}