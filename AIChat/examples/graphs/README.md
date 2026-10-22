# 图表效果示例

这些数据用于检查图表的内容、排版和连接表达。硬件配置、编译时间、告警阈值与发布日程均为演示设定。

| XML | 示例内容 | 可检查的效果 |
|---|---|---|
| list.xml | 家庭 NAS 上线检查清单 | 多条正文、自然换行、不同图标 |
| compare.xml | 笔记本与固定工作站配置 | 同类指标在两列中对照 |
| timeline.xml | 图表功能的两周发布计划 | 六个时间点、具体任务与产出 |
| tree.xml | AIChat 会话与图表模块 | 三层结构、同级分支与父子连线 |
| wide-tree.xml | 增加导出节点的 AIChat 宽树 | 横向子树排布；窄画布下的层级展开 |
| flow.xml | graph 解析至图片返回 | 条件分支、汇合、成功与失败出口 |
| relation.xml | 图表能力相关服务 | 多个组件间的数据关联与关系索引 |

`GraphExampleGenerator` 使用真实的 GraphDecoupler 与 ImageField 输出路径。
所有示例默认使用工件中的 HarmonyOS Sans SC Regular、Bold，无需指定系统字体。
演示图标是生成器内的本地 SVG 素材，按明确的 icon 键绑定；不调用真实 embedding 服务，不用来评估模型匹配质量。
正式 AIChat 使用预建的本地图标向量和持久文字缓存，本次模型为 tongyi-embedding-vision-plus-2026-03-06；示例生成器不改变生产匹配逻辑。

生成图片在 `AIChat/target/graph-render-preview/examples/`，每种类型按页码输出。
仅跨页关系或图面放不下的连线标签产生关系索引页。目录中的 `index.md` 列出本次输出的图片和 XML。

生成器入口：`src/test/java/com/nekoyu/Universe/AIChat/graph/GraphExampleGenerator.java`。
在仓库根目录运行：

```powershell
mvn -B -pl AIChat -am test
./AIChat/scripts/render-graph-examples.ps1
```

之后修改本目录的 XML，再运行脚本即可更新图片。

生成器可额外接收画布宽度与页高：输入目录、输出目录、width、pageHeight；例如 640 800 用于窄画布分页检查。
