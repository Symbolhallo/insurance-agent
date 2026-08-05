package com.xxx.insurance.ai.memory.service;

import com.xxx.insurance.ai.memory.model.LongTermMemoryRecord;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 默认长期记忆实现。
 *
 * <p>默认 profile 不连接本地数据库，因此该实现不执行任何持久化动作。启用 local-db
 * profile 后会由 JDBC 实现接管。</p>
 */
@Service
@Profile("!local-db")
public class NoOpLongTermMemoryService implements LongTermMemoryService {

    @Override
    public void save(LongTermMemoryRecord record) {
        // Default profile is intentionally stateless.
    }
}
