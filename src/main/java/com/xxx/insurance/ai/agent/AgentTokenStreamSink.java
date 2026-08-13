package com.xxx.insurance.ai.agent;

/** 将 ReactAgent 增量模型内容交给具体传输通道的核心端口。 */
public interface AgentTokenStreamSink {

    /** 发布一个模型增量文本块。 */
    void publishToken(AgentTokenStreamContext context,
                      String streamId,
                      long chunkIndex,
                      String content);

    /** 标记当前模型流正常结束，便于前端关闭对应 Agent 的打字状态。 */
    void complete(AgentTokenStreamContext context,
                  String streamId,
                  long chunkCount);

    /** 模型流异常结束时刷新已接收正文并释放该流的临时资源。 */
    default void abort(AgentTokenStreamContext context, String streamId) {
        // Stateless sinks do not need explicit cleanup.
    }
}
