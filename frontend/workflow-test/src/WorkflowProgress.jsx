import React from "react";
import {Check, Circle, LoaderCircle, CircleAlert, GitBranch, Clock3} from "lucide-react";

const NODE_DEFINITIONS = new Map([
    ["resolve-product-reference", ["产品线索解析", "PRODUCT_REFERENCE_RESOLUTION"]],
    ["retrieve-product-candidates", ["候选产品召回"]],
    ["human-confirm-product", ["产品确认"]],
    ["context-alignment", ["上下文对齐", "CONTEXT_ALIGNMENT"]],
    ["intent-recognition", ["意图识别", "INTENT_RECOGNITION"]],
    ["planner-agent", ["任务规划", "PLANNER"]],
    ["dag-executor", ["智能体协作", "SUB_AGENT"]],
    ["summary", ["结果总结", "SUMMARY"]],
    ["output-review", ["输出审核"]]
]);
const PHASE_NODES = new Map([...NODE_DEFINITIONS.entries()]
    .filter(([, definition]) => definition[1])
    .map(([code, definition]) => [definition[1], code]));
const LABELS = {PENDING: "待执行", RUNNING: "进行中", SUCCESS: "已完成", COMPLETED: "已完成",
    FAILED: "失败", ERROR: "异常", WAITING_CONFIRM: "待确认", NOT_USED: "未经过", UNKNOWN: "暂无记录",
    SKIPPED_DEPENDENCY_FAILED: "依赖失败，已跳过"};
export const AGENT_NAMES = {"product-analysis-agent": "产品分析", "knowledge-qa-agent": "保险知识",
    "policy-query-agent": "保单查询", "asset-query-agent": "资产查询"};

/** 只投影收到的事实事件；没有收到完成事件时，不把 Token 结束当成节点成功。 */
export function WorkflowProgress({stages, streams, waiting, running, connection}) {
    const visibleNodeCodes = [];
    const appendNode = code => {
        if (NODE_DEFINITIONS.has(code) && !visibleNodeCodes.includes(code)) visibleNodeCodes.push(code);
    };
    stages.forEach(event => appendNode(event.node));
    streams.forEach(stream => appendNode(PHASE_NODES.get(stream.phase)));
    if (waiting) appendNode("human-confirm-product");

    const tasks = new Map();
    stages.filter(event => event.taskId).forEach(event => tasks.set(event.taskId, event));
    return <>
        <div className="process-summary" role="status">
            {running ? <LoaderCircle size={18} className="spin"/> : waiting ? <Clock3 size={18}/> : <GitBranch size={18}/>}
            <span>{connection.message === "未连接" ? "等待开始" : connection.message}</span>
        </div>
        <ol className="workflow-track" aria-label="主工作流进度">
            {visibleNodeCodes.map(code => {
                const [name, phase] = NODE_DEFINITIONS.get(code);
                const latest = stages.filter(event => event.node === code).at(-1);
                let status = latest?.status || "RUNNING";
                if (waiting && code === "human-confirm-product") status = "WAITING_CONFIRM";
                return <li key={code} data-status={status} className="workflow-node">
                    <span className="node-icon">{["SUCCESS", "COMPLETED"].includes(status) ? <Check size={15}/> : status === "RUNNING" ? <LoaderCircle size={15} className="spin"/> : ["FAILED", "ERROR"].includes(status) ? <CircleAlert size={15}/> : <Circle size={12}/>}</span>
                    <div><strong>{name}</strong><span>{LABELS[status] || status}</span>
                        {code === "dag-executor" && tasks.size > 0 && <ul className="task-track">
                            {[...tasks.values()].map(task => <li key={task.taskId} data-status={task.status}>
                                <span>{AGENT_NAMES[task.agentType] || task.agentType}<small>{task.taskId}</small></span>
                                <em>{LABELS[task.status] || task.status}</em>
                            </li>)}
                        </ul>}
                    </div>
                </li>;
            })}
            {visibleNodeCodes.length === 0 && (
                <li className="empty-state compact">收到节点事件后，将按实际执行路径动态展示</li>
            )}
        </ol>
    </>;
}
