package com.trifork.cheetah.AccessClaim;

import java.util.ArrayList;
import java.util.List;

import com.trifork.cheetah.AccessClaim.AccessUtil.AccessClaim;
import com.trifork.cheetah.TopicAuthorization.TopicAccess;

public class TopicAccessExtractor implements AccessExtractor<TopicAccess> {
    @Override
    public List<TopicAccess> extractAccesses(List<String> accesses, String prefix) {
        List<TopicAccess> result = new ArrayList<>();
        for (AccessClaim extracted : AccessUtil.extractAccesses(accesses, prefix)) {
            String pattern = extracted.pattern();
            String operation = extracted.operation();
            result.add(new TopicAccess(pattern, operation));
        }
        return result;
    }
}