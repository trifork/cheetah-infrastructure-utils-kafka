package com.trifork.cheetah.TopicAuthorization;

import org.apache.kafka.server.authorizer.Action;
import java.util.List;

public interface TopicAuthorizationStrategy {
    boolean authorize(Action requestedAction, List<TopicAccess> topicAccesses);
}

