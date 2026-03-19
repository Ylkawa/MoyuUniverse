import com.google.gson.annotations.SerializedName;

import java.util.LinkedList;

public class FeedResponse {
    public int code;
    public int subcode;
    public String message;
    @SerializedName("default")
    public int default_;
    public Data data;

    public FeedResponse() {
        this.data = new Data();
    }

    public static class Data {
        public Main main;
        public LinkedList<Feed> data;

        public Data() {
            this.main = new Main();
            this.data = new LinkedList<>();
        }

        public static class Main {
            public String attach;
            public String searchtype;
            public boolean hasMoreFeeds;
            public String daylist;
            public String uinlist;
            public String error;
            public String hotkey;
            public LinkedList<Object> icGroupData;
            public String host_level;
            public String friend_level;
            public String lastaccesstime;
            public String lastAccessRelateTime;
            public String begintime;
            public String endtime;
            public String dayspac;
            public LinkedList<Object> hidedNameList;
            public String aisortBeginTime;
            public String aisortEndTime;
            public String aisortOffset;
            public String aisortNextTime;
            public String owner_bitmap;
            public String pagenum;
            public String externparam;

            public Main() {
                this.icGroupData = new LinkedList<>();
                this.hidedNameList = new LinkedList<>();
            }
        }

        public static class Feed {
            public String ver;
            public String appid;
            public String typeid;
            public String key;
            public String flag;
            public String dataonly;
            public String titleTemp;
            public String summaryTemp;
            public String feedno;
            public String title;
            public String summary;
            public String appiconid;
            public String clscFold;
            public String abstime;
            public String feedstime;
            public String userHome;
            public String namecardLink;
            public String opuin;
            public String uin;
            public String ouin;
            public String foldFeed;
            public String foldFeedTitle;
            public String showEbtn;
            public String scope;
            public String hideExtend;
            public String nickname;
            public LinkedList<Object> emoji;
            public String remark;
            public String type;
            public String vip;
            public String bitmap;
            public String yybitmap;
            public String info_user_name;
            public String logimg;
            public String bor;
            public String lastFeedBor;
            public String list_bor2;
            public String info_user_display;
            public String upernum;
            public String oprType;
            public String moreflag;
            public String otherflag;
            public String rightflag;
            public SameUser sameuser;
            public LinkedList<Object> uper_isfriend;
            public LinkedList<Object> uperlist;
            public String smallstar;
            public String html;
            public LinkedList<Object> mergeData;
            public String likecnt;
            public String relycnt;
            public String commentcnt;

            public Feed() {
                this.emoji = new LinkedList<>();
                this.uper_isfriend = new LinkedList<>();
                this.uperlist = new LinkedList<>();
                this.mergeData = new LinkedList<>();
                this.sameuser = new SameUser();
            }
        }

        public static class SameUser {
            // 根据实际数据扩展
        }
    }
}