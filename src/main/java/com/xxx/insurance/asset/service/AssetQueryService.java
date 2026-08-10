package com.xxx.insurance.asset.service;

import com.xxx.insurance.asset.model.AssetQueryResult;

/** 客户资产查询业务端口，后续由行内资产微应用适配器替换 Mock 实现。 */
public interface AssetQueryService {

    /** 按客户编号和可选资产类型查询脱敏资产。 */
    AssetQueryResult queryAssets(String customerId, String assetType);
}
