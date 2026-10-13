package com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies;

import com.nekoyu.Universe.API.MessageChannel.MFChain;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.ContentPiece.ContentPiece;

import java.util.ArrayList;
import java.util.List;

public class EmbeddingRequest {
    public String model;
    public List<MFChain> message = new ArrayList<>();
    /** 每项对应一个向量；文字、图片复用现有 ContentPiece，图片支持 HTTP URL 或 data URI。与 message 二选一。 */
    public List<List<ContentPiece>> inputs = new ArrayList<>();
    public Integer dimension;
    /** 同一输入项中的多模态内容是否合并成一个向量。 */
    public boolean enableFusion = false;
    public Integer resolutionLevel;
    /** 调用者指定的语义空间版本，只用于身份校验，不作为云端参数发送。 */
    public String spaceVersion;
}
