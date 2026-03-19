package com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.QZoneModule;

import DataObjects.Feed;

import java.util.List;

// 一个selenium实例的封装
public class QZone {
    // 输入 cookies string 来初始化会话
    public QZone() {

    }

    // 自动打开 好友动态 那个页面，多翻几页（翻到页尾等自动加载，循环三次）。利用之前的逻辑自动截取响应体并得到feed的list，return
    public List<Feed> getFeeds(){
        return null;
    }
}
