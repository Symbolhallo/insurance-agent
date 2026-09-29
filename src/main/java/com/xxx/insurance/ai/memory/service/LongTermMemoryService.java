package com.xxx.insurance.ai.memory.service;

import com.xxx.insurance.ai.memory.model.LongTermMemoryRecord;

/**
 * 长期记忆服务。
 *
 * <p>长期记忆与 {@code ai_chat_memory} 的短期窗口不同。短期窗口负责模型多轮上下文，
 * 可能被裁剪或覆盖；长期记忆按请求追加保存历史流水，用于审计、复盘和后续长期事实沉淀。</p>
 */
public interface LongTermMemoryService {

    void save(LongTermMemoryRecord record);
}
