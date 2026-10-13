package com.nekoyu.universe.aichat.image_edit;

import com.google.gson.JsonObject;
import com.nekoyu.Universe.AIChat.Skill.Skill;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.JsonSchema;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.LLMFunction;

import java.util.List;

/** 按照 MapSkill 的方式注册原生 ON_DEMAND 技能。 */
public final class ImageEditSkill {
    private ImageEditSkill() {}

    public static Skill definition() {
        Skill skill = new Skill();
        skill.id = "image_edit";
        skill.name = "图片编辑";
        skill.description = "裁切或旋转用户提供的图片；需要调整图片取景、方向时激活。";
        skill.mode = Skill.Mode.ON_DEMAND;
        skill.toolNames = List.of("ImageEdit_Crop", "ImageEdit_Rotate");
        skill.systemPrompt = """
                你已激活 image_edit 技能，可裁切和旋转图片。
                source_image 优先使用聊天附件引用 <ref id=\"N\"/>，执行前会自动替换为图片 URL；
                也接受用户实际提供的 HTTP/HTTPS 图片直链或本技能此前返回的结果 URL，不得编造图片链接。
                - ImageEdit_Crop：source_image、top_left_position={x,y}、width、height 均必填。
                  坐标原点为左上角，x 向右、y 向下。所有数值都是相对于原图的百分比，允许小数。
                  x、y 范围为 [0,100)，width、height 范围为 (0,100]，x+width 和 y+height 不能超过100。
                  例如保留正中间一半：{"source_image":"<ref id='1'/>","top_left_position":{"x":25,"y":25},"width":50,"height":50}。
                - ImageEdit_Rotate：source_image、angle 必填。角度单位为度，顺时针为正，逆时针为负，允许小数。
                  旋转会扩展画布保留整张图，新空白区域透明；例如顺时针90度传 angle=90。
                工具返回 PNG 图片 URL、实际像素尺寸和发送标记 <image_edit id=\"编号\"/>。
                最终回复原样使用工具返回的发送标记，系统会把它转换为图片消息，不能编造编号。
                连续编辑时把上一工具结果 URL 作为下一次 source_image，最终发送最后的结果。
                仅支持静态图，动画只处理首帧。输入和输出限制为20MiB、1600万像素、单边8192像素。
                结果随会话中图片对象的生命周期保留，不设固定时长或数量淘汰；对象回收后需重新处理。
                用户未提供图片或裁切区域不明确时先询问。失败时如实说明，不要声称编辑成功。
                """;
        return skill;
    }

    public static List<LLMFunction> tools(ImageEditService service) {
        return List.of(
                function(service, "ImageEdit_Crop", "crop", "按原图百分比裁切图片，输出 PNG。",
                        JsonSchema.object()
                                .property("source_image", sourceImage())
                                .property("top_left_position", JsonSchema.object()
                                        .description("裁切左上角在原图中的百分比位置")
                                        .property("x", JsonSchema.number().description("横向位置，[0,100)"))
                                        .property("y", JsonSchema.number().description("纵向位置，[0,100)"))
                                        .required("x", "y"))
                                .property("width", JsonSchema.number().description("宽度占原图的百分比，(0,100]"))
                                .property("height", JsonSchema.number().description("高度占原图的百分比，(0,100]"))
                                .required("source_image", "top_left_position", "width", "height")),
                function(service, "ImageEdit_Rotate", "rotate", "旋转图片并扩展透明画布，顺时针为正，输出 PNG。",
                        JsonSchema.object()
                                .property("source_image", sourceImage())
                                .property("angle", JsonSchema.number().description("角度，单位为度，顺时针为正，逆时针为负"))
                                .required("source_image", "angle"))
        );
    }

    private static JsonSchema sourceImage() {
        return JsonSchema.string().description("聊天图片引用 <ref id=\"N\"/>、用户提供的 HTTP/HTTPS 图片直链或先前的编辑结果 URL");
    }

    private static LLMFunction function(ImageEditService service, String name, String action,
                                        String description, JsonSchema parameters) {
        return LLMFunction.builder().name(name).description(description).parameters(parameters)
                .syncCallback(args -> {
                    // ChatContext 会注入占位符和 _ToolCallId，此处只复制工具声明的字段。
                    if (args == null || !args.isJsonObject()) return service.edit(action, args);
                    JsonObject fields = new JsonObject();
                    parameters.properties.keySet().forEach(field -> {
                        if (args.getAsJsonObject().has(field)) fields.add(field, args.getAsJsonObject().get(field));
                    });
                    return service.edit(action, fields);
                }).build();
    }
}
