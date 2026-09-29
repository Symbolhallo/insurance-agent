import React, {useCallback, useEffect, useMemo, useRef, useState} from "react";
import {WorkflowProgress, AGENT_NAMES} from "./WorkflowProgress";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import {
    ArrowDownToLine,
    Bot,
    Check,
    ChevronRight,
    CircleCheck,
    GitBranch,
    LoaderCircle,
    ListChecks,
    Menu,
    MessageSquare,
    PanelRightClose,
    PanelRightOpen,
    Plus,
    RotateCcw,
    Send,
    ShieldCheck,
    Sparkles,
    SquareActivity,
    Trash2,
    UserRound,
    X
} from "lucide-react";

const WORKFLOW_API_BASE = "/api/v1/workflows/main";
const MEMORY_API_BASE = "/api/v1/ai/memory";
const WORKFLOW_INSTANCE_ID_HEADER = "X-Workflow-Instance-Id";
const ACTIVE_WORKFLOW_STORAGE_KEY = "insurance-agent.active-workflow";
const WORKFLOW_STATUS_RUNNING = "RUNNING";
const WORKFLOW_STATUS_WAITING_CONFIRM = "WAITING_CONFIRM";
const MAX_RECONNECT_ATTEMPTS = 12;
const MAX_RECONNECT_DELAY_MS = 5000;

const PHASE_NAMES = {
    PRODUCT_REFERENCE_RESOLUTION: "产品线索解析",
    CONTEXT_ALIGNMENT: "上下文对齐",
    INTENT_RECOGNITION: "意图识别",
    PLANNER: "执行规划",
    SUB_AGENT: "子智能体",
    SUMMARY: "结果总结"
};

