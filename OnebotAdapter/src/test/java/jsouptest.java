import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.sql.Time;
import java.sql.Timestamp;

public class jsouptest {
    public static void main(String[] args) {
        String html = "<li class=\"f-single f-s-s\" id=\"fct_3276642541_311_0_1773932739_0_1\" read=\"1\">\n" +
                "                    <div class=\"f-single-head f-aside\">\n" +
                "                        <div class=\"f-adorn-top\"></div>\n" +
                "                        <div class=\"user-pto\"><a href=\"http://user.qzone.qq.com/3276642541\" target=\"_blank\"\n" +
                "                                class=\"user-avatar q_namecard f-s-a\" link=\"nameCard_3276642541\"\n" +
                "                                data-clicklog=\"avatar\"><img\n" +
                "                                    src=\"https://qlogo2.store.qq.com/qzone/3276642541/3276642541/50?1766759656\"></a>\n" +
                "                        </div>\n" +
                "                        <div class=\"user-op\"><a href=\"javascript:;\" class=\"arrow-down\" data-cmd=\"qz_opr_more\"\n" +
                "                                data-moreoperate=\"1\"><i class=\"fui-icon icon-arrow-down\"></i></a> </div>\n" +
                "                        <div class=\"user-info\">\n" +
                "                            <div class=\"f-nick\"><a target=\"_blank\" href=\"http://user.qzone.qq.com/3276642541\"\n" +
                "                                    data-clicklog=\"nick\" class=\"f-name q_namecard \" link=\"nameCard_3276642541\">牧夙</a> <a\n" +
                "                                    class=\"user-medal\" hotclickpath=\"isd.qzonemall.year.feeds\"\n" +
                "                                    hotdomain=\"mall.qzone.qq.com\"\n" +
                "                                    href=\"http://pay.qq.com/ipay/index.shtml?n=3&amp;c=xxjzghh,xxjzgw&amp;aid=feed_guajian&amp;ch=qdqb,kj\"\n" +
                "                                    target=\"_blank\" title=\"点击查看黄钻特权详情\"><span class=\"qz-f-vip-l qz-f-vip-l-5\"></span></a>\n" +
                "                            </div>\n" +
                "                            <div class=\"info-detail\"><span class=\" ui-mr8 state\"> 23:05</span><a href=\"javascript:;\"\n" +
                "                                    data-cmd=\"qz_sign\" class=\"f-sign-show state\" title=\"我也要设置\"></a></div>\n" +
                "                        </div>\n" +
                "                    </div>\n" +
                "                    <div class=\"f-single-content f-wrap\">\n" +
                "                        <div class=\"f-item f-s-i\" id=\"feed_3276642541_311_0_1773932739_0_1\" data-feedsflag=\"\"\n" +
                "                            data-iswupfeed=\"1\" data-key=\"ed984dc3c310bc6911650300\" data-specialtype=\"\"\n" +
                "                            data-extend-info=\"0_0_0_0_0_0_0|08009c88060a4001|0000040000000000\"\n" +
                "                            data-functype=\"func_friend_feed\" data-hasfollowed=\"1\">\n" +
                "                            <div class=\"f-info\">每次你找我就没好事发生，<br>天生克我来的吧<img\n" +
                "                                    src=\"http://qzonestyle.gtimg.cn/qzone/em/e400856.gif\" title=\"\"><br>空友不要对号入座喵（）</div>\n" +
                "                            <div class=\"qz_summary wupfeed\" id=\"hex_3276642541_311_0_1773932739_0_1\"><i class=\"none\"\n" +
                "                                    name=\"feed_data\" data-bitmap=\"08009c88060a4001\" data-yybitmap=\"0000040000000000\"\n" +
                "                                    data-vipstarbitmap=\"0000000040000080\" data-fkey=\"ed984dc3c310bc6911650300\"\n" +
                "                                    data-tid=\"ed984dc3c310bc6911650300\" data-uin=\"3276642541\" data-origfkey=\"\"\n" +
                "                                    data-origtid=\"ed984dc3c310bc6911650300\" data-origuin=\"3276642541\" data-subid=\"\"\n" +
                "                                    data-totweet=\"\" data-issignin=\"\" data-source=\"\" data-retweetcount=\"0\"\n" +
                "                                    data-stat=\"exNDgYmCtvi4cQCzC4UdH3C32dVqbWOZaC2!OJ0s34iehCAa9A5klsNNjtNqQpxna1fh26U!kKiSdjSVViDn/oEOh5TbfFzuaC2!OJ0s34iehCAa9A5kllbXGHcHjJSNoCtqSo/wJ4Iu4BjTAxeq0SRR9qzp2sbEXTK6j!SvTvVPpJmb8LCv5gTtTK!JSc70MzAkbzg1AwLWzi7JB5a5btI10ZDVRVlj_\"\n" +
                "                                    data-topicid=\"3276642541_ed984dc3c310bc6911650300__1\" data-feedstype=\"100\"\n" +
                "                                    data-abstime=\"1773932739\" data-iswupfeed=\"1\" data-platformid=\"52\"\n" +
                "                                    data-accessright=\"1\"></i>\n" +
                "                                <div class=\"f-reprint\">\n" +
                "                                    <p class=\"item\"> <i class=\"fui-icon icon-print-phone\"></i><span\n" +
                "                                            class=\"ui-mr8 state\">来自&nbsp;<a href=\"http://z.qzone.com?from=androidgrzxpl\"\n" +
                "                                                target=\"_blank\" class=\" phone-style state\">Xiaomi Civi 4\n" +
                "                                                Pro</a>&nbsp;</span> </p>\n" +
                "                                </div>\n" +
                "                            </div>\n" +
                "                        </div>\n" +
                "                    </div>\n" +
                "                    <div class=\"f-single-foot\">\n" +
                "                        <div class=\"f-op-detail f-detail content-line\">\n" +
                "                            <p class=\"op-list\"> <a class=\"item qz_retweet_btn \" href=\"javascript:;\" data-cmd=\"qz_popup\"\n" +
                "                                    data-version=\"4\" data-isnewtype=\"1\" data-type=\"ForwardingBox\"\n" +
                "                                    data-src=\"/qzone/app/controls/forwardingBox/forwardingBoxFacade.js\"\n" +
                "                                    data-clicklog=\"retweet\" data-publicpav=\"\"> <i class=\"fui-icon icon-op-share\"></i>\n" +
                "                                </a> <span class=\"item-line\"></span> &nbsp;<a href=\"javascript:;\" data-version=\"6.3\"\n" +
                "                                    data-cmd=\"qz_reply\" data-link=\"1\" data-clicklog=\"comment\"\n" +
                "                                    class=\" qz_btn_reply item \"> <i class=\"fui-icon icon-op-comment\"></i> </a>&nbsp;\n" +
                "                                <span class=\"item-line\"></span> <a class=\"item qz_like_btn_v3 \" data-islike=\"0\"\n" +
                "                                    data-likecnt=\"0\" data-showcount=\"20\"\n" +
                "                                    data-unikey=\"http://user.qzone.qq.com/3276642541/mood/ed984dc3c310bc6911650300\"\n" +
                "                                    data-curkey=\"http://user.qzone.qq.com/3276642541/mood/ed984dc3c310bc6911650300\"\n" +
                "                                    data-clicklog=\"like\" href=\"javascript:;\"> <i class=\"fui-icon icon-op-praise\"></i>\n" +
                "                                </a> </p>\n" +
                "                        </div>\n" +
                "                        <div class=\"f-ang-t\"></div>\n" +
                "                        <div class=\"mod-comments\" style=\"padding:0\">\n" +
                "                            <div class=\"mod-commnets-poster feedClickCmd comment_default_inputentry\" data-cmd=\"qz_reply\"\n" +
                "                                data-version=\"6\" data-action=\"http://taotao.qq.com/cgi-bin/emotion_cgi_re_feeds\"\n" +
                "                                data-param=\"t1_source=&amp;t1_uin=3276642541&amp;t1_tid=ed984dc3c310bc6911650300&amp;signin=0&amp;sceneid=100\"\n" +
                "                                data-charset=\"utf-8\" data-maxlength=\"\" data-tuin=\"3276642541\"\n" +
                "                                data-config=\"1|1|1|1,b52,with_fwd,同时转发;0|1,taotaoact.qzone.qq.com,@InputReply|1,taotaoact.qzone.qq.com,@ClickReply|1,taotaoact.qzone.qq.com,commentPresentClick\"\n" +
                "                                data-tid=\"\">\n" +
                "                                <div class=\"comments-poster-bd comments-poster-default\">\n" +
                "                                    <div class=\"comments-box\" data-clicklog=\"comment\">\n" +
                "                                        <div class=\"textinput textinput-default bor2\" contenteditable=\"true\"\n" +
                "                                            alt=\"replybtn\" placeholder=\"评论\"><a class=\"c_tx3\" href=\"javascript:void(0);\"\n" +
                "                                                alt=\"replybtn\">评论</a></div>\n" +
                "                                        <div class=\"mod-insert-img\"><a href=\"javascript:;\"\n" +
                "                                                data-cmd=\"qz_quick_upload_img\" class=\"btn-insert-img bg\"><i\n" +
                "                                                    class=\"icon-camera-16\"></i></a></div>\n" +
                "                                    </div>\n" +
                "                                </div>\n" +
                "                            </div>\n" +
                "                        </div>\n" +
                "                    </div>\n" +
                "                </li>";
        Document doc = Jsoup.parse(html);
        System.out.println("qq: " + doc.getElementsByClass("f-name q_namecard ").get(0).attr("link").split("_")[1]);
        Element img = doc.selectFirst("div.user-pto img");
        String avatarUrl = img.attr("src");
        System.out.println(avatarUrl);
        Element div = doc.selectFirst(".f-info");
        div.select("br").append("\\n");
        String text = div.text().replace("\\n", "\n");
        System.out.println(text);
    }
}
