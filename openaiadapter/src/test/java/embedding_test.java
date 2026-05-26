import com.google.gson.Gson;
import com.nekoyu.Universe.API.MessageChannel.MFChain;
import com.nekoyu.Universe.API.MessageChannel.MessageField.TextField;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.EmbeddingRequest;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.EmbeddingResponse;
import com.nekoyu.universe.openaiadapter.OpenAIChannel;

public class embedding_test {
    public static void main(String[] args) {
        OpenAIChannel openAIChannel = new OpenAIChannel();
        openAIChannel.setBaseurl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        openAIChannel.setApiKey(System.getenv("OPENAI_API_KEY"));
        EmbeddingRequest embeddingRequest = new EmbeddingRequest();
        embeddingRequest.message = new MFChain();
        embeddingRequest.model = "text-embedding-v4";
        embeddingRequest.message.add(new TextField("《異環》是一款開放世界動作角色扮演遊戲，由完美世界子公司「幻塔工作室」開發。玩家在遊戲中扮演一名鑑定師，一邊探索世界，一邊與敵對勢力作戰。故事將從海特洛市啟篇，玩家將與個性迥異、能力非凡的夥伴們一起探索各城市的大小謎團，歷經有笑有淚的各式奇遇，演繹獨屬於你們的都市物語。"));
        EmbeddingResponse embeddingResponse = openAIChannel.embedding(embeddingRequest);
        System.out.println(new Gson().toJson(embeddingResponse));
    }
}