const EVENT_NAMES = {
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

const USER_PROGRESS_STEPS = [
    {
        key: "understand-product",
        title: "识别问题中的产品信息",
        activeDescription: "正在判断是否涉及具体保险产品，以及是否需要你确认产品。",
        completedDescription: "已完成产品信息识别。",
        nodes: ["resolve-product-reference", "retrieve-product-candidates", "human-confirm-product"],
        phases: ["PRODUCT_REFERENCE_RESOLUTION"]
    },
    {
        key: "align-context",
        title: "结合对话理解你的问题",
        activeDescription: "正在结合本次问题和当前会话信息，消除指代并补全必要条件。",
        completedDescription: "已完成问题理解和上下文整理。",
        nodes: ["context-alignment"],
        phases: ["CONTEXT_ALIGNMENT"]
    },
    {
        key: "plan-work",
        title: "选择合适的专业能力",
        activeDescription: "正在识别业务意图，并安排需要参与处理的专业智能体。",
        completedDescription: "已确定本次处理方式。",
        nodes: ["intent-recognition", "planner-agent"],
        phases: ["INTENT_RECOGNITION", "PLANNER"]
    },
    {
        key: "run-agents",
        title: "查询并分析业务信息",
        activeDescription: "专业智能体正在查询资料并分析结果，请稍候。",
        completedDescription: "专业智能体已完成查询和分析。",
        nodes: ["dag-executor"],
        phases: ["SUB_AGENT"]
    },
    {
        key: "prepare-answer",
        title: "整理最终答复",
        activeDescription: "正在汇总分析结果并进行发布前检查。",
        completedDescription: "答复已整理完成。",
        nodes: ["summary", "output-review"],
        phases: ["SUMMARY"]
    }
];

function useAutoFollow(changeToken) {
    const containerRef = useRef(null);
    const [following, setFollowing] = useState(true);
    const followingRef = useRef(true);
    const pendingFrameRef = useRef(null);
    const programmaticScrollRef = useRef(false);
    const touchStartYRef = useRef(null);

    const scrollToLatest = useCallback(() => {
        const container = containerRef.current;
        if (!container || !followingRef.current) return;
        programmaticScrollRef.current = true;
        const activeNode = container.querySelector('.workflow-node[data-status="WAITING_CONFIRM"], .workflow-node[data-status="RUNNING"]');
        if (activeNode) {
            const top = activeNode.getBoundingClientRect().top - container.getBoundingClientRect().top;
            if (top < 0 || top + activeNode.offsetHeight > container.clientHeight) {
                container.scrollTop += top - 60;
            }
        }
        else {
            container.scrollTop = container.scrollHeight;
        }
        requestAnimationFrame(() => {
            programmaticScrollRef.current = false;
        });
    }, []);

    useEffect(() => {
        if (!followingRef.current) return undefined;
        if (pendingFrameRef.current !== null) cancelAnimationFrame(pendingFrameRef.current);
        pendingFrameRef.current = requestAnimationFrame(() => {
            pendingFrameRef.current = null;
            scrollToLatest();
        });
        return () => {
            if (pendingFrameRef.current !== null) {
                cancelAnimationFrame(pendingFrameRef.current);
                pendingFrameRef.current = null;
            }
        };
    }, [changeToken, following, scrollToLatest]);

    const pauseForUser = useCallback(() => {
        const container = containerRef.current;
        if (!container || container.scrollHeight <= container.clientHeight + 2) return;
        followingRef.current = false;
        if (pendingFrameRef.current !== null) {
            cancelAnimationFrame(pendingFrameRef.current);
            pendingFrameRef.current = null;
        }
        setFollowing(false);
    }, []);

    const handleWheel = useCallback(event => {
        // 到底后继续向下滚动不改变跟随状态；只有向上阅读历史内容时才立即暂停。
        if (event.deltaY < 0) pauseForUser();
    }, [pauseForUser]);

    const handleScroll = useCallback(() => {
        const container = containerRef.current;
        if (!container || programmaticScrollRef.current) return;
        const distanceFromBottom = container.scrollHeight - container.scrollTop - container.clientHeight;
        // 只负责“回到底部后恢复”。离开底部必须由明确的用户手势触发，DOM 重排不能暂停跟随。
        if (distanceFromBottom <= 12 && !followingRef.current) {
            followingRef.current = true;
            setFollowing(true);
        }
    }, []);

    const handleTouchStart = useCallback(event => {
        touchStartYRef.current = event.touches[0]?.clientY ?? null;
    }, []);

    const handleTouchMove = useCallback(event => {
        const startY = touchStartYRef.current;
        const currentY = event.touches[0]?.clientY;
        // 手指向下拖动代表阅读更早内容；到底后继续向上推不暂停自动跟随。
        if (startY !== null && currentY !== undefined && currentY > startY + 4) pauseForUser();
    }, [pauseForUser]);

    const resume = useCallback(() => {
        followingRef.current = true;
        setFollowing(true);
        if (pendingFrameRef.current !== null) cancelAnimationFrame(pendingFrameRef.current);
        pendingFrameRef.current = requestAnimationFrame(() => {
            pendingFrameRef.current = null;
            scrollToLatest();
        });
    }, [scrollToLatest]);

    return {
        containerRef,
        following,
        resume,
        interactionProps: {
            onWheel: handleWheel,
            onTouchStart: handleTouchStart,
            onTouchMove: handleTouchMove,
            onScroll: handleScroll
        }
    };
}

function AutoFollowButton({following, onResume}) {
    if (following) return null;
    return (
        <button className="icon-button follow-button" type="button" onClick={onResume}
                title="回到最新内容并恢复自动跟随" aria-label="恢复自动跟随">
            <ArrowDownToLine size={16}/>
        </button>
    );
}

function App() {
    const [restoredWorkflow] = useState(readActiveWorkflow);
    const initiallyRunning = restoredWorkflow?.status === WORKFLOW_STATUS_RUNNING
        && Boolean(restoredWorkflow.workflowInstanceId);
    const initiallyWaiting = restoredWorkflow?.status === WORKFLOW_STATUS_WAITING_CONFIRM
        && Boolean(restoredWorkflow.workflowInstanceId);
    const [conversationId, setConversationId] = useState(
        () => restoredWorkflow?.conversationId || createConversationId());
    const [question, setQuestion] = useState("");
    const [connection, setConnection] = useState(() => initiallyRunning
        ? {state: "reconnecting", message: "正在恢复工作流连接"}
        : initiallyWaiting
            ? {state: "waiting", message: "等待产品确认"}
            : {state: "idle", message: "未连接"});
    const [requestMeta, setRequestMeta] = useState(() => restoredWorkflow?.workflowInstanceId
        ? `workflowInstanceId: ${restoredWorkflow.workflowInstanceId}`
        : "等待请求");
    const [running, setRunning] = useState(initiallyRunning);
    const [waitingForConfirmation, setWaitingForConfirmation] = useState(initiallyWaiting);
    const [stages, setStages] = useState([]);
    const [streams, setStreams] = useState([]);
    const [candidates, setCandidates] = useState(() => restoredWorkflow?.candidates || []);
    const [selectedProductCodes, setSelectedProductCodes] = useState(
        () => restoredWorkflow?.selectedProductCodes || []);
    const [finalResult, setFinalResult] = useState(null);
    const [conversations, setConversations] = useState([]);
    const [historyMessages, setHistoryMessages] = useState([]);
    const [historyLoading, setHistoryLoading] = useState(false);
    const [conversationError, setConversationError] = useState("");
    const [pendingDeleteConversations, setPendingDeleteConversations] = useState([]);
    const [deletingConversation, setDeletingConversation] = useState(false);
    const [historyManageMode, setHistoryManageMode] = useState(false);
    const [selectedConversationIds, setSelectedConversationIds] = useState([]);
    const [historyOpen, setHistoryOpen] = useState(false);
    const [activityOpen, setActivityOpen] = useState(initiallyRunning);
    const [submittedQuestion, setSubmittedQuestion] = useState("");

    const controllerRef = useRef(null);
    const runningRef = useRef(initiallyRunning);
    const waitingForConfirmationRef = useRef(initiallyWaiting);
    const workflowInstanceIdRef = useRef(restoredWorkflow?.workflowInstanceId || null);
    const conversationIdRef = useRef(restoredWorkflow?.conversationId || null);
    const lastEventIdRef = useRef(restoredWorkflow?.lastEventId || null);
    const requestIdRef = useRef(restoredWorkflow?.requestId || null);
    const historyRequestRef = useRef(0);

    const streamChangeToken = useMemo(() => streams.reduce(
        (total, stream) => total + stream.text.length + (stream.finished ? 1 : 0),
        (finalResult?.answer.length || 0) + historyMessages.reduce(
            (total, message) => total + message.content.length, 0)),
    [streams, finalResult, historyMessages]);
    const stageFollow = useAutoFollow(stages.length);
    const streamFollow = useAutoFollow(streamChangeToken);

    const setRunState = useCallback((isRunning, message, stateName = isRunning ? "running" : "idle") => {
        runningRef.current = isRunning;
        waitingForConfirmationRef.current = false;
        setRunning(isRunning);
        setWaitingForConfirmation(false);
        setConnection({state: stateName, message});
    }, []);

    const setWaitingState = useCallback(message => {
        runningRef.current = false;
        waitingForConfirmationRef.current = true;
        setRunning(false);
        setWaitingForConfirmation(true);
        setConnection({state: "waiting", message});
    }, []);

    const abortCurrentRequest = useCallback(() => {
        if (!controllerRef.current) return;
        controllerRef.current.abort();
        controllerRef.current = null;
    }, []);

    useEffect(() => abortCurrentRequest, [abortCurrentRequest]);

    const loadConversations = useCallback(async () => {
        try {
            const response = await fetch(`${MEMORY_API_BASE}/conversations?limit=50`, {
                headers: {"Accept": "application/json"}
            });
            const data = await readApiData(response);
            setConversations(Array.isArray(data) ? data : []);
            setConversationError("");
        }
        catch (error) {
            setConversationError(error.message || "历史会话加载失败");
        }
    }, []);

    const loadConversationHistory = useCallback(async selectedConversationId => {
        const requestSequence = historyRequestRef.current + 1;
        historyRequestRef.current = requestSequence;
        setHistoryLoading(true);
        setConversationError("");
        try {
            const response = await fetch(
                `${MEMORY_API_BASE}/conversations/${encodeURIComponent(selectedConversationId)}?limit=200`,
                {headers: {"Accept": "application/json"}}
            );
            const snapshot = await readApiData(response);
            if (historyRequestRef.current === requestSequence) {
                setHistoryMessages(toHistoryMessages(snapshot));
                return true;
            }
        }
        catch (error) {
            if (historyRequestRef.current === requestSequence) {
                setHistoryMessages([]);
                setConversationError(error.message || "历史消息加载失败");
            }
            return false;
        }
        finally {
            if (historyRequestRef.current === requestSequence) {
                setHistoryLoading(false);
            }
        }
    }, []);

    useEffect(() => {
        void loadConversations();
    }, [loadConversations]);

    useEffect(() => {
        const availableIds = new Set(conversations.map(conversation => conversation.conversationId));
        setSelectedConversationIds(current => current.filter(id => availableIds.has(id)));
    }, [conversations]);

    const resetRun = useCallback(() => {
        abortCurrentRequest();
        runningRef.current = false;
        waitingForConfirmationRef.current = false;
        workflowInstanceIdRef.current = null;
        lastEventIdRef.current = null;
        requestIdRef.current = null;
        setRunning(false);
        setWaitingForConfirmation(false);
        setStages([]);
        setStreams([]);
        setCandidates([]);
        setSelectedProductCodes([]);
        setFinalResult(null);
        setSubmittedQuestion("");
        clearActiveWorkflow();
    }, [abortCurrentRequest]);

    const createNewConversation = useCallback(() => {
        if (runningRef.current || waitingForConfirmationRef.current) return;
        resetRun();
        historyRequestRef.current += 1;
        const nextConversationId = createConversationId();
        setConversationId(nextConversationId);
        setHistoryMessages([]);
        setHistoryLoading(false);
        setConversationError("");
        setQuestion("");
        setRequestMeta("等待请求");
        setConnection({state: "idle", message: "新对话"});
        setPendingDeleteConversations([]);
        setHistoryManageMode(false);
        setSelectedConversationIds([]);
    }, [resetRun]);

    const selectConversation = useCallback(selectedConversationId => {
        if (runningRef.current || waitingForConfirmationRef.current
            || selectedConversationId === conversationId) return;
        resetRun();
        setConversationId(selectedConversationId);
        setQuestion("");
        setRequestMeta(`conversationId: ${selectedConversationId}`);
        setConnection({state: "idle", message: "历史会话"});
        void loadConversationHistory(selectedConversationId);
    }, [conversationId, loadConversationHistory, resetRun]);

    const requestDeleteConversation = useCallback((event, selectedConversation) => {
        event.stopPropagation();
        if (runningRef.current || waitingForConfirmationRef.current) return;
        setPendingDeleteConversations([selectedConversation]);
    }, []);

    const requestDeleteSelectedConversations = useCallback(() => {
        if (runningRef.current || waitingForConfirmationRef.current
            || selectedConversationIds.length === 0) return;
        const selectedIds = new Set(selectedConversationIds);
        setPendingDeleteConversations(conversations.filter(
            conversation => selectedIds.has(conversation.conversationId)));
    }, [conversations, selectedConversationIds]);

    const toggleHistoryManageMode = useCallback(() => {
        if (runningRef.current || waitingForConfirmationRef.current || deletingConversation) return;
        setHistoryManageMode(current => !current);
        setSelectedConversationIds([]);
    }, [deletingConversation]);

    const toggleConversationSelection = useCallback(selectedConversationId => {
        setSelectedConversationIds(current => current.includes(selectedConversationId)
            ? current.filter(id => id !== selectedConversationId)
            : [...current, selectedConversationId]);
    }, []);

    const toggleAllConversations = useCallback(() => {
        setSelectedConversationIds(current => current.length === conversations.length
            ? []
            : conversations.map(conversation => conversation.conversationId));
    }, [conversations]);

    const deleteConversations = useCallback(async () => {
        if (pendingDeleteConversations.length === 0 || runningRef.current
            || waitingForConfirmationRef.current || deletingConversation) return;
        setDeletingConversation(true);
        const deletedIds = [];
        const failedIds = [];
        for (const target of pendingDeleteConversations) {
            try {
                const response = await fetch(
                    `${MEMORY_API_BASE}/conversations/${encodeURIComponent(target.conversationId)}`,
                    {method: "DELETE", headers: {"Accept": "application/json"}}
                );
                await readApiData(response);
                deletedIds.push(target.conversationId);
            }
            catch {
                failedIds.push(target.conversationId);
            }
        }

        const deletedIdSet = new Set(deletedIds);
        setConversations(current => current.filter(
            conversation => !deletedIdSet.has(conversation.conversationId)));
        setPendingDeleteConversations([]);
        setDeletingConversation(false);

        if (deletedIdSet.has(conversationId)) {
            createNewConversation();
        }
        if (failedIds.length > 0) {
            setSelectedConversationIds(failedIds);
            setHistoryManageMode(true);
            setConversationError(`${deletedIds.length} 个会话已删除，${failedIds.length} 个删除失败或仍在运行`);
        }
        else {
            setSelectedConversationIds([]);
            setConversationError("");
            setHistoryManageMode(false);
        }
    }, [conversationId, createNewConversation, deletingConversation, pendingDeleteConversations]);

    const addStage = useCallback(event => {
        const status = event.data?.status || event.type.toUpperCase();
        setStages(current => event.eventId && current.some(item => item.key === event.eventId) ? current : [...current, {
            key: event.eventId || `${event.type}-${Date.now()}-${current.length}`,
            type: event.type,
            node: event.node,
            taskId: event.data?.taskId,
            agentType: event.data?.agentType,
            name: EVENT_NAMES[event.type] || event.type,
            status,
            detail: [event.node, event.data?.nodeName, event.data?.agentType, status]
                .filter(Boolean).join(" · "),
            occurredAt: event.occurredAt
        }]);
    }, []);

    const addLocalError = useCallback(message => {
        addStage({
            type: "error",
            node: "browser-client",
            occurredAt: new Date().toISOString(),
            data: {status: "ERROR", nodeName: message}
        });
    }, [addStage]);

    const renderStream = useCallback(event => {
        const data = event.data || {};
        if (!data.streamId) return;
        const chunkIndex = Number(data.chunkIndex || 0);

        setStreams(current => {
            const index = current.findIndex(stream => stream.streamId === data.streamId);
            if (index < 0) {
                return [...current, {
                    streamId: data.streamId,
                    phase: data.phase || "SUB_AGENT",
                    agentName: data.agentName,
                    taskId: data.taskId,
                    text: data.last ? "" : data.content || "",
                    lastIndex: data.last ? 0 : chunkIndex,
                    finished: Boolean(data.last)
                }];
            }

            const existing = current[index];
            if (!data.last && chunkIndex <= existing.lastIndex) return current;
            const updated = {
                ...existing,
                text: data.last ? existing.text : existing.text + (data.content || ""),
                lastIndex: data.last ? existing.lastIndex : chunkIndex,
                finished: existing.finished || Boolean(data.last)
            };
            return current.map((stream, streamIndex) => streamIndex === index ? updated : stream);
        });
    }, []);

    const handleEvent = useCallback(frame => {
        let event;
        try {
            event = JSON.parse(frame.data);
        }
        catch {
            addLocalError("收到无法解析的 SSE 事件");
            return;
        }

        lastEventIdRef.current = event.eventId || frame.id || lastEventIdRef.current;
        workflowInstanceIdRef.current = event.workflowInstanceId || workflowInstanceIdRef.current;
        updateActiveWorkflow({
            workflowInstanceId: workflowInstanceIdRef.current,
            lastEventId: lastEventIdRef.current
        });
        setRequestMeta(workflowInstanceIdRef.current
            ? `workflowInstanceId: ${workflowInstanceIdRef.current}`
            : `conversationId: ${conversationIdRef.current}`);

        if (event.type === "agent_stream") {
            renderStream(event);
            return;
        }

        addStage(event);
        if (event.type === "human_confirm") {
            const nextCandidates = event.data?.candidates || [];
            const nextSelectedProductCodes = nextCandidates.length > 0
                ? [nextCandidates[0].productCode]
                : [];
            setCandidates(nextCandidates);
            setSelectedProductCodes(nextSelectedProductCodes);
            updateActiveWorkflow({
                status: WORKFLOW_STATUS_WAITING_CONFIRM,
                candidates: nextCandidates,
                selectedProductCodes: nextSelectedProductCodes
            });
            setWaitingState("等待产品确认");
            setActivityOpen(false);
        }
        else if (event.type === "complete") {
            const answer = event.data?.finalAnswer || "";
            setFinalResult({
                answer,
                status: event.data?.status || "COMPLETED"
            });
            setRunState(false, "工作流已完成", "idle");
            setActivityOpen(false);
            clearActiveWorkflow();
            void loadConversations();
            void loadConversationHistory(conversationIdRef.current).then(loaded => {
                if (loaded) setSubmittedQuestion("");
            });
        }
        else if (event.type === "error") {
            setRunState(false, event.data?.message || "工作流执行失败", "error");
            clearActiveWorkflow();
        }
    }, [addLocalError, addStage, loadConversationHistory, loadConversations, renderStream,
        setRunState, setWaitingState]);

    const openSse = useCallback(async (url, body = null, extraHeaders = {}, options = {}) => {
        abortCurrentRequest();
        const controller = new AbortController();
        controllerRef.current = controller;
        let requestUrl = url;
        let requestMethod = options.method || "POST";
        let requestBody = body;
        let reconnectAttempts = 0;

        try {
            while (!controller.signal.aborted) {
                try {
                    const headers = {
                        "Accept": "text/event-stream",
                        ...extraHeaders
                    };
                    if (requestMethod !== "GET") {
                        headers["Content-Type"] = "application/json";
                    }
                    if (lastEventIdRef.current) {
                        headers["Last-Event-ID"] = lastEventIdRef.current;
                    }
                    const response = await fetch(requestUrl, {
                        method: requestMethod,
                        headers,
                        body: requestMethod === "GET" ? undefined : JSON.stringify(requestBody),
                        signal: controller.signal
                    });
                    if (!response.ok || !response.body) {
                        const responseFailure = new Error(await responseError(response));
                        responseFailure.status = response.status;
                        throw responseFailure;
                    }

                    const responseWorkflowInstanceId = response.headers.get(WORKFLOW_INSTANCE_ID_HEADER);
                    if (responseWorkflowInstanceId) {
                        workflowInstanceIdRef.current = responseWorkflowInstanceId;
                        updateActiveWorkflow({workflowInstanceId: responseWorkflowInstanceId});
                        setRequestMeta(`workflowInstanceId: ${responseWorkflowInstanceId}`);
                    }
                    reconnectAttempts = 0;
                    setConnection({state: "running", message: "实时接收中"});
                    await consumeSse(response.body, handleEvent);
                    if (!runningRef.current) return;
                }
                catch (error) {
                    if (error.name === "AbortError" || !runningRef.current) return;
                    if (!workflowInstanceIdRef.current || error.status === 410) {
                        const message = error.status === 410
                            ? "可重放事件已过期，无法完整恢复当前输出"
                            : error.message || "工作流连接失败";
                        addLocalError(message);
                        clearActiveWorkflow();
                        setRunState(false, message, "error");
                        return;
                    }
                }

                reconnectAttempts += 1;
                if (reconnectAttempts === MAX_RECONNECT_ATTEMPTS) {
                    addLocalError("网络持续不可用，页面仍会后台自动重连");
                }
                const reconnectDelay = Math.min(
                    500 * (2 ** Math.min(reconnectAttempts - 1, 4)),
                    MAX_RECONNECT_DELAY_MS);
                setConnection({
                    state: "reconnecting",
                    message: `连接中断，${Math.ceil(reconnectDelay / 1000)}秒后自动重连`
                });
                await abortableDelay(reconnectDelay, controller.signal);
                requestUrl = `${WORKFLOW_API_BASE}/runs/${encodeURIComponent(
                    workflowInstanceIdRef.current)}/events`;
                requestMethod = "GET";
                requestBody = null;
            }
        }
        catch (error) {
            if (error.name !== "AbortError") {
                addLocalError(error.message || "请求失败");
                setRunState(false, "请求失败", "error");
            }
        }
        finally {
            if (controllerRef.current === controller) {
                controllerRef.current = null;
            }
        }
    }, [abortCurrentRequest, addLocalError, handleEvent, setRunState]);

    useEffect(() => {
        if (!initiallyRunning || !restoredWorkflow?.workflowInstanceId) return;
        const headers = restoredWorkflow.lastEventId
            ? {"Last-Event-ID": restoredWorkflow.lastEventId}
            : {};
        void openSse(
            `${WORKFLOW_API_BASE}/runs/${encodeURIComponent(
                restoredWorkflow.workflowInstanceId)}/events`,
            null,
            headers,
            {method: "GET"});
    }, [initiallyRunning, openSse, restoredWorkflow]);

    const submitRun = async event => {
        event.preventDefault();
        const message = question.trim();
        const normalizedConversationId = conversationId.trim();
        if (running || waitingForConfirmation || !message || !normalizedConversationId) return;

        resetRun();
        setSubmittedQuestion(message);
        setQuestion("");
        conversationIdRef.current = normalizedConversationId;
        requestIdRef.current = createRequestId();
        writeActiveWorkflow({
            conversationId: normalizedConversationId,
            requestId: requestIdRef.current,
            workflowInstanceId: null,
            lastEventId: null,
            status: WORKFLOW_STATUS_RUNNING,
            candidates: [],
            selectedProductCodes: []
        });
        setRunState(true, "工作流连接中");
        setActivityOpen(true);
        setRequestMeta(`conversationId: ${normalizedConversationId}`);
        await openSse(`${WORKFLOW_API_BASE}/runs/stream`, {
            message,
            conversationId: normalizedConversationId,
            requestId: requestIdRef.current
        });
    };

    const confirmProducts = async event => {
        event.preventDefault();
        if (running || !waitingForConfirmation || !workflowInstanceIdRef.current
            || selectedProductCodes.length === 0) {
            if (selectedProductCodes.length === 0) {
                setConnection({state: "waiting", message: "请选择至少一个产品"});
            }
            return;
        }

        updateActiveWorkflow({
            status: WORKFLOW_STATUS_RUNNING,
            selectedProductCodes
        });
        setRunState(true, "正在恢复工作流");
        setActivityOpen(true);
        const headers = lastEventIdRef.current
            ? {"Last-Event-ID": lastEventIdRef.current}
            : {};
        await openSse(
            `${WORKFLOW_API_BASE}/runs/${encodeURIComponent(workflowInstanceIdRef.current)}/product-confirmations/stream`,
            {conversationId: conversationIdRef.current, selectedProductCodes},
            headers
        );
    };

    const clearAll = () => {
        createNewConversation();
    };

    const toggleProduct = productCode => {
        setSelectedProductCodes(current => {
            const next = current.includes(productCode)
                ? current.filter(code => code !== productCode)
                : [...current, productCode];
            updateActiveWorkflow({selectedProductCodes: next});
            return next;
        });
    };

    const workflowLocked = running || waitingForConfirmation;

    const hasConversationContent = historyLoading || historyMessages.length > 0 || submittedQuestion
        || streams.length > 0 || finalResult || waitingForConfirmation;

    return (
        <div className="app-shell" data-history-open={historyOpen} data-activity-open={activityOpen}>
            <ConversationSidebar
                conversations={conversations}
                activeConversationId={conversationId}
                error={conversationError}
                disabled={workflowLocked}
                manageMode={historyManageMode}
                selectedConversationIds={selectedConversationIds}
                deleting={deletingConversation}
                onCreate={() => { createNewConversation(); setHistoryOpen(false); }}
                onSelect={id => { selectConversation(id); setHistoryOpen(false); }}
                onDelete={requestDeleteConversation}
                onToggleManage={toggleHistoryManageMode}
                onToggleSelection={toggleConversationSelection}
                onToggleAll={toggleAllConversations}
                onDeleteSelected={requestDeleteSelectedConversations}
            />
            <button className="sidebar-scrim" type="button" aria-label="关闭历史会话"
                    onClick={() => setHistoryOpen(false)}/>

            <main className="chat-shell">
                <header className="chat-header">
                    <button className="icon-button mobile-menu" type="button" aria-label="打开历史会话"
                            onClick={() => setHistoryOpen(true)}><Menu size={20}/></button>
                    <div className="chat-title">
                        <h1>保险智能助理</h1>
                        <span className="connection-status" data-state={connection.state}>
                            <i/>{connection.message}
                        </span>
                    </div>
                    <div className="header-actions">
                        <button className="icon-button" type="button" onClick={clearAll}
                                disabled={workflowLocked} title="新建对话" aria-label="新建对话">
                            <RotateCcw size={18}/>
                        </button>
                        <button className="activity-button" type="button" aria-expanded={activityOpen}
                                onClick={() => setActivityOpen(value => !value)}>
                            {activityOpen ? <PanelRightClose size={18}/> : <PanelRightOpen size={18}/>}
                            <span>运行详情</span>
                            {(running || waitingForConfirmation) && <i/>}
                        </button>
                    </div>
                </header>

                <section className="conversation-canvas" ref={streamFollow.containerRef}
                         {...streamFollow.interactionProps} aria-label="当前对话">
                    <div className="conversation-column">
                        {!hasConversationContent && <WelcomePanel onSuggestion={setQuestion}/>}
                        <ConversationHistory messages={historyMessages} loading={historyLoading}/>
                        {submittedQuestion && <CurrentQuestion text={submittedQuestion}/>}
                        {(running || (streams.length > 0 && !finalResult)) && (
                            <LiveExecution stages={stages} streams={streams} running={running}
                                           onOpenActivity={() => setActivityOpen(true)}/>
                        )}
                        {waitingForConfirmation && (
                            <ProductConfirmation
                                candidates={candidates}
                                selectedProductCodes={selectedProductCodes}
                                running={running}
                                onToggle={toggleProduct}
                                onSubmit={confirmProducts}
                            />
                        )}
                        {finalResult && <FinalResult result={finalResult}/>}
                    </div>
                    <AutoFollowButton following={streamFollow.following} onResume={streamFollow.resume}/>
                </section>

                <div className="composer-dock">
                    <form id="queryForm" className="composer" onSubmit={submitRun}>
                        <label className="sr-only" htmlFor="question">输入保险问题</label>
                        <textarea id="question" maxLength={2000} rows={1} value={question}
                                  onChange={event => setQuestion(event.target.value)}
                                  onKeyDown={event => {
                                      if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
                                          event.preventDefault();
                                          event.currentTarget.form?.requestSubmit();
                                      }
                                  }}
                                  placeholder={waitingForConfirmation ? "请先完成上方产品确认" : "询问产品对比、保险知识、保单或资产信息"}
                                  disabled={workflowLocked} required/>
                        <div className="composer-footer">
                            <span title={requestMeta}>{question.length} / 2000 · Enter 发送，Shift + Enter 换行</span>
                            <button className="send-button" type="submit" disabled={workflowLocked || !question.trim()}
                                    aria-label={running ? "处理中" : "发送问题"}>
                                {running ? <LoaderCircle size={18} className="spin"/> : <Send size={18}/>}
                            </button>
                        </div>
                    </form>
                    <p>回答仅供业务辅助，请以正式条款、核心系统数据与人工审核结论为准。</p>
                </div>
            </main>

            <aside className="activity-drawer" aria-label="工作流运行详情">
                <div className="activity-header">
                    <div><span>实时工作流</span><strong>运行详情</strong></div>
                    <button className="icon-button" type="button" onClick={() => setActivityOpen(false)}
                            aria-label="关闭运行详情"><X size={18}/></button>
                </div>
                <div className="activity-scroll" ref={stageFollow.containerRef} {...stageFollow.interactionProps}>
                    <WorkflowProgress stages={stages} streams={streams} waiting={waitingForConfirmation}
                                      running={running} connection={connection}/>
                    <details className="event-details">
                        <summary>原始事件 <span>{stages.length}</span></summary>
                        <ol>
                            {stages.map(stage => <StageItem key={stage.key} stage={stage}/>) }
                            {stages.length === 0 && <li className="empty-state compact">工作流启动后会显示节点事件</li>}
                        </ol>
                    </details>
                    <details className="model-output-details">
                        <summary>模型原始输出 <span>{streams.length}</span></summary>
                        <div className="raw-stream-list">
                            {streams.map(stream => <RawStreamItem key={stream.streamId} stream={stream}/>) }
                            {streams.length === 0 && <div className="empty-state compact">收到模型输出后可在这里排查原始内容</div>}
                        </div>
                    </details>
                </div>
                <AutoFollowButton following={stageFollow.following} onResume={stageFollow.resume}/>
            </aside>
            <button className="activity-scrim" type="button" aria-label="关闭运行详情"
                    onClick={() => setActivityOpen(false)}/>
            {pendingDeleteConversations.length > 0 && (
                <DeleteConversationDialog
                    conversations={pendingDeleteConversations}
                    deleting={deletingConversation}
                    onCancel={() => setPendingDeleteConversations([])}
                    onConfirm={deleteConversations}
                />
            )}
        </div>
    );
}

