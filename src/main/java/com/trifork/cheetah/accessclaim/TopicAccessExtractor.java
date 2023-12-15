package com.trifork.cheetah.accessclaim;

import java.util.ArrayList;
import java.util.List;

import com.trifork.cheetah.accessclaim.AccessUtil.AccessClaim;
import com.trifork.cheetah.topicauthorization.TopicAccess;

public class TopicAccessExtractor implements AccessExtractor<TopicAccess> {
    @Override
    public List<TopicAccess> extractAccesses(List<String> accesses, String prefix) {
        final List<TopicAccess> result = new ArrayList<>();
        for (AccessClaim extracted : AccessUtil.extractAccesses(accesses, prefix)) {
            String pattern = extracted.pattern();
            String operation = extracted.operation();
            result.add(new TopicAccess(pattern, operation));
        }
        return result;
    }
}