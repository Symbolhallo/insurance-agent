package com.xxx.insurance.product.mapper;

import com.xxx.insurance.product.model.ConfirmedProduct;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

/**
 * 会话确认产品 Mapper。
 */
@Mapper
public interface ConversationConfirmedProductMapper {

    /**
     * 读取当前 conversationId 内仍有效的确认产品，并按最近使用和确认时间排序。
     * 不跨会话查询，保证历史确认结果只在当前会话上下文中生效。
     */
    @Select("""
            select conversation_id,
                   product_code,
                   product_name,
                   product_type,
                   insurer_name,
                   source_clue,
                   retrieval_call_id,
                   workflow_instance_id,
                   confirmed_at
            from ai_conversation_confirmed_product
            where conversation_id = #{conversationId}
              and status = 'ACTIVE'
            order by last_used_at desc, confirmed_at desc
            """)
    @ConstructorArgs({
            @Arg(column = "conversation_id", javaType = String.class),
            @Arg(column = "product_code", javaType = String.class),
            @Arg(column = "product_name", javaType = String.class),
            @Arg(column = "product_type", javaType = String.class),
            @Arg(column = "insurer_name", javaType = String.class),
            @Arg(column = "source_clue", javaType = String.class),
            @Arg(column = "retrieval_call_id", javaType = String.class),
            @Arg(column = "workflow_instance_id", javaType = String.class),
            @Arg(column = "confirmed_at", javaType = Instant.class)
    })
    List<ConfirmedProduct> findActiveByConversationId(@Param("conversationId") String conversationId);

    /**
     * 按 conversationId + productCode 幂等保存用户确认结果。重复确认会更新标准产品信息、
     * 召回来源和最近使用时间，并重新激活曾失效的同一产品记录。
     */
    @Insert("""
            insert into ai_conversation_confirmed_product (
                confirmation_id,
                conversation_id,
                product_code,
                product_name,
                product_type,
                insurer_name,
                source_clue,
                retrieval_call_id,
                workflow_instance_id,
                status,
                confirmed_at,
                last_used_at,
                created_at,
                updated_at
            ) values (
                #{confirmationId},
                #{product.conversationId},
                #{product.productCode},
                #{product.productName},
                #{product.productType},
                #{product.insurerName},
                #{product.sourceClue},
                #{product.retrievalCallId},
                #{product.workflowInstanceId},
                'ACTIVE',
                #{product.confirmedAt},
                #{product.confirmedAt},
                #{product.confirmedAt},
                #{product.confirmedAt}
            ) on duplicate key update
                product_name = values(product_name),
                product_type = values(product_type),
                insurer_name = values(insurer_name),
                source_clue = values(source_clue),
                retrieval_call_id = values(retrieval_call_id),
                workflow_instance_id = values(workflow_instance_id),
                status = 'ACTIVE',
                confirmed_at = values(confirmed_at),
                last_used_at = values(last_used_at),
                updated_at = values(updated_at)
            """)
    void upsert(@Param("confirmationId") String confirmationId,
                @Param("product") ConfirmedProduct product);
}
