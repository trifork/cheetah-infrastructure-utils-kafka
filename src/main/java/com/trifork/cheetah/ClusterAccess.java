package com.trifork.cheetah;

import org.apache.kafka.common.acl.AclOperation;

public class ClusterAccess
{
    public ClusterAccess (String operation )
    {
        this.operation = AclOperation.valueOf(operation.replace("-", "_").toUpperCase());
    }

    public final AclOperation operation;
}