function ConversationSidebar({conversations, activeConversationId, error, disabled,
                                 manageMode, selectedConversationIds, deleting,
                                 onCreate, onSelect, onDelete, onToggleManage,
                                 onToggleSelection, onToggleAll, onDeleteSelected}) {
    const allSelected = conversations.length > 0
        && selectedConversationIds.length === conversations.length;
    return (
        <aside className="conversation-sidebar" aria-label="历史会话">
            <div className="sidebar-brand">
                <span><ShieldCheck size={20}/></span>
                <div><strong>Insurance AI</strong><small>智能业务工作台</small></div>
            </div>
            <button className="new-chat-button" type="button" onClick={onCreate}
                    disabled={disabled || manageMode}>
                <Plus size={17}/><span>新建对话</span>
            </button>
            <div className="conversation-sidebar-header">
                <div>
                    <h2>最近对话</h2>
                    <span>{conversations.length}</span>
                </div>
                <button className="sidebar-manage-button" type="button" onClick={onToggleManage}
                        disabled={disabled || deleting || conversations.length === 0}>
                    {manageMode ? <X size={14}/> : <ListChecks size={14}/>}
                    <span>{manageMode ? "取消" : "管理"}</span>
                </button>
            </div>
            {manageMode && (
                <div className="batch-toolbar">
                    <label>
                        <input type="checkbox" checked={allSelected} onChange={onToggleAll}/>
                        <span>{allSelected ? "取消全选" : "全选"}</span>
                    </label>
                    <span>已选 {selectedConversationIds.length} 项</span>
                    <button type="button" onClick={onDeleteSelected}
                            disabled={selectedConversationIds.length === 0 || deleting}>
                        <Trash2 size={14}/><span>删除</span>
                    </button>
                </div>
            )}
            {error && <div className="conversation-error" role="status">{error}</div>}
            <nav className="conversation-list" aria-label="会话列表">
                {conversations.map(conversation => (
                    <div className="conversation-row" key={conversation.conversationId}
                         data-active={conversation.conversationId === activeConversationId}
                         data-selected={selectedConversationIds.includes(conversation.conversationId)}
                         data-manage={manageMode}>
                        {manageMode && (
                            <label className="conversation-check">
                                <input type="checkbox"
                                       checked={selectedConversationIds.includes(conversation.conversationId)}
                                       onChange={() => onToggleSelection(conversation.conversationId)}
                                       aria-label={`选择会话：${conversation.title || conversation.conversationId}`}/>
                            </label>
                        )}
                        <button className="conversation-select" type="button" disabled={disabled}
                                onClick={() => manageMode
                                    ? onToggleSelection(conversation.conversationId)
                                    : onSelect(conversation.conversationId)}>
                            <MessageSquare size={16}/>
                            <span className="conversation-copy">
                                <strong>{conversation.title || "保险智能体会话"}</strong>
                                <span>{conversation.messageCount} 条消息 · {formatDateTime(conversation.updatedAt)}</span>
                            </span>
                        </button>
                        {!manageMode && <button className="conversation-delete" type="button" disabled={disabled}
                                onClick={event => onDelete(event, conversation)}
                                title="删除会话" aria-label={`删除会话：${conversation.title || conversation.conversationId}`}>
                            <Trash2 size={15}/>
                        </button>}
                    </div>
                ))}
                {conversations.length === 0 && (
                    <div className="conversation-empty">发送第一条问题后，会话将自动保存在这里。</div>
                )}
            </nav>
            <div className="sidebar-footer">
                <SquareActivity size={15}/>
                <span>工作流事件已开启持久化</span>
            </div>
        </aside>
    );
}

