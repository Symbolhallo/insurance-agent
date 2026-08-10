package com.xxx.insurance.ai.retrieval.service;

import com.xxx.insurance.ai.retrieval.mapper.RetrievalCallMapper;
import com.xxx.insurance.ai.retrieval.model.RetrievalCallRecord;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * OceanBase/MyBatis 召回审计记录器。
 */
@Service
@Profile("local-db")
public class MyBatisRetrievalCallRecorder implements RetrievalCallRecorder {

    private final RetrievalCallMapper retrievalCallMapper;

    public MyBatisRetrievalCallRecorder(RetrievalCallMapper retrievalCallMapper) {
        this.retrievalCallMapper = retrievalCallMapper;
    }

    @Override
    public void record(RetrievalCallRecord record) {
        retrievalCallMapper.insert(record);
    }
}
