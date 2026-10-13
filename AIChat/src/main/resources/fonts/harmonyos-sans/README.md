# HarmonyOS Sans 字体资源

AIChat 图表使用 **HarmonyOS Sans SC**。Copyright 2021 Huawei Device Co., Ltd.
字体按 HarmonyOS Sans Fonts License Agreement 随 AIChat 软件分发，原始许可见同目录 `LICENSE.txt`。

来源：华为开发者联盟官方字体包
https://developer.huawei.com/images/download/general/HarmonyOS-Sans.zip

从官方包 `HarmonyOS Sans/HarmonyOS_Sans_SC/` 提取以下文件，保留原始字节，未做修改、子集化或格式转换：

| 文件 | SHA-256 |
|---|---|
| HarmonyOS_Sans_SC_Regular.ttf | 297b088424be212207df2ce8b98e335468b782aa6b96832af0b8b773d711e2b1 |
| HarmonyOS_Sans_SC_Bold.ttf | 43a424b85e47fb53a17b3b32026a71801f86f8e022ca6798d186b47d39fa5f01 |

默认通过 `Font.createFont()` 从 JAR 内加载，无需安装系统中文字体或全局注册字体。
Regular 与 Bold 使用各自的原始字体文件；加载结果在进程内复用。
字体资源缺失或损坏时明确报错，不静默替换为系统字体。
