package com.trifork.cheetah;

import org.apache.kafka.common.acl.AclOperation;

public class TopicAccess
{
    public final String pattern;

    @Override
    public String toString ()
    {
        return "TopicAccess{" +
            "pattern='" + pattern + '\'' +
            ", operation='" + operation + '\'' +
            '}';
    }

    public TopicAccess ( String pattern, String operation )
    {
        this.pattern = pattern;
        this.operation = AclOperation.valueOf(operation.toUpperCase());
    }

    public final AclOperation operation;
}

