package com.xxx.insurance.product.service;

import com.xxx.insurance.product.mapper.ConversationConfirmedProductMapper;
import com.xxx.insurance.product.model.ConfirmedProduct;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * OceanBase/MyBatis 会话确认产品服务。
 */
@Service
@Profile("local-db")
public class MyBatisConversationConfirmedProductService implements ConversationConfirmedProductService {

    private final ConversationConfirmedProductMapper mapper;

    public MyBatisConversationConfirmedProductService(ConversationConfirmedProductMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConfirmedProduct> findConfirmedProducts(String conversationId) {
        return List.copyOf(mapper.findActiveByConversationId(conversationId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveConfirmedProducts(List<ConfirmedProduct> products) {
        products.forEach(product -> mapper.upsert(
                "pcf-" + UUID.randomUUID().toString().replace("-", ""), product));
    }
}
