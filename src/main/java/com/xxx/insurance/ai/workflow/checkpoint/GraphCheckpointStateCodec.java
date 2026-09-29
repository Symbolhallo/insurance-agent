package com.xxx.insurance.ai.workflow.checkpoint;

import com.alibaba.cloud.ai.graph.serializer.StateSerializer;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/**
 * Graph State 二进制编解码边界。
 *
 * <p>必须复用 Spring AI Alibaba Graph 的 StateSerializer，而不是直接调用普通
 * ObjectMapper。主图状态只保存工作流 DTO、标量和集合；AgentScope 消息由业务窗口和
 * AgentStateStore 管理，不复制进 Graph Checkpoint。专用 Serializer 确保恢复后的业务 DTO
 * 类型仍符合节点读取合同。</p>
 */
public class GraphCheckpointStateCodec {

    private final StateSerializer stateSerializer;

    public GraphCheckpointStateCodec(StateSerializer stateSerializer) {
        this.stateSerializer = Objects.requireNonNull(stateSerializer, "stateSerializer must not be null");
    }

    public EncodedState encode(Map<String, Object> state) {
        try {
            return new EncodedState(stateSerializer.dataToBytes(state), stateSerializer.contentType());
        }
        catch (IOException ex) {
            throw new IllegalStateException("Failed to serialize Graph checkpoint state", ex);
        }
    }

    public Map<String, Object> decode(byte[] payload, String contentType) {
        if (!Objects.equals(stateSerializer.contentType(), contentType)) {
            throw new IllegalStateException("Checkpoint content type mismatch: stored=" + contentType
                    + ", configured=" + stateSerializer.contentType());
        }
        try {
            return stateSerializer.dataFromBytes(payload);
        }
        catch (IOException | ClassNotFoundException ex) {
            throw new IllegalStateException("Failed to deserialize Graph checkpoint state", ex);
        }
    }

    /**
     * Graph State 序列化结果。
     *
     * @param payload 序列化后的 State 二进制内容
     * @param contentType StateSerializer 使用的内容类型
     */
    public record EncodedState(byte[] payload, String contentType) {
    }
}
