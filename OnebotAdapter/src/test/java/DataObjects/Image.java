package DataObjects;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class Image {
    private String imageUrl;      // 图片URL
    private String originUrl;     // 原图URL
    private Integer width;        // 宽度
    private Integer height;       // 高度
    private Integer picIndex;     // 图片序号
}
