"use strict";

const API_BASE = "/api/v1/workflows/main";

const phaseNames = {
    PRODUCT_REFERENCE_RESOLUTION: "产品线索解析",
    CONTEXT_ALIGNMENT: "上下文对齐",
    INTENT_RECOGNITION: "意图识别",
    PLANNER: "执行规划",
    SUB_AGENT: "子智能体",
    SUMMARY: "结果总结"
};

const eventNames = {
    start: "工作流启动",
    stage: "节点状态",
    human_confirm: "等待产品确认",
    agent_start: "子智能体启动",
    agent_complete: "子智能体完成",
    summary: "结果总结",
    review: "输出审核",
    complete: "工作流完成",
    error: "工作流异常"
};

const elements = {
    connectionStatus: document.querySelector("#connectionStatus"),
    conversationId: document.querySelector("#conversationId"),
    question: document.querySelector("#question"),
    queryForm: document.querySelector("#queryForm"),
    submitButton: document.querySelector("#submitButton"),
    clearButton: document.querySelector("#clearButton"),
    requestMeta: document.querySelector("#requestMeta"),
    stageList: document.querySelector("#stageList"),
    stageEmpty: document.querySelector("#stageEmpty"),
    eventCount: document.querySelector("#eventCount"),
    streamList: document.querySelector("#streamList"),
    streamEmpty: document.querySelector("#streamEmpty"),
    streamCount: document.querySelector("#streamCount"),
    finalSection: document.querySelector("#finalSection"),
    finalStatus: document.querySelector("#finalStatus"),
    finalAnswer: document.querySelector("#finalAnswer"),
    confirmForm: document.querySelector("#confirmForm"),
    confirmButton: document.querySelector("#confirmButton"),
    candidateList: document.querySelector("#candidateList"),
    candidateCount: document.querySelector("#candidateCount"),
    confirmEmpty: document.querySelector("#confirmEmpty"),
    stageTemplate: document.querySelector("#stageTemplate"),
    streamTemplate: document.querySelector("#streamTemplate"),
    candidateTemplate: document.querySelector("#candidateTemplate")
};

const state = {
    controller: null,
    workflowInstanceId: null,
    conversationId: null,
    lastEventId: null,
    running: false,
    eventCount: 0,
    streams: new Map()
};

elements.conversationId.value = createConversationId();

elements.queryForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (state.running) return;

    const message = elements.question.value.trim();
    const conversationId = elements.conversationId.value.trim();
    if (!message || !conversationId) return;

    resetRun();
    state.conversationId = conversationId;
    setRunning(true, "工作流连接中");
    elements.requestMeta.textContent = `conversationId: ${conversationId}`;

    await openSse(`${API_BASE}/runs/stream`, {
        message,
        conversationId,
        requestId: createRequestId()
    });
});

elements.confirmForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (state.running || !state.workflowInstanceId) return;

    const selectedProductCodes = [...elements.candidateList.querySelectorAll("input:checked")]
        .map(input => input.value);
    if (selectedProductCodes.length === 0) {
        setConnection("waiting", "请选择至少一个产品");
        return;
    }

    setRunning(true, "正在恢复工作流");
    elements.confirmButton.disabled = true;
    await openSse(
        `${API_BASE}/runs/${encodeURIComponent(state.workflowInstanceId)}/product-confirmations/stream`,
        {
            conversationId: state.conversationId,
            selectedProductCodes
        },
        state.lastEventId ? {"Last-Event-ID": state.lastEventId} : {}
    );
});

elements.clearButton.addEventListener("click", () => {
    abortCurrentRequest();
    resetRun();
    elements.question.value = "";
    elements.conversationId.value = createConversationId();
    elements.requestMeta.textContent = "等待请求";
    setConnection("idle", "未连接");
});

elements.question.addEventListener("keydown", (event) => {
    if (event.key === "Enter" && (event.ctrlKey || event.metaKey)) {
        elements.queryForm.requestSubmit();
    }
});

