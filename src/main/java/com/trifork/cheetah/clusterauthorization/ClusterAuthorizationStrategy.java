package com.trifork.cheetah.clusterauthorization;

import org.apache.kafka.server.authorizer.Action;
import java.util.List;

public interface ClusterAuthorizationStrategy {
    boolean authorize(Action requestedAction, List<ClusterAccess> topicAccesses);
}

