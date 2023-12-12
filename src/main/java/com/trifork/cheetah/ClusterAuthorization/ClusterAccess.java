package com.trifork.cheetah.ClusterAuthorization;

import org.apache.kafka.common.acl.AclOperation;

public record ClusterAccess(AclOperation operation) {
    public ClusterAccess( String operation) {
        this(AclOperation.valueOf(operation.replace("-", "_").toUpperCase()));
    }
}
