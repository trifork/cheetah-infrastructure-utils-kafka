package com.trifork.cheetah;

import org.apache.kafka.common.acl.AclOperation;

public class TopicAccess
{
    public final String pattern;

    public TopicAccess ( String pattern, String operation )
    {
        this.pattern = pattern;
        this.operation = AclOperation.valueOf(operation.toUpperCase());
    }

    public final AclOperation operation;
}

