package com.xxx.insurance.product.service;

import com.xxx.insurance.product.model.ConfirmedProduct;

import java.util.List;

/**
 * conversationId 级产品确认存储边界。
 */
public interface ConversationConfirmedProductService {

    List<ConfirmedProduct> findConfirmedProducts(String conversationId);

    void saveConfirmedProducts(List<ConfirmedProduct> products);
}
