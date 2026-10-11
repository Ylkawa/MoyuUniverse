package com.nekoyu.Universe.API.MessageChannel.MessageField;

import com.nekoyu.Universe.API.UniverseChannel;

import java.io.File;
import java.io.IOException;
import java.lang.ref.Cleaner;
import java.net.URL;

public class FileField extends MsgField {
    public String description;
    private static final Cleaner cleaner = Cleaner.create();

    private static class State implements Runnable {

        private URL url;
        private boolean reposted = false;

        State(URL url, boolean reposted) {
            this.url = url;
            this.reposted = reposted;
        }

        void update(URL newUrl, boolean reposted) {
            this.url = newUrl;
            this.reposted = reposted;
        }

        @Override
        public synchronized void run() {
            if (reposted) {
                reposted = false;
                UniverseChannel.releaseRepost(url);
            }
        }
    }

    private final State state;
    private final Cleaner.Cleanable cleanable;

    public FileField(URL url) {
        super.type = "file";

        this.state = new State(url, UniverseChannel.retainRepost(url));
        this.cleanable = cleaner.register(this, state);
    }

    /** 将本地文件交由 repost() 使用的缓存和 Cleaner 管理。 */
    public FileField(File file) throws IOException {
        super.type = "file";
        this.state = new State(UniverseChannel.cacheLocalFile(file), true);
        this.cleanable = cleaner.register(this, state);
    }

    public URL getUrl() {
        synchronized (state) {
            return state.url;
        }
    }

    public void setUrl(URL url) {
        synchronized (state) {
            if (state.url.toString().equals(url.toString())) return;
            boolean retained = UniverseChannel.retainRepost(url);
            if (state.reposted) UniverseChannel.releaseRepost(state.url);
            state.update(url, retained);
        }
    }

    public void repost() throws IOException {
        synchronized (state) {
            if (state.reposted) return;
            state.update(UniverseChannel.repostFile(state.url), true);
        }
    }

    @Override
    public String toString() {
        return "[文件]";
    }
}
