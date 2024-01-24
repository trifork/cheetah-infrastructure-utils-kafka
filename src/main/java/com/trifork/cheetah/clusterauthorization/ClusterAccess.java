package com.trifork.cheetah.clusterauthorization;

import org.apache.kafka.common.acl.AclOperation;

public record ClusterAccess(AclOperation operation) {
    public ClusterAccess( String operation) {
        this(AclOperation.valueOf(operation.replace("-", "_").toUpperCase()));
    }
}
