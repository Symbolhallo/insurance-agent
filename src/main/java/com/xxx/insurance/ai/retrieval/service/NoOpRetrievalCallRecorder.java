package com.xxx.insurance.ai.retrieval.service;

import com.xxx.insurance.ai.retrieval.model.RetrievalCallRecord;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 非本地数据库模式下的空召回审计记录器。
 */
@Service
@Profile("!local-db")
public class NoOpRetrievalCallRecorder implements RetrievalCallRecorder {

    @Override
    public void record(RetrievalCallRecord record) {
        // 默认 profile 不启用持久化，但仍允许独立验证 Mock 召回能力。
    }
}
