package com.codeagent.session;

import com.codeagent.core.Message;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 会话（M5）：基于 SessionStore 的 JSONL 事件日志。
 * - resume：任何时刻从日志 replay 出完整消息列表（崩溃后可恢复）
 * - rename：日志整体搬迁，历史不丢
 * - fork ：复制当前日志生成新分支，父子此后互不干扰（用于「试一版别的方案」）
 */
public class Session {

    private SessionStore store;
    private String name;

    public Session(Path file, String name) {
        this.store = new SessionStore(file);
        this.name = name;
    }

    /** 打开（不存在则惰性创建）指定目录下的会话 */
    public static Session open(Path sessionsDir, String name) {
        return new Session(sessionsDir.resolve(name + ".jsonl"), name);
    }

    public void append(Message m) {
        store.append(m);
    }

    /** resume：从日志重建完整上下文 */
    public List<Message> messages() {
        return store.replay();
    }

    public long size() {
        return store.size();
    }

    public String name() {
        return name;
    }

    public Path file() {
        return store.file();
    }

    /** 重命名：搬迁日志文件，历史完整保留 */
    public void rename(String newName) {
        Path target = file().getParent().resolve(newName + ".jsonl");
        try {
            Files.move(file(), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("session rename failed: " + e.getMessage(), e);
        }
        this.name = newName;
        this.store = new SessionStore(target);
    }

    /** 分叉：复制当前日志到新会话，此后互不影响 */
    public Session fork(String newName) {
        Path target = file().getParent().resolve(newName + ".jsonl");
        try {
            Files.createDirectories(target.getParent());
            Files.copy(file(), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("session fork failed: " + e.getMessage(), e);
        }
        return new Session(target, newName);
    }
}
