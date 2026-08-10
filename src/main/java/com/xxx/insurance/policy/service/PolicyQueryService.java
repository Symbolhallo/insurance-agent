package com.xxx.insurance.policy.service;

import com.xxx.insurance.policy.model.PolicyQueryResult;

/** 客户保单查询业务端口，后续由行内保单微应用适配器替换 Mock 实现。 */
public interface PolicyQueryService {

    /** 按客户编号和可选保单状态查询脱敏保单。 */
    PolicyQueryResult queryPolicies(String customerId, String policyStatus);
}