function WelcomePanel({onSuggestion}) {
    const suggestions = [
        ["产品对比", "对比盛世典藏和鑫享人生的保障责任与适用人群"],
        ["保险知识", "重疾险的等待期和免赔额分别是什么意思？"],
        ["保单查询", "查询客户 CUST-001 当前有效的保单"],
        ["资产查询", "查询客户 CUST-001 的保险资产概况"]
    ];
    return (
        <section className="welcome-panel">
            <div className="welcome-mark"><Sparkles size={24}/></div>
            <h2>今天想了解什么？</h2>
            <p>我可以协助产品分析、业务知识问答，以及授权范围内的保单和资产查询。</p>
            <div className="suggestion-grid">
                {suggestions.map(([label, prompt]) => (
                    <button type="button" key={label} onClick={() => onSuggestion(prompt)}>
                        <span>{label}</span><strong>{prompt}</strong><ChevronRight size={16}/>
                    </button>
                ))}
            </div>
        </section>
    );
}

function CurrentQuestion({text}) {
    return (
        <article className="message-row user-message">
            <div className="message-avatar"><UserRound size={16}/></div>
            <div className="message-content"><div className="message-label">你</div><p>{text}</p></div>
        </article>
    );
}

function LiveExecution({stages, streams, running, onOpenActivity}) {
    const progressItems = buildUserProgress(stages, streams, running);
    const currentItem = [...progressItems].reverse().find(item => item.status === "RUNNING")
        || progressItems.at(-1);
    const answerStreams = streams.filter(stream => stream.phase === "SUMMARY" && stream.text);
    return (
        <article className="message-row assistant-message live-execution">
            <div className="message-avatar"><Bot size={17}/></div>
            <div className="message-content">
                <div className="message-label">保险智能助理</div>
                <div className="execution-status">
                    {running ? <LoaderCircle size={16} className="spin"/> : <CircleCheck size={16}/>}
                    <span>{running ? "正在处理你的问题" : "处理过程已完成"}</span>
                    <button type="button" onClick={onOpenActivity}><GitBranch size={14}/>查看处理详情</button>
                </div>
                <div className="user-progress-card" role="status" aria-live="polite">
                    <div className="user-progress-current">
                        <span className="user-progress-icon">
                            {running ? <LoaderCircle size={16} className="spin"/> : <CircleCheck size={16}/>}
                        </span>
                        <div>
                            <strong>{currentItem?.title || "正在建立安全连接"}</strong>
                            <p>{currentItem
                                ? currentItem.status === "RUNNING"
                                    ? currentItem.activeDescription
                                    : currentItem.completedDescription
                                : "连接建立后会实时显示处理进度。"}</p>
                        </div>
                    </div>
                    {progressItems.length > 0 && (
                        <ol className="user-progress-steps">
                            {progressItems.map(item => (
                                <li key={item.key} data-status={item.status}>
                                    <span>{item.status === "COMPLETED"
                                        ? <Check size={12}/>
                                        : <LoaderCircle size={12} className="spin"/>}</span>
                                    <strong>{item.title}</strong>
                                </li>
                            ))}
                        </ol>
                    )}
                    {progressItems.length === 0 && running && (
                        <div className="stream-placeholder"><i/><i/><i/></div>
                    )}
                </div>
                {answerStreams.length > 0 && (
                    <section className="live-answer-draft" aria-label="正在生成的回答">
                        <header><Sparkles size={14}/><strong>正在生成答复</strong><span>内容仍可能调整</span></header>
                        {answerStreams.map(stream => (
                            <MarkdownContent key={stream.streamId}
                                             className="live-answer-markdown markdown-content"
                                             content={stream.text}/>
                        ))}
                    </section>
                )}
            </div>
        </article>
    );
}

