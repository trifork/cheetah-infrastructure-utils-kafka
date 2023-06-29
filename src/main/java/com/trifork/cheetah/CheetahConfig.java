package com.trifork.cheetah;

import io.strimzi.kafka.oauth.common.Config;

import java.util.Properties;

public class CheetahConfig extends Config
{
    public static final String CHEETAH_AUTHORIZATION_CLAIM_NAME = "cheetah.authorization.claim.name";
    public static final String CHEETAH_AUTHORIZATION_PREFIX = "cheetah.authorization.prefix";
    public static final String CHEETAH_AUTHORIZATION_CLAIM_IS_LIST = "cheetah.authorization.claim.is-list";
    public static final String CHEETAH_AUTHORIZATION_READONLY_SUPERUSERS = "cheetah.authorization.readonly.superusers";

    CheetahConfig ()
    {
    }

    CheetahConfig ( Properties properties )
    {
        super(properties);
    }
}
