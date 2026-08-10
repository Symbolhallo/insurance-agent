package com.xxx.insurance.ai.retrieval.service;

import com.xxx.insurance.ai.retrieval.model.RetrievalCallRecord;

/**
 * 外部召回审计记录边界。
 */
public interface RetrievalCallRecorder {

    void record(RetrievalCallRecord record);
}
