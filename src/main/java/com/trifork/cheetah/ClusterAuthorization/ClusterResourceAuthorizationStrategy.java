package com.trifork.cheetah.ClusterAuthorization;

import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.server.authorizer.Action;
import java.util.List;

import static org.apache.kafka.common.acl.AclOperation.*;

public class ClusterResourceAuthorizationStrategy implements ClusterAuthorizationStrategy {
    @Override
    public boolean authorize(Action requestedAction, List<ClusterAccess> clusterAccesses) {
        for (ClusterAccess c : clusterAccesses) {
            var claimedOperation = c.operation();
            if (checkClusterAccess(claimedOperation, requestedAction))
                return true;
        }
        return false;
    }

    private static boolean checkClusterAccess(AclOperation claimedOperation, Action requestedAction) {
        return List.of(ANY, ALL).contains(claimedOperation)
                || requestedAction.operation().equals(claimedOperation);

    }

}