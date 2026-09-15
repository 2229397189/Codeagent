package com.codeagent.core;

import java.util.List;

/**
 * 模型抽象。主循环只依赖这个接口，便于切换真实模型 / Mock 模型。
 * 真实实现：OpenAiCompatibleChatModel（GLM / DeepSeek / 通义 / Ollama 通用）。
 * 测试实现：MockChatModel（确定性脚本，无需网络/key）。
 */
public interface ChatModel {
    /**
     * @param messages 完整对话历史（含 system / user / assistant / tool）
     * @param tools    当前可用的工具 schema（空列表 = 纯对话）
     * @return 模型响应（文本和/或工具调用）
     */
    ChatResponse chat(List<Message> messages, List<ToolSpec> tools);
}
