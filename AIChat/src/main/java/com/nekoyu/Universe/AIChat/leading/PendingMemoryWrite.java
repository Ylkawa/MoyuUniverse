package com.nekoyu.Universe.AIChat.leading;

import com.nekoyu.Universe.AIChat.Memory;

import java.util.Map;
import java.util.UUID;

/** 保存已校验的写入意图，失败重试时不重新推理、不重新分配新增 UUID。 */
public record PendingMemoryWrite(Kind kind, Memory.Item item) {
    public enum Kind { ADD, UPDATE, DELETE }

    public void apply(MemoryStore store, Map<UUID, Memory.Item> cache) {
        if (kind == Kind.ADD) store.insert(java.util.List.of(item));
        else if (kind == Kind.UPDATE) store.update(item.id, item.confidence, item.importance, item.content);
        else store.delete(item.id);
        // 只在持久化成功后更新会话缓存。
        if (kind == Kind.DELETE) cache.remove(item.id);
        else {
            item.updateAt = System.currentTimeMillis();
            cache.put(item.id, item);
        }
    }
}