function ProductConfirmation({candidates, selectedProductCodes, running, onToggle, onSubmit}) {
    return (
        <article className="message-row assistant-message confirmation-message">
            <div className="message-avatar waiting"><ShieldCheck size={17}/></div>
            <div className="message-content">
                <div className="message-label">需要你的确认</div>
                <form id="confirmForm" className="confirmation-card" onSubmit={onSubmit}>
                    <header><div><strong>请选择要继续分析的产品</strong><p>确认后将从当前 Checkpoint 恢复，并继续流式输出。</p></div><span>{candidates.length} 个候选</span></header>
                    <div className="candidate-list">
                        {candidates.map(candidate => (
                            <CandidateItem key={candidate.productCode} candidate={candidate}
                                           checked={selectedProductCodes.includes(candidate.productCode)}
                                           onChange={() => onToggle(candidate.productCode)}/>
                        ))}
                        {candidates.length === 0 && <div className="empty-state compact">没有可确认的候选产品</div>}
                    </div>
                    {candidates.length > 0 && (
                        <button className="primary-button confirmation-submit" type="submit"
                                disabled={running || selectedProductCodes.length === 0}>
                            <Check size={16}/><span>确认并继续</span>
                        </button>
                    )}
                </form>
            </div>
        </article>
    );
}

