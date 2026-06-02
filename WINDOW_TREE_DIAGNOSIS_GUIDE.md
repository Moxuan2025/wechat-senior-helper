# 微信窗口树诊断指南

## 概述
本指南帮助你诊断和解决微信无障碍树 `childCount=0` 的问题，通过详细的日志监控来识别根本原因。

## 问题背景
微信可能使用 SurfaceView、WebView 或自定义渲染组件，导致 AccessibilityNodeInfo 的 childCount 为 0，即使窗口被识别也无法获取子节点。

## 诊断策略

### 1. 窗口选择优先级
系统采用以下策略选择微信窗口：

1. **优先选择 childCount > 0 的微信窗口**
2. **若有多个候选窗口：**
   - 优先选择 `isActive=true` 或 `isFocused=true` 的窗口
   - 其次按 `layer` 最低选择（最底层通常是主界面）
3. **备选方案：**
   - 若选中窗口 `childCount == 0`，尝试使用 `service.rootInActiveWindow` 作为备用
   - 仅当其为微信且 `childCount > 0` 时使用

### 2. 关键诊断日志点

#### A. 窗口列表信息
查找如下格式的日志：
```
窗口[0]: id=0, type=0, layer=0, isActive=true, isFocused=true, pkg=com.tencent.mm, className=android.widget.FrameLayout, childCount=45
窗口[1]: id=1, type=2, layer=1, isActive=false, isFocused=false, pkg=com.tencent.mm, className=android.view.SurfaceView, childCount=0
```

**关注点：**
- `pkg` 是否为 `com.tencent.mm`
- `childCount` 是否大于 0
- `isActive` 和 `isFocused` 状态
- `type` 和 `layer` 值
- `className` 是否提示 SurfaceView/WebView

#### B. 候选窗口选择
```
找到候选微信窗口: isActive=true, isFocused=true, layer=0
选择窗口: isActive=true, isFocused=true, layer=0, childCount=45
```

#### C. 备选方案触发
```
备选 rootInActiveWindow: pkg=com.tencent.mm childCount=38
```

#### D. 成功/失败标志
```
✅ 获取到稳定微信树: nodeCount=156
❌ 经过 6 次重试仍未获取到稳定微信树
```

## 使用方法

### 方法 1：使用诊断脚本（推荐）

```bash
# 赋予执行权限
chmod +x scripts/monitor_window_tree.sh

# 运行窗口树诊断监控
./scripts/monitor_window_tree.sh
```

### 方法 2：使用综合监控脚本

```bash
# 赋予执行权限
chmod +x scripts/monitor_all.sh

# 运行综合监控（包含所有组件）
./scripts/monitor_all.sh
```

### 方法 3：手动 ADB 命令

```bash
# 仅监控窗口树提供者
adb logcat WeChatWindowTreeProvider:V *:S

# 监控所有相关组件
adb logcat \
    WeChatWindowTreeProvider:V \
    WeChatAssistService:V \
    FloatingBallService:V \
    WeChatAnchorPageDetector:V \
    *:S

# 保存日志到文件
adb logcat WeChatWindowTreeProvider:V *:S > window_tree_log.txt
```

## 测试流程

### 步骤 1：准备阶段
```bash
# 1. 连接设备并确认 ADB 可用
adb devices

# 2. 清除旧日志
adb logcat -c

# 3. 启动日志监控
./scripts/monitor_window_tree.sh
```

### 步骤 2：重现问题场景
1. 启动微信老年助手应用
2. 授予无障碍服务权限
3. 授予悬浮窗权限
4. 启动悬浮球服务
5. 点击"打开微信"按钮
6. 观察日志输出

### 步骤 3：分析日志

#### 正常情况示例：
```
[WINDOW] 窗口[0]: id=0, type=0, layer=0, isActive=true, isFocused=true, pkg=com.tencent.mm, className=android.widget.FrameLayout, childCount=45
[WINDOW] 窗口[1]: id=1, type=2, layer=1, isActive=false, isFocused=false, pkg=com.tencent.mm, className=android.view.SurfaceView, childCount=0
[WINDOW] 找到候选微信窗口: isActive=true, isFocused=true, layer=0
[WINDOW] 选择窗口: isActive=true, isFocused=true, layer=0, childCount=45
[SERVICE] ✅ 获取到稳定微信树: nodeCount=156
```

#### 异常情况示例 1 - 所有窗口 childCount=0：
```
[WINDOW] 窗口[0]: id=0, type=0, layer=0, isActive=true, isFocused=true, pkg=com.tencent.mm, className=android.view.SurfaceView, childCount=0
[WINDOW] 窗口[1]: id=1, type=2, layer=1, isActive=false, isFocused=false, pkg=com.tencent.mm, className=android.view.View, childCount=0
[WINDOW] 找到候选微信窗口: isActive=true, isFocused=true, layer=0
[WINDOW] 选择窗口: isActive=true, isFocused=true, layer=0, childCount=0
[WINDOW] 备选 rootInActiveWindow: pkg=com.tencent.mm childCount=0
[SERVICE] ❌ 经过 6 次重试仍未获取到稳定微信树
```

