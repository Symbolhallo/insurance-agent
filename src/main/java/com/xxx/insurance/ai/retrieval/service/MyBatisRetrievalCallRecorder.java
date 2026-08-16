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

    /** 创建召回调用审计记录器。 */
    public MyBatisRetrievalCallRecorder(RetrievalCallMapper retrievalCallMapper) {
        this.retrievalCallMapper = retrievalCallMapper;
    }

    /** 追加保存一次召回调用的输入、输出、耗时和状态，不参与召回结果决策。 */
    @Override
    public void record(RetrievalCallRecord record) {
        retrievalCallMapper.insert(record);
    }
}