function DeleteConversationDialog({conversations, deleting, onCancel, onConfirm}) {
    const batchDelete = conversations.length > 1;
    const firstConversation = conversations[0];
    return (
        <div className="dialog-backdrop" role="presentation" onMouseDown={event => {
            if (event.target === event.currentTarget) onCancel();
        }}>
            <section className="confirm-dialog" role="dialog" aria-modal="true"
                     aria-labelledby="deleteConversationTitle">
                <div className="dialog-icon"><Trash2 size={19}/></div>
                <div>
                    <h2 id="deleteConversationTitle">{batchDelete ? "批量删除历史会话" : "删除历史会话"}</h2>
                    <p>{batchDelete
                        ? `选中的 ${conversations.length} 个会话将从列表隐藏。每个会话仍会独立校验运行状态。`
                        : `“${firstConversation.title || "保险智能体会话"}”将从会话列表隐藏。`}
                        历史消息和审计数据仍会保留。</p>
                </div>
                <div className="dialog-actions">
                    <button className="secondary-button" type="button" onClick={onCancel} disabled={deleting}>取消</button>
                    <button className="danger-button" type="button" onClick={onConfirm} disabled={deleting}>
                        {deleting ? "正在删除" : batchDelete ? `删除 ${conversations.length} 个会话` : "删除"}
                    </button>
                </div>
            </section>
        </div>
    );
}

