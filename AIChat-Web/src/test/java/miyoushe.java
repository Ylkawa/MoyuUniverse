import com.nekoyu.Universe.AIChat.Web.Miyoushe;

import java.io.IOException;

public class miyoushe {
    public static void main(String[] args) throws IOException {
        Miyoushe.Post post = Miyoushe.fetchPost(8, 75960127);
        System.out.println(post.title);
        System.out.println(post.content);
    }
}
