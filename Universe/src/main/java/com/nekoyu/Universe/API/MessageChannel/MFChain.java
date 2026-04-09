package com.nekoyu.Universe.API.MessageChannel;

import com.nekoyu.Universe.API.MessageChannel.MessageField.MsgField;

import java.util.LinkedList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MFChain extends LinkedList<MsgField> {
    public String solveAll(boolean description) {
        var sb = new StringBuilder();
        if (description) {
            int solvedPic = 0;
            ExecutorService executor = Executors.newCachedThreadPool();
            // 逆序遍历
            for (int i = size() - 1; i >= 0; i--) {
                MsgField msgField = get(i);
                switch (msgField.type) {
                    case "image" -> {
                        if (!msgField.isSolved && solvedPic < 3) {
                            executor.execute(() -> {
                                try {
                                    msgField.solve();
                                } catch (Exception ignored) {
                                }
                            });
                            solvedPic++;
                        }
                    }
                    case "voice" -> msgField.solve();
                }
            }
            executor.shutdown();
            try {
                executor.awaitTermination(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        for (MsgField msgField : this) {
            sb.append(msgField.toString());
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return solveAll(false);
    }
}
