package com.codeagent.context;

import com.codeagent.core.Message;

import java.util.List;

/**
 * 上下文治理接口（M3 实现四层压缩：snip / micro / auto-compact / 大结果离屏）。
 * 主循环在每轮调模型前调用 apply()，得到喂给模型的消息视图。
 */
public interface ContextGovernance {
    List<Message> apply(List<Message> messages);
}