function ConversationHistory({messages, loading}) {
    if (loading) {
        return (
            <div className="history-loading">
                <LoaderCircle className="spin" size={17}/>
                <span>正在加载历史消息</span>
            </div>
        );
    }
    if (messages.length === 0) return null;
    return (
        <section className="history-thread" aria-label="历史对话记录">
            {messages.map(message => (
                <article className="history-message" data-role={message.role} key={message.id}>
                    <div className="history-avatar" aria-hidden="true">
                        {message.role === "USER" ? <UserRound size={15}/> : <Bot size={15}/>} 
                    </div>
                    <div className="history-bubble">
                        <header>
                            <strong>{message.role === "USER" ? "用户" : "保险智能体"}</strong>
                            <time>{formatDateTime(message.occurredAt)}</time>
                        </header>
                        {message.role === "ASSISTANT"
                            ? <MarkdownContent className="history-markdown" content={message.content}/>
                            : <div>{message.content}</div>}
                    </div>
                </article>
            ))}
        </section>
    );
}

function PanelTitle({title, count, follow}) {
    return (
        <div className="panel-title">
            <h2>{title}</h2>
            <div className="panel-tools">
                <AutoFollowButton following={follow.following} onResume={follow.resume}/>
                <span className="counter">{count}</span>
            </div>
        </div>
    );
}

function StageItem({stage}) {
    return (
        <li className="stage-item" data-status={stage.status}>
            <span className="stage-marker"/>
            <div className="stage-content">
                <div className="stage-row">
                    <strong className="stage-name">{stage.name}</strong>
                    <time className="stage-time">{formatTime(stage.occurredAt)}</time>
                </div>
                <span className="stage-detail">{stage.detail}</span>
            </div>
        </li>
    );
}

function RawStreamItem({stream}) {
    return (
        <article className="stream-item" data-phase={stream.phase}
                 data-finished={stream.finished ? "true" : "false"}>
            <header className="stream-header">
                <div>
                    <strong className="stream-phase">{PHASE_NAMES[stream.phase] || stream.phase || "模型输出"}</strong>
                    <span className="stream-agent">
                        {[AGENT_NAMES[stream.agentName] || stream.agentName, stream.taskId].filter(Boolean).join(" · ")}
                    </span>
                </div>
                <span className="stream-state">{stream.finished ? "生成结束 · 待最终审核" : "生成中"}</span>
            </header>
            <pre className="stream-content">{stream.text}</pre>
        </article>
    );
}

function FinalResult({result}) {
    return (
        <section className="final-section">
            <div className="panel-title">
                <h2>最终回答</h2>
                <span className="status-label">{result.status}</span>
            </div>
            <MarkdownContent className="final-answer markdown-content" content={result.answer}/>
        </section>
    );
}

/** 使用 React 节点渲染模型 Markdown；原始 HTML 不会被解释，避免把模型文本注入 DOM。 */
function MarkdownContent({content, className}) {
    return (
        <div className={className}>
            <ReactMarkdown remarkPlugins={[remarkGfm]} skipHtml
                           components={{
                               a: ({node: _node, ...props}) => (
                                   <a {...props} target="_blank" rel="noreferrer noopener"/>
                               )
                           }}>
                {normalizeModelMarkdown(content)}
            </ReactMarkdown>
        </div>
    );
}

