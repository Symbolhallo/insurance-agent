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

    /** 创建长期记忆写入服务，数据库连接和事务边界由调用方及 Spring 管理。 */
    public MyBatisLongTermMemoryService(LongTermMemoryMapper longTermMemoryMapper) {
        this.longTermMemoryMapper = longTermMemoryMapper;
    }

    /** 将领域记录转换为数据库标量后追加写入；不覆盖或归档已有长期记忆。 */
    @Override
    public void save(LongTermMemoryRecord record) {
        longTermMemoryMapper.insert(LongTermMemoryMapper.LongTermMemoryWriteRecord.from(record));
    }
}
