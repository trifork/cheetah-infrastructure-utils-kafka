package com.trifork.cheetah;

import io.strimzi.kafka.oauth.common.Config;

import java.util.Properties;

public class CheetahConfig extends Config
{
    public static final String CHEETAH_AUTHORIZATION_CLAIM_NAME = "cheetah.authorization.claim.name";

    CheetahConfig ()
    {
    }

    CheetahConfig ( Properties properties )
    {
        super(properties);
    }
}
