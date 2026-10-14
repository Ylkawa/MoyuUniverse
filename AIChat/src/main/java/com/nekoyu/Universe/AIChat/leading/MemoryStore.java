package com.nekoyu.Universe.AIChat.leading;

import com.nekoyu.Universe.AIChat.Memory;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/** 记忆持久化边界；离线测试可替换存储，不连接真实向量库。 */
public interface MemoryStore {
    double[] embedding(String text) throws IOException;
    List<Memory.Item> query(List<Float> vector, List<String> locations);
    List<Memory.Item> getLastMemoryItems(List<String> locations, int limit);
    List<Memory.Item> getHighImportanceItems(List<String> locations, int limit, float importance);
    void insert(List<Memory.Item> items);
    void update(UUID id, float confidence, float importance, String content);
    void delete(UUID id);
}
