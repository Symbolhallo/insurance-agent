package com.xxx.insurance.product.service;

import com.xxx.insurance.product.model.ConfirmedProduct;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 非本地数据库模式下的空会话确认产品服务。
 */
@Service
@Profile("!local-db")
public class NoOpConversationConfirmedProductService implements ConversationConfirmedProductService {

    @Override
    public List<ConfirmedProduct> findConfirmedProducts(String conversationId) {
        return List.of();
    }

    @Override
    public void saveConfirmedProducts(List<ConfirmedProduct> products) {
        // 默认 profile 不持久化会话确认结果。
    }
}