**可能原因：**
- 微信使用了 SurfaceView 或 WebView 渲染
- 微信对无障碍做了屏蔽（importantForAccessibility=false）
- 悬浮球覆盖导致微信不是前台窗口

#### 异常情况示例 2 - 未找到微信窗口：
```
[WINDOW] 可用窗口数: 2
[WINDOW] 窗口[0]: id=0, type=0, layer=0, isActive=true, isFocused=true, pkg=com.android.launcher, className=..., childCount=25
[WINDOW] 窗口[1]: id=1, type=2, layer=1, isActive=false, isFocused=false, pkg=com.example.wechat_senior_helper, className=..., childCount=8
[WINDOW] windows 列表中未找到微信窗口，尝试 rootInActiveWindow...
[WINDOW] rootInActiveWindow 包名不匹配: com.android.launcher
[SERVICE] ❌ 经过 6 次重试仍未获取到稳定微信树
```

**可能原因：**
- 微信未启动
- 微信在后台运行
- 当前显示的是其他应用

## 问题排查清单

### 若仍出现 childCount=0，检查以下内容：

#### 1. 微信渲染方式
- 查看 `className` 字段
- 若为 `android.view.SurfaceView` 或 `android.webkit.WebView`，说明微信使用了特殊渲染
- **解决方案：** 尝试调整 `minNodeCount` 参数或使用指数回退重试策略

#### 2. 窗口可见性
- 确认微信界面确实可见（未被完全遮挡）
- 检查悬浮球是否影响窗口焦点
- **解决方案：** 临时隐藏悬浮球后重试

#### 3. 无障碍屏蔽
- 某些应用会设置 `importantForAccessibility=false`
- 检查是否有安全软件阻止无障碍服务
- **解决方案：** 检查微信设置和系统无障碍权限

#### 4. 重试策略优化
如果默认重试策略不够，可以调整参数：

```kotlin
// 在 WeChatAssistAccessibilityService.kt 中修改
val root = treeProvider.getStableWeChatRoot(
    maxRetry = 8,              // 增加重试次数
    retryDelayMs = 200L,       // 增加重试间隔
    minNodeCount = 5           // 降低最小节点数要求（测试用）
)
```

## 可选改进建议

### 1. 指数回退重试策略
将固定延迟改为指数增长：
```
第1次：500ms
第2次：800ms  
第3次：1000ms
第4次：1500ms
...
```

### 2. 动态 minNodeCount
根据页面类型调整期望的节点数：
- 会话列表页：至少 10 个节点
- 聊天页：至少 15 个节点
- 其他页面：至少 5 个节点

### 3. 窗口类型过滤
优先排除已知会导致 childCount=0 的窗口类型：
```kotlin
if (window.type == AccessibilityWindowInfo.TYPE_SYSTEM || 
    window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
    continue  // 跳过系统窗口和输入法窗口
}
```

## 日志保存与分析

### 保存完整日志
```bash
# 保存带时间戳的日志文件
adb logcat WeChatWindowTreeProvider:V *:S > "window_log_$(date +%Y%m%d_%H%M%S).txt"
```

### 提取关键信息
```bash
# 提取所有窗口信息
grep "窗口\[" window_log.txt

# 提取成功/失败记录
grep -E "✅|❌" window_log.txt

# 统计 childCount=0 的次数
grep "childCount=0" window_log.txt | wc -l
```

## 常见问题 FAQ

### Q1: 为什么有时能获取到节点，有时不能？
**A:** 这通常是因为微信在不同页面使用了不同的渲染方式。主页可能使用标准 View，而视频播放页使用 SurfaceView。

### Q2: rootInActiveWindow 和 windows 列表有什么区别？
**A:** 
- `windows` 列表包含所有窗口（包括后台窗口）
- `rootInActiveWindow` 仅返回当前前台活动窗口的根节点
- 当微信不是前台应用时，`rootInActiveWindow` 不会返回微信节点

### Q3: 如何判断是 SurfaceView 导致的问题？
**A:** 查看日志中的 `className` 字段，如果出现 `android.view.SurfaceView` 且 `childCount=0`，基本可以确定是此原因。

### Q4: 重试次数应该设置为多少？
**A:** 
- 正常情况：6 次足够
- 网络较慢或设备性能较差：8-10 次
- 每次重试间隔建议 120-200ms

## 联系与支持
如遇到无法解决的问题，请提供：
1. 完整的日志文件
2. 微信版本号
3. Android 系统版本
4. 具体的操作步骤
