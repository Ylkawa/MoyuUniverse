package com.nekoyu.MoyuUniverse.Nya.OnebotAdapter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nekoyu.Universe.API.MessageChannel.MFChain;
import com.nekoyu.Universe.API.MessageChannel.MessageField.ImageField;
import com.nekoyu.Universe.API.MessageChannel.MessageField.StickerField;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 使用内存中的 WebSocket 验证实际的 OnebotChannel 请求构造，不建立网络连接。 */
public final class ImageSendTest {
    public static void main(String[] args) throws Exception {
        String imageUrl = "https://example.invalid/Universe/edited.png";
        OnebotChannel channel = new OnebotChannel("ImageSendFixture");
        List<JsonObject> sent = new ArrayList<>();
        var callbackMethod = Class.forName(OnebotChannel.class.getName() + "$Callback")
                .getDeclaredMethod("callback", OBResponse.class);
        callbackMethod.setAccessible(true);
        channel.wsConnection = new WebSocketClient(URI.create("ws://example.invalid/offline")) {
            @Override public boolean isOpen() { return true; }
            @Override public void send(String text) {
                JsonObject request = JsonParser.parseString(text).getAsJsonObject();
                sent.add(request);
                String echo = request.get("echo").getAsString();
                Object callback = ((Map<?, ?>) channel.syncActions).remove(echo);
                OBResponse response = new OBResponse();
                response.status = "ok";
                response.data = JsonParser.parseString("{\"message_id\":77}");
                try { callbackMethod.invoke(callback, response); }
                catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            }
            @Override public void onOpen(ServerHandshake handshake) {}
            @Override public void onMessage(String text) {}
            @Override public void onClose(int code, String reason, boolean remote) {}
            @Override public void onError(Exception error) {}
        };
        try {
            MFChain image = new MFChain.Builder().text("编辑后的图片：")
                    .image(new ImageField(new URL(imageUrl))).build();
            check(channel.sendMessage("group/123", image) == 77, "group send returns acknowledgement");
            check(channel.sendMessage("private/456", image) == 77, "private send returns acknowledgement");
            check(channel.sendMessage("group/123", new MFChain(new StickerField(new URL(imageUrl)))) == 77,
                    "sticker send returns acknowledgement");
            for (int i = 0; i < sent.size(); i++) {
                JsonObject request = sent.get(i);
                check(request.get("action").getAsString().equals("send_msg"), "native send_msg action");
                JsonObject params = request.getAsJsonObject("params");
                check(params.get(i == 1 ? "user_id" : "group_id").getAsString().equals(i == 1 ? "456" : "123"),
                        "correct native target");
                var segments = params.getAsJsonArray("message");
                var imageSegment = segments.get(segments.size() - 1).getAsJsonObject();
                check(imageSegment.get("type").getAsString().equals("image"), "native image segment");
                JsonObject data = imageSegment.getAsJsonObject("data");
                check(data.get("url").getAsString().equals(imageUrl), "图片地址按现有 NapCat 发送方式写入 data.url");
                if (i == 2) check(data.get("sub_type").getAsInt() == 1, "sticker flag preserved");
            }
            check(sent.size() == 3 && channel.syncActions.isEmpty(), "exactly three offline requests completed");
            System.out.println("ImageSendTest passed");
        } finally {
            channel.onMsgEx.shutdownNow();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
