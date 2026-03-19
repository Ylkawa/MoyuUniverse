package DataObjects;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class User {
    private String uin;           // QQ号
    private String nick;          // 昵称
    private String avatarUrl;     // 头像URL
    private String qzoneUrl;      // 空间链接
}
