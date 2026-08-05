package com.xxx.insurance.ai.memory.service;

import com.xxx.insurance.ai.memory.mapper.LongTermMemoryMapper;
import com.xxx.insurance.ai.memory.model.LongTermMemoryRecord;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 基于 MyBatis 的长期记忆服务。
 */
@Service
@Profile("local-db")
public class MyBatisLongTermMemoryService implements LongTermMemoryService {

    private final LongTermMemoryMapper longTermMemoryMapper;

    public MyBatisLongTermMemoryService(LongTermMemoryMapper longTermMemoryMapper) {
        this.longTermMemoryMapper = longTermMemoryMapper;
    }

    @Override
    public void save(LongTermMemoryRecord record) {
        longTermMemoryMapper.insert(LongTermMemoryMapper.LongTermMemoryWriteRecord.from(record));
    }
}
