package com.nekoyu.Universe.API.MessageChannel.MessageField;

import com.nekoyu.Universe.API.UniverseChannel;
import com.nekoyu.Universe.Universe;

import java.io.IOException;
import java.lang.ref.Cleaner;
import java.net.URL;

public class FileField extends MsgField {
    String description;
    private static final Cleaner cleaner = Cleaner.create();

    private static class State implements Runnable {

        private URL url;
        private boolean reposted = false;

        State(URL url) {
            this.url = url;
        }

        void update(URL newUrl, boolean reposted) {
            this.url = newUrl;
            this.reposted = reposted;
        }

        @Override
        public void run() {
            if (reposted) {
                UniverseChannel.releaseRepost(url);
            }
        }
    }

    private final State state;
    private final Cleaner.Cleanable cleanable;

    public FileField(URL url) {
        super.type = "file";

        this.state = new State(url);
        this.cleanable = cleaner.register(this, state);
    }

    @Override
    public String getAsString() {
        return "[文件]";
    }

    public URL getUrl() {
        return state.url;
    }

    public void setUrl(URL url) {
        state.url = url;
        state.update(url, state.reposted);
    }

    public void repost() throws IOException {
        if (state.reposted) return;
        URL newUrl = UniverseChannel.repostFile(state.url);
        state.url = newUrl;
        state.update(newUrl, true);
    }
}
