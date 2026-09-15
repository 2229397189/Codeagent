package com.codeagent.context;

import com.codeagent.core.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * micro-compact：每步确定性裁剪。
 * 距当前较远的工具结果内容替换为 [used tool X] 占位，但保留 read_file 等"参考材料"结果
 * （删了要重读，不如留着）；保留最近若干轮次。
 */
public class MicroCompact {
    private final int keepRounds;
    private final Set<String> keepTools;

    public MicroCompact(int keepRounds, Set<String> keepTools) {
        this.keepRounds = keepRounds;
        this.keepTools = keepTools;
    }

    public List<Message> apply(List<Message> messages) {
        List<Message> out = new ArrayList<>();
        int n = messages.size();
        int keepFrom = Math.max(0, n - keepRounds * 2); // 每轮 ~ assistant+tool 两条
        for (int i = 0; i < n; i++) {
            Message m = messages.get(i);
            if (m.role == Message.Role.tool
                    && !keepTools.contains(m.name)
                    && i < keepFrom
                    && m.content != null
                    && !m.content.startsWith("[used tool")) {
                Message c = Message.tool(m.toolCallId, m.name, "[used tool " + m.name + "]");
                out.add(c);
            } else {
                out.add(m);
            }
        }
        return out;
    }
}
