package com.xxx.insurance.ai.memory.service;

import com.xxx.insurance.ai.memory.model.LongTermMemoryRecord;

/**
 * 长期记忆服务。
 *
 * <p>长期记忆与 Spring AI ChatMemory 的窗口记忆不同。ChatMemory 负责多轮上下文窗口，
 * 可能被裁剪或覆盖；长期记忆按请求追加保存历史流水，用于审计、复盘和后续长期事实沉淀。</p>
 */
public interface LongTermMemoryService {

    void save(LongTermMemoryRecord record);
}
