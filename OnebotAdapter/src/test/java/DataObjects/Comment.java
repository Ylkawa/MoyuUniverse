package DataObjects;

import lombok.Data;
import java.util.List;
import java.util.ArrayList;

@Data
public class Comment {
    private String uin;
    private String nick;
    private String avatarUrl;
    private String content;
    private String time;
    private List<Image> images;
    private List<Comment> replies;    // 回复列表

    public Comment() {
        this.images = new ArrayList<>();
        this.replies = new ArrayList<>();
    }
}
