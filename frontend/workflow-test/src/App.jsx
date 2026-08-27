import React, {useCallback, useEffect, useMemo, useRef, useState} from "react";
import {
    ArrowDownToLine,
    Bot,
    Check,
    LoaderCircle,
    MessageSquare,
    Play,
    Plus,
    RotateCcw,
    SquareActivity,
    Trash2,
    UserRound
} from "lucide-react";

const WORKFLOW_API_BASE = "/api/v1/workflows/main";
const MEMORY_API_BASE = "/api/v1/ai/memory";

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

function useAutoFollow(changeToken) {
    const containerRef = useRef(null);
    const [following, setFollowing] = useState(true);

    const scrollToLatest = useCallback(() => {
        const container = containerRef.current;
        if (!container) return;
        container.scrollTop = container.scrollHeight;
    }, []);

    useEffect(() => {
        if (!following) return;
        const frame = requestAnimationFrame(scrollToLatest);
        return () => cancelAnimationFrame(frame);
    }, [changeToken, following, scrollToLatest]);

    const pauseForUser = useCallback(() => {
        const container = containerRef.current;
        if (!container || container.scrollHeight <= container.clientHeight + 2) return;
        setFollowing(false);
    }, []);

    const handleScroll = useCallback(() => {
        const container = containerRef.current;
        if (!container || following) return;
        const distanceFromBottom = container.scrollHeight - container.scrollTop - container.clientHeight;
        if (distanceFromBottom <= 12) {
            setFollowing(true);
        }
    }, [following]);

    const resume = useCallback(() => {
        setFollowing(true);
        requestAnimationFrame(scrollToLatest);
    }, [scrollToLatest]);

    return {
        containerRef,
        following,
        resume,
        interactionProps: {
            onWheel: pauseForUser,
            onPointerDown: pauseForUser,
            onTouchStart: pauseForUser,
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
    const [conversationId, setConversationId] = useState(createConversationId);
    const [question, setQuestion] = useState("");
    const [connection, setConnection] = useState({state: "idle", message: "未连接"});
    const [requestMeta, setRequestMeta] = useState("等待请求");
    const [running, setRunning] = useState(false);
    const [stages, setStages] = useState([]);
    const [streams, setStreams] = useState([]);
    const [candidates, setCandidates] = useState([]);
    const [selectedProductCodes, setSelectedProductCodes] = useState([]);
    const [finalResult, setFinalResult] = useState(null);
    const [conversations, setConversations] = useState([]);
    const [historyMessages, setHistoryMessages] = useState([]);
    const [historyLoading, setHistoryLoading] = useState(false);
    const [conversationError, setConversationError] = useState("");
    const [pendingDeleteConversation, setPendingDeleteConversation] = useState(null);
    const [deletingConversation, setDeletingConversation] = useState(false);

    const controllerRef = useRef(null);
    const runningRef = useRef(false);
    const workflowInstanceIdRef = useRef(null);
    const conversationIdRef = useRef(null);
    const lastEventIdRef = useRef(null);
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
        setRunning(isRunning);
        setConnection({state: stateName, message});
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

    const resetRun = useCallback(() => {
        abortCurrentRequest();
        runningRef.current = false;
        workflowInstanceIdRef.current = null;
        lastEventIdRef.current = null;
        setRunning(false);
        setStages([]);
        setStreams([]);
        setCandidates([]);
        setSelectedProductCodes([]);
        setFinalResult(null);
    }, [abortCurrentRequest]);

    const createNewConversation = useCallback(() => {
        if (runningRef.current) return;
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
        setPendingDeleteConversation(null);
    }, [resetRun]);

    const selectConversation = useCallback(selectedConversationId => {
        if (runningRef.current || selectedConversationId === conversationId) return;
        resetRun();
        setConversationId(selectedConversationId);
        setQuestion("");
        setRequestMeta(`conversationId: ${selectedConversationId}`);
        setConnection({state: "idle", message: "历史会话"});
        void loadConversationHistory(selectedConversationId);
    }, [conversationId, loadConversationHistory, resetRun]);

    const requestDeleteConversation = useCallback((event, selectedConversation) => {
        event.stopPropagation();
        if (runningRef.current) return;
        setPendingDeleteConversation(selectedConversation);
    }, []);

    const deleteConversation = useCallback(async () => {
        const selectedConversationId = pendingDeleteConversation?.conversationId;
        if (!selectedConversationId || runningRef.current || deletingConversation) return;
        setDeletingConversation(true);
        try {
            const response = await fetch(
                `${MEMORY_API_BASE}/conversations/${encodeURIComponent(selectedConversationId)}`,
                {method: "DELETE", headers: {"Accept": "application/json"}}
            );
            await readApiData(response);
            setConversations(current => current.filter(
                conversation => conversation.conversationId !== selectedConversationId));
            if (selectedConversationId === conversationId) {
                createNewConversation();
            }
            setPendingDeleteConversation(null);
        }
        catch (error) {
            setConversationError(error.message || "会话删除失败");
        }
        finally {
            setDeletingConversation(false);
        }
    }, [conversationId, createNewConversation, deletingConversation, pendingDeleteConversation]);

    const addStage = useCallback(event => {
        const status = event.data?.status || event.type.toUpperCase();
        setStages(current => [...current, {
            key: event.eventId || `${event.type}-${Date.now()}-${current.length}`,
            type: event.type,
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
            setCandidates(nextCandidates);
            setSelectedProductCodes(nextCandidates.length > 0 ? [nextCandidates[0].productCode] : []);
            setRunState(false, "等待产品确认", "waiting");
        }
        else if (event.type === "complete") {
            const answer = event.data?.finalAnswer || "";
            setFinalResult({
                answer,
                status: event.data?.status || "COMPLETED"
            });
            setRunState(false, "工作流已完成", "idle");
            void loadConversations();
            void loadConversationHistory(conversationIdRef.current).then(loaded => {
                if (loaded) setFinalResult(null);
            });
        }
        else if (event.type === "error") {
            setRunState(false, event.data?.message || "工作流执行失败", "error");
        }
    }, [addLocalError, addStage, loadConversationHistory, loadConversations, renderStream, setRunState]);

    const openSse = useCallback(async (url, body, extraHeaders = {}) => {
        abortCurrentRequest();
        const controller = new AbortController();
        controllerRef.current = controller;

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

            setConnection({state: "running", message: "实时接收中"});
            await consumeSse(response.body, handleEvent);
            if (runningRef.current) {
                setRunState(false, "连接已结束");
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

    const submitRun = async event => {
        event.preventDefault();
        const message = question.trim();
        const normalizedConversationId = conversationId.trim();
        if (running || !message || !normalizedConversationId) return;

        resetRun();
        conversationIdRef.current = normalizedConversationId;
        setRunState(true, "工作流连接中");
        setRequestMeta(`conversationId: ${normalizedConversationId}`);
        await openSse(`${WORKFLOW_API_BASE}/runs/stream`, {
            message,
            conversationId: normalizedConversationId,
            requestId: createRequestId()
        });
    };

    const confirmProducts = async event => {
        event.preventDefault();
        if (running || !workflowInstanceIdRef.current || selectedProductCodes.length === 0) {
            if (selectedProductCodes.length === 0) {
                setConnection({state: "waiting", message: "请选择至少一个产品"});
            }
            return;
        }

        setRunState(true, "正在恢复工作流");
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
        setSelectedProductCodes(current => current.includes(productCode)
            ? current.filter(code => code !== productCode)
            : [...current, productCode]);
    };

    return (
        <div className="app-shell">
            <header className="topbar">
                <div className="brand-block">
                    <SquareActivity size={24}/>
                    <div>
                        <h1>保险智能体工作流测试台</h1>
                        <p className="connection-status" data-state={connection.state}>{connection.message}</p>
                    </div>
                </div>
                <button className="icon-button topbar-action" type="button" onClick={clearAll}
                        title="清空当前运行" aria-label="清空当前运行">
                    <RotateCcw size={18}/>
                </button>
            </header>

            <div className="application-body">
                <ConversationSidebar
                    conversations={conversations}
                    activeConversationId={conversationId}
                    error={conversationError}
                    disabled={running}
                    onCreate={createNewConversation}
                    onSelect={selectConversation}
                    onDelete={requestDeleteConversation}
                />

                <main className="content-area">
                    <section className="query-band" aria-labelledby="queryTitle">
                    <div className="query-inner">
                        <div className="query-heading">
                            <h2 id="queryTitle">发起分析</h2>
                            <label htmlFor="conversationId">会话编号</label>
                            <input id="conversationId" maxLength={64} autoComplete="off"
                                   value={conversationId} readOnly/>
                        </div>
                        <form id="queryForm" className="query-form" onSubmit={submitRun}>
                            <label className="sr-only" htmlFor="question">问题</label>
                            <textarea id="question" maxLength={2000} rows={3} value={question}
                                      onChange={event => setQuestion(event.target.value)}
                                      onKeyDown={event => {
                                          if (event.key === "Enter" && (event.ctrlKey || event.metaKey)) {
                                              event.preventDefault();
                                              event.currentTarget.form?.requestSubmit();
                                          }
                                      }}
                                      placeholder="输入保险产品、保单、资产或业务知识问题" required/>
                            <div className="query-actions">
                                <span className="request-meta">{requestMeta}</span>
                                <button className="primary-button command-button" type="submit" disabled={running}>
                                    <Play size={16}/>
                                    <span>开始运行</span>
                                </button>
                            </div>
                        </form>
                    </div>
                    </section>

                    <section className="workspace" aria-label="工作流运行结果">
                    <aside className="stage-panel">
                        <PanelTitle title="执行阶段" count={stages.length}
                                    follow={stageFollow}/>
                        <ol className="stage-list" ref={stageFollow.containerRef} {...stageFollow.interactionProps}>
                            {stages.map(stage => <StageItem key={stage.key} stage={stage}/>) }
                            {stages.length === 0 && <li className="empty-state">暂无工作流事件</li>}
                        </ol>
                    </aside>

                    <section className="stream-panel">
                        <PanelTitle title="对话与模型输出" count={historyMessages.length + streams.length}
                                    follow={streamFollow}/>
                        <div className="stream-scroll" ref={streamFollow.containerRef}
                             {...streamFollow.interactionProps}>
                            <ConversationHistory messages={historyMessages} loading={historyLoading}/>
                            <div className="stream-list">
                                {streams.map(stream => <StreamItem key={stream.streamId} stream={stream}/>) }
                                {historyMessages.length === 0 && streams.length === 0 && !finalResult
                                    && !historyLoading && (
                                    <div className="empty-state">历史消息和模型输出将在这里出现</div>
                                )}
                            </div>
                            {finalResult && <FinalResult result={finalResult}/>} 
                        </div>
                    </section>

                    <aside className="confirm-panel">
                        <div className="panel-title">
                            <h2>产品确认</h2>
                            <span className="counter">{candidates.length}</span>
                        </div>
                        <form id="confirmForm" onSubmit={confirmProducts}>
                            <div className="candidate-list">
                                {candidates.map(candidate => (
                                    <CandidateItem key={candidate.productCode} candidate={candidate}
                                                   checked={selectedProductCodes.includes(candidate.productCode)}
                                                   onChange={() => toggleProduct(candidate.productCode)}/>
                                ))}
                                {candidates.length === 0 && (
                                    <div className="empty-state">当前无需人工确认</div>
                                )}
                            </div>
                            {candidates.length > 0 && (
                                <button className="primary-button command-button full-width" type="submit"
                                        disabled={running || selectedProductCodes.length === 0}>
                                    <Check size={16}/>
                                    <span>确认并继续</span>
                                </button>
                            )}
                        </form>
                    </aside>
                    </section>
                </main>
            </div>
            {pendingDeleteConversation && (
                <DeleteConversationDialog
                    conversation={pendingDeleteConversation}
                    deleting={deletingConversation}
                    onCancel={() => setPendingDeleteConversation(null)}
                    onConfirm={deleteConversation}
                />
            )}
        </div>
    );
}

function ConversationSidebar({conversations, activeConversationId, error, disabled,
                                 onCreate, onSelect, onDelete}) {
    return (
        <aside className="conversation-sidebar" aria-label="历史会话">
            <div className="conversation-sidebar-header">
                <div>
                    <h2>历史会话</h2>
                    <span>{conversations.length} 个会话</span>
                </div>
                <button className="icon-button" type="button" onClick={onCreate} disabled={disabled}
                        title="新建对话" aria-label="新建对话">
                    <Plus size={17}/>
                </button>
            </div>
            {error && <div className="conversation-error" role="status">{error}</div>}
            <nav className="conversation-list" aria-label="会话列表">
                {conversations.map(conversation => (
                    <div className="conversation-row" key={conversation.conversationId}
                         data-active={conversation.conversationId === activeConversationId}>
                        <button className="conversation-select" type="button" disabled={disabled}
                                onClick={() => onSelect(conversation.conversationId)}>
                            <MessageSquare size={16}/>
                            <span className="conversation-copy">
                                <strong>{conversation.title || "保险智能体会话"}</strong>
                                <span>{conversation.messageCount} 条消息 · {formatDateTime(conversation.updatedAt)}</span>
                            </span>
                        </button>
                        <button className="conversation-delete" type="button" disabled={disabled}
                                onClick={event => onDelete(event, conversation)}
                                title="删除会话" aria-label={`删除会话：${conversation.title || conversation.conversationId}`}>
                            <Trash2 size={15}/>
                        </button>
                    </div>
                ))}
                {conversations.length === 0 && (
                    <div className="conversation-empty">暂无历史会话，发送第一条问题后会自动保存。</div>
                )}
            </nav>
        </aside>
    );
}

function DeleteConversationDialog({conversation, deleting, onCancel, onConfirm}) {
    return (
        <div className="dialog-backdrop" role="presentation" onMouseDown={event => {
            if (event.target === event.currentTarget) onCancel();
        }}>
            <section className="confirm-dialog" role="dialog" aria-modal="true"
                     aria-labelledby="deleteConversationTitle">
                <div className="dialog-icon"><Trash2 size={19}/></div>
                <div>
                    <h2 id="deleteConversationTitle">删除历史会话</h2>
                    <p>“{conversation.title || "保险智能体会话"}”将从会话列表隐藏，历史消息和审计数据仍会保留。</p>
                </div>
                <div className="dialog-actions">
                    <button className="secondary-button" type="button" onClick={onCancel} disabled={deleting}>取消</button>
                    <button className="danger-button" type="button" onClick={onConfirm} disabled={deleting}>删除</button>
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
                        <div>{message.content}</div>
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

function StreamItem({stream}) {
    return (
        <article className="stream-item" data-phase={stream.phase}
                 data-finished={stream.finished ? "true" : "false"}>
            <header className="stream-header">
                <div>
                    <strong className="stream-phase">{PHASE_NAMES[stream.phase] || stream.phase || "模型输出"}</strong>
                    <span className="stream-agent">
                        {[stream.agentName, stream.taskId].filter(Boolean).join(" · ")}
                    </span>
                </div>
                <span className="stream-state">{stream.finished ? "完成" : "生成中"}</span>
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
            <div className="final-answer">{result.answer}</div>
        </section>
    );
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