async function openSse(url, body, extraHeaders = {}) {
    abortCurrentRequest();
    const controller = new AbortController();
    state.controller = controller;

    try {
        const response = await fetch(url, {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                "Accept": "text/event-stream",
                ...extraHeaders
            },
            body: JSON.stringify(body),
            signal: controller.signal
        });

        if (!response.ok || !response.body) {
            throw new Error(await responseError(response));
        }

        setConnection("running", "实时接收中");
        await consumeSse(response.body, handleEvent);
        if (state.running) {
            setRunning(false, "连接已结束");
        }
    } catch (error) {
        if (error.name !== "AbortError") {
            addLocalError(error.message || "请求失败");
            setRunning(false, "请求失败", "error");
        }
    } finally {
        if (state.controller === controller) {
            state.controller = null;
        }
    }
}

async function consumeSse(stream, onEvent) {
    const reader = stream.getReader();
    const decoder = new TextDecoder("utf-8");
    let buffer = "";

    while (true) {
        const {value, done} = await reader.read();
        buffer += decoder.decode(value || new Uint8Array(), {stream: !done});

        let match = buffer.match(/\r?\n\r?\n/);
        while (match && match.index !== undefined) {
            const frame = buffer.slice(0, match.index);
            buffer = buffer.slice(match.index + match[0].length);
            const parsed = parseSseFrame(frame);
            if (parsed) onEvent(parsed);
            match = buffer.match(/\r?\n\r?\n/);
        }

        if (done) {
            const parsed = parseSseFrame(buffer);
            if (parsed) onEvent(parsed);
            return;
        }
    }
}

function parseSseFrame(frame) {
    if (!frame.trim() || frame.startsWith(":")) return null;
    let id = "";
    let event = "message";
    const data = [];

    frame.split(/\r?\n/).forEach(line => {
        if (line.startsWith("id:")) id = line.slice(3).trimStart();
        if (line.startsWith("event:")) event = line.slice(6).trimStart();
        if (line.startsWith("data:")) data.push(line.slice(5).trimStart());
    });

    if (data.length === 0) return null;
    return {id, event, data: data.join("\n")};
}

function handleEvent(frame) {
    let event;
    try {
        event = JSON.parse(frame.data);
    } catch {
        addLocalError("收到无法解析的 SSE 事件");
        return;
    }

    state.lastEventId = event.eventId || frame.id || state.lastEventId;
    state.workflowInstanceId = event.workflowInstanceId || state.workflowInstanceId;
    elements.requestMeta.textContent = state.workflowInstanceId
        ? `workflowInstanceId: ${state.workflowInstanceId}`
        : `conversationId: ${state.conversationId}`;

    if (event.type === "agent_stream") {
        renderStream(event);
        return;
    }

    addStage(event);
    switch (event.type) {
        case "human_confirm":
            renderCandidates(event.data?.candidates || []);
            setRunning(false, "等待产品确认", "waiting");
            break;
        case "complete":
            renderFinal(event.data?.finalAnswer || "", event.data?.status || "COMPLETED");
            setRunning(false, "工作流已完成", "idle");
            break;
        case "error":
            setRunning(false, event.data?.message || "工作流执行失败", "error");
            break;
        default:
            break;
    }
}

function renderStream(event) {
    const data = event.data || {};
    const streamId = data.streamId;
    if (!streamId) return;

    let stream = state.streams.get(streamId);
    if (!stream) {
        const fragment = elements.streamTemplate.content.cloneNode(true);
        const item = fragment.querySelector(".stream-item");
        const content = fragment.querySelector(".stream-content");
        item.dataset.phase = data.phase || "SUB_AGENT";
        fragment.querySelector(".stream-phase").textContent = phaseNames[data.phase] || data.phase || "模型输出";
        fragment.querySelector(".stream-agent").textContent = [data.agentName, data.taskId]
            .filter(Boolean).join(" · ");
        elements.streamList.append(fragment);
        stream = {
            item: elements.streamList.lastElementChild,
            content,
            text: "",
            lastIndex: 0
        };
        state.streams.set(streamId, stream);
        elements.streamCount.textContent = String(state.streams.size);
        elements.streamEmpty.hidden = true;
    }

    const chunkIndex = Number(data.chunkIndex || 0);
    if (!data.last && chunkIndex > stream.lastIndex) {
        stream.text += data.content || "";
        stream.lastIndex = chunkIndex;
        stream.content.textContent = stream.text;
        stream.content.scrollTop = stream.content.scrollHeight;
    }
    if (data.last) {
        stream.item.dataset.finished = "true";
        stream.item.querySelector(".stream-state").textContent = "完成";
    }
}

