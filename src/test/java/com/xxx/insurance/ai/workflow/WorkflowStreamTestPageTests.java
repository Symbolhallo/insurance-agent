package com.xxx.insurance.ai.workflow;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowStreamTestPageTests {

    @Test
    void packagesSameOriginWorkflowStreamTestPage() throws IOException {
        String html = resource("static/workflow-test/index.html");
        String script = resource("static/workflow-test/assets/app.js");
        String styles = resource("static/workflow-test/assets/styles.css");
        String source = source("frontend/workflow-test/src/App.jsx");
        String workflowProgress = source("frontend/workflow-test/src/WorkflowProgress.jsx");
        String packageJson = source("frontend/workflow-test/package.json");
        String resourceConfig = source(
                "src/main/java/com/xxx/insurance/common/config/WorkflowTestResourceConfig.java");

        assertThat(html)
                .contains("保险智能体工作流测试台")
                .contains("id=\"root\"")
                .contains("/workflow-test/assets/app.js")
                .contains("/workflow-test/assets/styles.css")
                .doesNotContain("http://", "https://");
        assertThat(script)
                .contains("/api/v1/workflows/main")
                .contains("/runs/stream")
                .contains("/events")
                .contains("/product-confirmations/stream")
                .contains("/api/v1/ai/memory")
                .contains("X-Workflow-Instance-Id")
                .contains("Last-Event-ID")
                .contains("sessionStorage")
                .contains("streamId")
                .contains("chunkIndex");
        assertThat(source)
                .contains("function App()")
                .contains("function useAutoFollow(changeToken)")
                .contains("response.body")
                .contains("pendingFrameRef.current = requestAnimationFrame")
                .contains("onWheel: handleWheel")
                .contains("event.deltaY < 0")
                .contains("onPointerDown: handlePointerDown")
                .contains("pointerScrollingRef.current && distanceFromBottom > 12")
                .contains("onTouchMove: handleTouchMove")
                .contains("currentY > startY + 4")
                .contains("followingRef.current = false")
                .contains("cancelAnimationFrame(pendingFrameRef.current)")
                .contains("distanceFromBottom <= 12")
                .contains("aria-label=\"恢复自动跟随\"")
                .contains("function ConversationSidebar(")
                .contains("function ConversationHistory(")
                .contains("historyManageMode")
                .contains("selectedConversationIds")
                .contains("requestDeleteSelectedConversations")
                .contains("toggleAllConversations")
                .contains("批量删除历史会话")
                .contains("每个会话仍会独立校验运行状态")
                .contains("aria-label=\"新建对话\"")
                .contains("function DeleteConversationDialog(")
                .contains("历史消息和审计数据仍会保留")
                .contains("method: \"DELETE\"")
                .contains("toHistoryMessages(snapshot)")
                .contains("local-${completedRequestId}-user")
                .contains("local-${completedRequestId}-assistant")
                .contains("streamFollow.resume();")
                .contains("if (unsupported) void loadConversations();")
                .contains("stages.length + submittedQuestion.length + (workflowNotice?.message.length || 0)")
                .contains("const roleDifference = messageRoleOrder(left.role) - messageRoleOrder(right.role)")
                .doesNotContain("void loadConversationHistory(conversationIdRef.current).then")
                .contains("limit=200")
                .contains("ReactMarkdown")
                .contains("remarkGfm")
                .contains("function MarkdownContent(")
                .contains("normalizeModelMarkdown(content)")
                .contains("replace(/[\\u200B\\uFEFF]/g")
                .contains("replace(/\\n[\\t ]*\\n(?:[\\t ]*\\n)+/g")
                .contains("replace(/^(#{1,6})([^\\s#])/gm")
                .contains("function buildUserProgress(")
                .contains("识别问题中的产品信息")
                .contains("选择合适的专业能力")
                .contains("正在分析你的问题")
                .contains("function streamedAnswerText(streams)")
                .contains("stream.phase === \"SUMMARY\"")
                .contains("stream.phase === \"SUB_AGENT\"")
                .contains("function useProgressiveText(targetText, initialText = \"\")")
                .contains("setVisibleText(target.slice(0, visibleText.length + chunkSize))")
                .contains("className=\"assistant-activity-feed\"")
                .contains("aria-label=\"实时处理进度\"")
                .contains(") : running ? (")
                .contains("className=\"streaming-caret\"")
                .contains("模型处理结果")
                .contains("function RawStreamItem(")
                .contains("function describeModelOutput(stream)")
                .contains("function parseStructuredModelOutput(content)")
                .contains("识别到的产品")
                .contains("理解后的问题")
                .contains("业务类型")
                .contains("执行安排")
                .contains("查看技术原文")
                .contains("产品识别模型")
                .contains("该问题超出当前支持的保险业务范围")
                .contains("WORKFLOW_STATUS_WAITING_CONFIRM")
                .contains("readActiveWorkflow")
                .contains("updateActiveWorkflow")
                .contains("abortableDelay")
                .contains("MAX_RECONNECT_ATTEMPTS")
                .contains("id=\"queryForm\"")
                .contains("id=\"confirmForm\"")
                .doesNotContain("innerHTML", "dangerouslySetInnerHTML", "eval(", "window.confirm");
        assertThat(workflowProgress)
                .contains("visibleNodeCodes")
                .contains("stages.forEach(event => appendNode(event.node))")
                .contains("streams.forEach(stream => appendNode(PHASE_NODES.get(stream.phase)))")
                .doesNotContain("NODES.map");
        assertThat(packageJson)
                .contains("\"react\": \"18.3.1\"")
                .contains("\"vite\": \"6.4.3\"")
                .contains("\"react-markdown\"")
                .contains("\"remark-gfm\"")
                .contains("\"lucide-react\"");
        assertThat(resourceConfig)
                .contains("/workflow-test/**")
                .contains("classpath:/static/workflow-test/")
                .contains("CacheControl.noStore()");
        assertThat(styles)
                .contains("@media(max-width:680px)")
                .contains("overflow-wrap:anywhere")
                .contains("scrollbar-gutter:stable")
                .contains(".assistant-thinking")
                .contains(".assistant-activity-feed")
                .contains(".assistant-answer")
                .contains(".streaming-caret")
                .contains(".model-output-fields")
                .contains(".technical-output")
                .contains(".model-output-details")
                .contains(".markdown-content>*+*")
                .contains(".history-markdown>*+*")
                .contains("p:empty");
    }

    /** 读取打包前的类路径资源，避免测试依赖外部浏览器或运行中的服务。 */
    private String resource(String path) throws IOException {
        try (var input = getClass().getClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("Missing classpath resource: " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 读取仓库中的 React 源码，防止仅提交旧 bundle 而遗漏可维护的前端实现。 */
    private String source(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }
}
