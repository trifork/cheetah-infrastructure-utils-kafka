package com.trifork.cheetah.TopicAuthorization;

import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.server.authorizer.Action;
import java.util.List;

import static org.apache.kafka.common.acl.AclOperation.*;

public class TopicResourceTopicAuthorizationStrategy implements TopicAuthorizationStrategy {
    @Override
    public boolean authorize(Action requestedAction, List<TopicAccess> topicAccesses) {
        for (TopicAccess t : topicAccesses) {
            var claimedOperation = t.operation();
            if (matchTopicPattern(requestedAction, t) && checkTopicAccess(claimedOperation, requestedAction)) {
                return true;
            }
        }
        return false;
    }

    private static boolean checkTopicAccess(AclOperation claimedOperation, Action requestedAction) {
        switch (requestedAction.operation()) {
            case DESCRIBE:
                // WRITE, READ, DELETE and ALTER implicitly allows DESCRIBE
                return List.of(ANY, ALL, WRITE, READ, DELETE, ALTER, DESCRIBE).contains(claimedOperation);
            default:
                return List.of(ANY, ALL).contains(claimedOperation)
                        || requestedAction.operation().equals(claimedOperation);

        }
    }

    private static boolean matchTopicPattern(Action action, TopicAccess t) {
        String resourceName = action.resourcePattern().name();
        String pattern = t.pattern();

        if (pattern.endsWith("*")) {
            // Pattern ends with '*', check if resourceName starts with the part of the
            // pattern before '*'
            String prefix = pattern.substring(0, pattern.length() - 1);
            return resourceName.startsWith(prefix);
        } else if (pattern.startsWith("*")) {
            // Pattern starts with '*', check if resourceName ends with the part of the
            // pattern after '*'
            String suffix = pattern.substring(1);
            return resourceName.endsWith(suffix);
        } else {
            // Exact match
            return resourceName.equals(pattern);
        }
    }
}