function addStage(event) {
    const fragment = elements.stageTemplate.content.cloneNode(true);
    const item = fragment.querySelector(".stage-item");
    const status = event.data?.status || event.type.toUpperCase();
    item.dataset.status = status;
    fragment.querySelector(".stage-name").textContent = eventNames[event.type] || event.type;
    fragment.querySelector(".stage-detail").textContent = [
        event.node,
        event.data?.nodeName,
        event.data?.agentType,
        status
    ].filter(Boolean).join(" · ");
    fragment.querySelector(".stage-time").textContent = formatTime(event.occurredAt);
    elements.stageList.append(fragment);
    elements.stageList.scrollTop = elements.stageList.scrollHeight;
    state.eventCount += 1;
    elements.eventCount.textContent = String(state.eventCount);
    elements.stageEmpty.hidden = true;
}

function addLocalError(message) {
    addStage({
        type: "error",
        node: "browser-client",
        occurredAt: new Date().toISOString(),
        data: {status: "ERROR", nodeName: message}
    });
}

function renderCandidates(candidates) {
    elements.candidateList.replaceChildren();
    candidates.forEach((candidate, index) => {
        const fragment = elements.candidateTemplate.content.cloneNode(true);
        const checkbox = fragment.querySelector(".candidate-checkbox");
        checkbox.value = candidate.productCode;
        checkbox.checked = index === 0;
        fragment.querySelector(".candidate-name").textContent = candidate.productName;
        fragment.querySelector(".candidate-code").textContent = candidate.productCode;
        fragment.querySelector(".candidate-meta").textContent =
            `${candidate.productType || ""} · ${candidate.insurerName || ""}`;
        fragment.querySelector(".candidate-reason").textContent = candidate.matchReason || "";
        elements.candidateList.append(fragment);
    });
    elements.candidateCount.textContent = String(candidates.length);
    elements.confirmEmpty.hidden = candidates.length > 0;
    elements.confirmButton.hidden = candidates.length === 0;
    elements.confirmButton.disabled = false;
}

function renderFinal(answer, status) {
    elements.finalSection.hidden = false;
    elements.finalAnswer.textContent = answer;
    elements.finalStatus.textContent = status;
}

function setRunning(running, message, stateName = running ? "running" : "idle") {
    state.running = running;
    elements.submitButton.disabled = running;
    elements.clearButton.disabled = false;
    if (!elements.confirmButton.hidden) {
        elements.confirmButton.disabled = running;
    }
    setConnection(stateName, message);
}

function setConnection(stateName, message) {
    elements.connectionStatus.dataset.state = stateName;
    elements.connectionStatus.textContent = message;
}

function resetRun() {
    abortCurrentRequest();
    state.workflowInstanceId = null;
    state.lastEventId = null;
    state.eventCount = 0;
    state.streams.clear();
    state.running = false;
    elements.stageList.replaceChildren();
    elements.streamList.replaceChildren();
    elements.candidateList.replaceChildren();
    elements.stageEmpty.hidden = false;
    elements.streamEmpty.hidden = false;
    elements.confirmEmpty.hidden = false;
    elements.confirmButton.hidden = true;
    elements.finalSection.hidden = true;
    elements.finalAnswer.textContent = "";
    elements.eventCount.textContent = "0";
    elements.streamCount.textContent = "0";
    elements.candidateCount.textContent = "0";
    elements.submitButton.disabled = false;
}

function abortCurrentRequest() {
    if (state.controller) {
        state.controller.abort();
        state.controller = null;
    }
}

async function responseError(response) {
    const text = await response.text();
    if (!text) return `HTTP ${response.status}`;
    try {
        const body = JSON.parse(text);
        return body.message || body.error || `HTTP ${response.status}`;
    } catch {
        return text.slice(0, 300);
    }
}

function createConversationId() {
    const timestamp = new Date().toISOString().replace(/\D/g, "").slice(0, 14);
    const suffix = Math.random().toString(36).slice(2, 7);
    return `web-${timestamp}-${suffix}`;
}

function createRequestId() {
    return `req-${Date.now()}-${crypto.randomUUID().replaceAll("-", "").slice(0, 12)}`;
}

function formatTime(value) {
    if (!value) return "";
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? "" : date.toLocaleTimeString("zh-CN", {hour12: false});
}
