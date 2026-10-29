package com.nekoyu.MoyuUniverse.Nya.OnebotAdapter;

import com.google.gson.JsonParser;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.Map;

/** 离线回归：资料失败时使用 ID 继续处理会话，实际发送失败仍正常上报。 */
public final class LoginInitializationTest {
    public static void main(String[] args) throws Exception {
        OnebotChannel channel = new OnebotChannel("LoginFixture");
        var initialize = OnebotChannel.class.getDeclaredMethod("initializeLoginAccount", com.google.gson.JsonObject.class);
        initialize.setAccessible(true);
        try {
            initialize.invoke(channel, JsonParser.parseString("{\"user_id\":12345,\"nickname\":\"bot\"}").getAsJsonObject());
            check(channel.accountId.equals("12345") && channel.nickname.equals("bot"), "login identity populated");
            check(channel.loginAccount == channel.getSession("user/12345"), "self lookup works without WebSocket");
            check(channel.loginAccount == channel.getSession("private/12345"), "private alias uses cached self");
            check(channel.loginAccount.getLocationId().equals("QQ:user/12345"), "self location populated");
            check(channel.loginAccount.getSex().equals("unknown"), "login info does not require sex");
            check(channel.loginAccount.getAvatar() != null, "self avatar configured");

            initialize.invoke(channel, JsonParser.parseString("{\"user_id\":12345,\"nickname\":\"renamed\"}").getAsJsonObject());
            check(channel.getSession("user/12345").getName().equals("renamed"), "reconnect refreshes cached self");

            var callbackMethod = Class.forName(OnebotChannel.class.getName() + "$Callback")
                    .getDeclaredMethod("callback", OBResponse.class);
            callbackMethod.setAccessible(true);
            String[] expectedAction = { "get_stranger_info" };
            int[] calls = { 0 };
            channel.wsConnection = new WebSocketClient(URI.create("ws://example.invalid/offline")) {
                @Override public boolean isOpen() { return true; }
                @Override public void send(String text) {
                    var request = JsonParser.parseString(text).getAsJsonObject();
                    check(request.get("action").getAsString().equals(expectedAction[0]), "expected action queried");
                    calls[0]++;
                    String echo = request.get("echo").getAsString();
                    Object callback = ((Map<?, ?>) channel.syncActions).remove(echo);
                    OBResponse response = new OBResponse();
                    response.status = "failed";
                    response.retcode = 1200;
                    response.message = "Cannot read properties of null (reading 'coreInfo')";
                    try { callbackMethod.invoke(callback, response); }
                    catch (ReflectiveOperationException e) { throw new AssertionError(e); }
                }
                @Override public void onOpen(ServerHandshake handshake) {}
                @Override public void onMessage(String text) {}
                @Override public void onClose(int code, String reason, boolean remote) {}
                @Override public void onError(Exception error) {}
            };
            var fallbackUser = channel.getSession("user/67890");
            check(fallbackUser.getName().equals("67890") && fallbackUser.getLocationId().equals("QQ:user/67890"),
                    "failed user profile falls back to ID");
            check(channel.getSession("private/67890") == fallbackUser && calls[0] == 1,
                    "fallback cached without retrying profile lookup");

            expectedAction[0] = "get_group_info";
            var fallbackGroup = channel.getSession("group/54321");
            check(fallbackGroup.getName().equals("54321") && fallbackGroup.getLocationId().equals("QQ:group/54321"),
                    "failed group profile falls back to ID");
            check(calls[0] == 2, "one request per uncached session");

            expectedAction[0] = "send_msg";
            try {
                channel.sendMessage("group/54321", new com.nekoyu.Universe.API.MessageChannel.MFChain());
                throw new AssertionError("failed send must propagate");
            } catch (OBException e) {
                check(e.retcode == 1200, "remote error code preserved");
                check(e.getMessage().contains("send_msg") && e.getMessage().contains("coreInfo"),
                        "error identifies action and preserves remote detail");
            }
            check(channel.syncActions.isEmpty(), "offline callback completed");
            System.out.println("LoginInitializationTest passed");
        } finally {
            channel.onMsgEx.shutdownNow();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