/** 将模型常见的不规范标题、列表写法修正为 CommonMark 可识别格式，不解释原始 HTML。 */
function normalizeModelMarkdown(content) {
    return (content || "")
        .replace(/\r\n/g, "\n")
        .replace(/[\u200B\uFEFF]/g, "")
        .replace(/^[\t ]*(?:&nbsp;|\u00A0)+[\t ]*$/gim, "")
        .replace(/\n[\t ]*\n(?:[\t ]*\n)+/g, "\n\n")
        .replace(/^(#{1,6})([^\s#])/gm, "$1 $2")
        .replace(/^([-*+])([^\s*-])/gm, "$1 $2")
        .replace(/^(\d+\.)([^\s])/gm, "$1 $2")
        .trim();
}

/** 把 Graph/Agent 技术事件投影为用户能够理解的少量业务阶段，原始内容仍保留在运行详情。 */
function buildUserProgress(stages, streams, running) {
    return USER_PROGRESS_STEPS.flatMap(step => {
        const relatedStages = stages.filter(stage => step.nodes.includes(stage.node));
        const relatedStreams = streams.filter(stream => step.phases.includes(stream.phase));
        if (relatedStages.length === 0 && relatedStreams.length === 0) return [];

        const latestStage = relatedStages.at(-1);
        const hasRunningStage = latestStage?.status === "RUNNING";
        const hasUnfinishedStream = relatedStreams.some(stream => !stream.finished);
        const stageCompleted = latestStage
            && ["SUCCESS", "COMPLETED"].includes(latestStage.status);
        const streamsCompleted = relatedStreams.length > 0
            && relatedStreams.every(stream => stream.finished);
        const completed = !hasRunningStage && !hasUnfinishedStream && (stageCompleted || streamsCompleted);

        return [{...step, status: completed || !running && step === USER_PROGRESS_STEPS.at(-1)
            ? "COMPLETED"
            : "RUNNING"}];
    });
}

function CandidateItem({candidate, checked, onChange}) {
    return (
        <label className="candidate-item">
            <input className="candidate-checkbox" type="checkbox" value={candidate.productCode}
                   checked={checked} onChange={onChange}/>
            <span className="candidate-body">
                <span className="candidate-heading">
                    <strong className="candidate-name">{candidate.productName}</strong>
                    <span className="candidate-code">{candidate.productCode}</span>
                </span>
                <span className="candidate-meta">
                    {[candidate.productType, candidate.insurerName].filter(Boolean).join(" · ")}
                </span>
                <span className="candidate-reason">{candidate.matchReason || ""}</span>
            </span>
        </label>
    );
}

async function consumeSse(stream, onEvent) {
    const reader = stream.getReader();
    const decoder = new TextDecoder("utf-8");
    let buffer = "";

    while (true) {
        const {value, done} = await reader.read();
        buffer += decoder.decode(value || new Uint8Array(), {stream: !done});
        let frameBoundary = buffer.match(/\r?\n\r?\n/);
        while (frameBoundary && frameBoundary.index !== undefined) {
            const frame = buffer.slice(0, frameBoundary.index);
            buffer = buffer.slice(frameBoundary.index + frameBoundary[0].length);
            const parsed = parseSseFrame(frame);
            if (parsed) onEvent(parsed);
            frameBoundary = buffer.match(/\r?\n\r?\n/);
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
    for (const line of frame.split(/\r?\n/)) {
        if (line.startsWith("id:")) id = line.slice(3).trimStart();
        if (line.startsWith("event:")) event = line.slice(6).trimStart();
        if (line.startsWith("data:")) data.push(line.slice(5).trimStart());
    }
    return data.length === 0 ? null : {id, event, data: data.join("\n")};
}

async function responseError(response) {
    const text = await response.text();
    if (!text) return `HTTP ${response.status}`;
    try {
        const body = JSON.parse(text);
        return body.message || body.error || `HTTP ${response.status}`;
    }
    catch {
        return text.slice(0, 300);
    }
}

async function readApiData(response) {
    if (!response.ok) {
        throw new Error(await responseError(response));
    }
    const body = await response.json();
    if (!body.success) {
        throw new Error(body.message || "请求失败");
    }
    return body.data;
}

function toHistoryMessages(snapshot) {
    const longTermMessages = Array.isArray(snapshot?.longTermMemories)
        ? snapshot.longTermMemories
            .filter(message => message.memoryType === "MESSAGE"
                && (message.role === "USER" || message.role === "ASSISTANT")
                && message.content)
            .map(message => ({
                id: message.memoryId,
                role: message.role,
                content: message.content,
                occurredAt: message.occurredAt || message.createdAt
            }))
            .sort(compareMessages)
        : [];
    if (longTermMessages.length > 0) return longTermMessages;

    return Array.isArray(snapshot?.chatMessages)
        ? snapshot.chatMessages
            .filter(message => (message.messageType === "USER" || message.messageType === "ASSISTANT")
                && message.textContent)
            .map(message => ({
                id: message.messageId,
                role: message.messageType,
                content: message.textContent,
                occurredAt: message.createdAt,
                order: message.messageOrder
            }))
            .sort((left, right) => left.order - right.order)
        : [];
}

function compareMessages(left, right) {
    return new Date(left.occurredAt || 0).getTime() - new Date(right.occurredAt || 0).getTime();
}

function createConversationId() {
    const timestamp = new Date().toISOString().replace(/\D/g, "").slice(0, 14);
    const suffix = Math.random().toString(36).slice(2, 7);
    return `web-${timestamp}-${suffix}`;
}

function createRequestId() {
    return `req-${Date.now()}-${crypto.randomUUID().replaceAll("-", "").slice(0, 12)}`;
}

function readActiveWorkflow() {
    try {
        const value = sessionStorage.getItem(ACTIVE_WORKFLOW_STORAGE_KEY);
        if (!value) return null;
        const workflow = JSON.parse(value);
        if (!workflow?.conversationId
            || ![WORKFLOW_STATUS_RUNNING, WORKFLOW_STATUS_WAITING_CONFIRM].includes(workflow.status)) {
            sessionStorage.removeItem(ACTIVE_WORKFLOW_STORAGE_KEY);
            return null;
        }
        return {
            ...workflow,
            candidates: Array.isArray(workflow.candidates) ? workflow.candidates : [],
            selectedProductCodes: Array.isArray(workflow.selectedProductCodes)
                ? workflow.selectedProductCodes
                : []
        };
    }
    catch {
        return null;
    }
}

function writeActiveWorkflow(workflow) {
    try {
        sessionStorage.setItem(ACTIVE_WORKFLOW_STORAGE_KEY, JSON.stringify(workflow));
    }
    catch {
        // 浏览器禁用会话存储时仍允许当前页面继续消费 SSE，只失去刷新恢复能力。
    }
}

function updateActiveWorkflow(patch) {
    const current = readActiveWorkflow();
    if (!current) return;
    writeActiveWorkflow({...current, ...patch});
}

function clearActiveWorkflow() {
    try {
        sessionStorage.removeItem(ACTIVE_WORKFLOW_STORAGE_KEY);
    }
    catch {
        // 与写入失败保持相同降级语义。
    }
}

function abortableDelay(delayMillis, signal) {
    return new Promise((resolve, reject) => {
        if (signal.aborted) {
            reject(new DOMException("Aborted", "AbortError"));
            return;
        }
        const timeout = window.setTimeout(() => {
            signal.removeEventListener("abort", onAbort);
            resolve();
        }, delayMillis);
        const onAbort = () => {
            window.clearTimeout(timeout);
            reject(new DOMException("Aborted", "AbortError"));
        };
        signal.addEventListener("abort", onAbort, {once: true});
    });
}

function formatTime(value) {
    if (!value) return "";
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? "" : date.toLocaleTimeString("zh-CN", {hour12: false});
}

function formatDateTime(value) {
    if (!value) return "刚刚";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return "";
    return date.toLocaleString("zh-CN", {
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        hour12: false
    });
}

export default App;
