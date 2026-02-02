# 便签小程序（Java Swing）

一个更像“完整应用”的桌面便签：多便签管理、搜索/标签/归档/置顶、Markdown 预览、主题切换、自动保存与历史版本。

## 运行（Windows / cmd）

需要安装 Java（JRE/JDK 8+ 都可以）。

```bat
cd /d E:\java\app
.\run.cmd
```

首次运行会自动下载依赖到 `lib\`，并编译到 `out\`（以 Java 8 兼容模式编译）。

数据保存在用户目录下的：

- `%USERPROFILE%\.sticky-note-app\notes.json`（便签数据）
- `%USERPROFILE%\.sticky-note-app\history\`（历史版本）
- `%USERPROFILE%\.sticky-note-app\config.properties`（窗口与主题设置）

## 功能

- 多便签：左侧列表 / 右侧编辑
- 搜索与筛选：关键字、标签、是否归档
- 置顶 / 归档 / 删除
- Markdown 预览（编辑/预览切换）
- 自动保存：停止输入一小段时间后保存；退出/失焦也会保存
- 历史版本：保存时写入快照，可回滚
- 主题：亮色 / 暗色 / 跟随系统（FlatLaf）
- 记住窗口位置、大小、分割条位置与置顶状态
