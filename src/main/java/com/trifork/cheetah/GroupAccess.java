package com.trifork.cheetah;

import org.apache.kafka.common.acl.AclOperation;

public class GroupAccess
{
    public final String pattern;

    public GroupAccess(String pattern, String operation)
    {
        this.pattern = pattern;
        this.operation = AclOperation.valueOf(operation.replace("-", "_").toUpperCase());
    }

    public final AclOperation operation;
}
