# 微信老年助手 - 无障碍服务使用指南

## 📋 项目概述

本项目实现了基于 Android AccessibilityService 的微信自动化操作框架，能够：
- 识别当前窗口（包名、类名）
- 打印 UI 节点树
- 执行基础动作（点击、返回、输入文本、滚动）

## 🏗️ 项目结构

```
app/src/main/java/com/example/wechat_senior_helper/
├── service/
│   └── WeChatAssistAccessibilityService.kt  # 无障碍服务核心类
├── utils/
│   ├── AccessibilityTreeDumper.kt           # 节点树调试工具
│   └── AccessibilityActionHelper.kt         # 基础动作封装
└── MainActivity.kt                           # 主界面（跳转无障碍设置）
```

## 🚀 快速开始

### 1. 编译安装到真机

```bash
./gradlew installDebug
```

**注意：** 建议使用真机调试，模拟器上微信和无障碍权限可能不稳定。

### 2. 开启无障碍服务

1. 打开 App，点击"去开启无障碍服务"按钮
2. 在系统设置中找到"微信老年助手"
3. 开启该服务

### 3. 验证服务是否生效

1. 打开 Android Studio 的 Logcat 窗口
2. 过滤标签：`WeChatAssistService`
3. 打开微信，切换不同页面（聊天列表、聊天页等）
4. 观察 Logcat 输出：

```
D/WeChatAssistService: ========== 窗口状态变化 ==========
D/WeChatAssistService: 包名: com.tencent.mm
D/WeChatAssistService: 类名: com.tencent.mm.ui.LauncherUI
D/WeChatAssistService: 检测到微信窗口！
D/WeChatAssistService: ========== 开始 Dump UI 树 ==========
D/WeChatAssistService: Node[
D/WeChatAssistService:   className=android.widget.FrameLayout
D/WeChatAssistService:   text="微信"
D/WeChatAssistService:   clickable=true
D/WeChatAssistService:   boundsInScreen=[0, 0, 1080, 2340]
D/WeChatAssistService:   childCount=3
D/WeChatAssistService: ...
```

## 🛠️ 核心功能说明

### 一、节点树调试（AccessibilityTreeDumper）

#### 1. 自动 Dump
当检测到微信窗口时，服务会自动将整个 UI 树输出到 Logcat。

#### 2. 手动查找节点

```kotlin
// 通过文本查找
val node = AccessibilityTreeDumper.findNodeByText(root, "发送")

// 通过 ID 查找
val node = AccessibilityTreeDumper.findNodeById(root, "com.tencent.mm:id/xxx")

// 通过内容描述查找
val node = AccessibilityTreeDumper.findNodeByContentDescription(root, "更多选项")

// 查找可点击的父节点
val clickableParent = AccessibilityTreeDumper.findNearestClickableParent(node)
```

**节点信息包含：**
- className（控件类型）
- text（文本内容）
- contentDescription（内容描述）
- viewIdResourceName（资源 ID）
- clickable（是否可点击）
- enabled（是否可用）
- boundsInScreen（屏幕坐标）
- childCount（子节点数量）

### 二、基础动作（AccessibilityActionHelper）

#### 1. 全局返回

```kotlin
AccessibilityActionHelper.performGlobalBack()
```

#### 2. 点击操作

```kotlin
// 直接点击节点
AccessibilityActionHelper.clickNode(node)

// 通过文本查找并点击
AccessibilityActionHelper.clickNodeByText(root, "发送")

// 通过 ID 查找并点击
AccessibilityActionHelper.clickNodeById(root, "com.tencent.mm:id/send_btn")

// 通过内容描述查找并点击
AccessibilityActionHelper.clickNodeByContentDescription(root, "更多选项")
```

**智能点击策略：**
- 如果目标节点不可点击，自动向上查找最近的可点击父节点

#### 3. 文本输入

```kotlin
// 直接向节点输入
AccessibilityActionHelper.inputText(editTextNode, "你好")

// 通过提示文本查找输入框并输入
AccessibilityActionHelper.inputTextByHint(root, "输入消息", "你好")

// 通过 ID 查找输入框并输入
AccessibilityActionHelper.inputTextById(root, "com.tencent.mm:id/input", "你好")
```

**输入方式优先级：**
1. `ACTION_SET_TEXT`（Android 8+，最稳定）
2. 剪贴板粘贴（fallback 方案）

#### 4. 滚动操作

```kotlin
// 向下滚动
AccessibilityActionHelper.scrollDown(scrollableNode)

// 向上滚动
AccessibilityActionHelper.scrollUp(scrollableNode)
```

## 🧪 最小业务场景 PoC

### PoC-1：识别当前是否为微信主页

```kotlin
// 在 WeChatAssistAccessibilityService 中
private fun isWeChatHome(root: AccessibilityNodeInfo): Boolean {
    // 查找底部导航栏特征文本
    val hasWeChat = AccessibilityTreeDumper.findNodeByText(root, "微信") != null
    val hasContacts = AccessibilityTreeDumper.findNodeByText(root, "通讯录") != null
    val hasDiscover = AccessibilityTreeDumper.findNodeByText(root, "发现") != null
    val hasMe = AccessibilityTreeDumper.findNodeByText(root, "我") != null
    
    return hasWeChat && hasContacts && hasDiscover && hasMe
}
```

### PoC-2：从聊天页执行返回

```kotlin
private fun handleChatPageBack(root: AccessibilityNodeInfo) {
    // 检测聊天页特征（输入框或发送按钮）
    val hasInputBox = AccessibilityTreeDumper.findNodeById(
        root, 
        "com.tencent.mm:id/btn_chat"
    ) != null
    
    if (hasInputBox) {
        Log.d(TAG, "检测到聊天页面，执行返回")
        AccessibilityActionHelper.performGlobalBack()
    }
}
```

## ⚠️ 常见问题与解决方案

### 1. rootInActiveWindow 为空

**原因：** 刚开启服务或窗口未稳定

**解决：** 已实现延迟重试机制（200ms/500ms）

```kotlin
val root = getRootNodeWithRetry(retryCount = 3)
```

### 2. 节点找不到

**原因：** 微信 UI 经常变化，text 不稳定

**解决策略（优先级从高到低）：**
1. `viewIdResourceName`（如果能拿到）
2. `contentDescription`
3. `text`
4. 层级路径

### 3. 点击无效

**原因：** 目标节点不可点击

**解决：** 工具类已自动处理，向上查找可点击父节点

```kotlin
// 自动处理，无需手动判断
AccessibilityActionHelper.clickNode(node)
```

### 4. 输入失败

**原因：** `ACTION_SET_TEXT` 在某些输入框上不可用

**解决：** 工具类已实现 fallback 到剪贴板粘贴

```kotlin
// 自动降级，无需手动处理
AccessibilityActionHelper.inputText(node, "文本")
```

### 5. 监听太吵导致卡顿

**解决：** 在 `accessibility_service_config.xml` 中限定只监听微信

```xml
android:packageNames="com.tencent.mm"
```

**当前配置：** 调试阶段监听所有应用，稳定后改为只监听微信。

## 📝 下一步开发计划

1. **收敛监听范围**
   - 修改 `accessibility_service_config.xml`，设置 `packageNames="com.tencent.mm"`

2. **实现页面识别引擎**
   - 定义页面特征（微信主页、聊天列表、聊天页等）
   - 实现页面状态机

3. **实现意图任务执行器**
   - 接收任务指令（如"给张三发消息"）
   - 分解为原子操作序列
   - 执行并反馈结果

4. **添加可视化调试工具**
   - 在屏幕上 overlay 标记节点位置
   - 实时显示当前页面状态

## 🔍 调试技巧

### 查看完整 UI 树

```bash
adb shell uiautomator dump /sdcard/window_dump.xml
adb pull /sdcard/window_dump.xml .
```

然后用浏览器打开 XML 文件查看完整结构。

### 实时监控日志

```bash
adb logcat | grep -E "WeChatAssistService|AccessibilityTreeDumper|AccessibilityAction"
```

### 检查服务状态

```bash
adb shell settings get secure enabled_accessibility_services
```

## 📚 技术要点总结

1. **无障碍服务生命周期**
   - `onServiceConnected()` → 服务启动
   - `onAccessibilityEvent()` → 接收事件
   - `onInterrupt()` → 服务中断
   - `onDestroy()` → 服务销毁

2. **节点回收原则**
   - 所有通过 `getChild()`、`findNodeXXX()` 获取的节点必须调用 `recycle()`
   - 避免内存泄漏

3. **线程安全**
   - 无障碍服务运行在主线程
   - 耗时操作需要异步处理

4. **权限要求**
   - `BIND_ACCESSIBILITY_SERVICE`（在 Manifest 中声明）
   - 用户手动在系统设置中开启

## ✅ 验收标准

完成以上步骤后，你应该能够：

- ✅ 在系统中看到并开启"微信老年助手"无障碍服务
- ✅ Logcat 中看到微信窗口变化事件（`packageName = com.tencent.mm`）
- ✅ 获取到非空的 `rootInActiveWindow`
- ✅ 遍历并打印完整的 UI 节点树
- ✅ 执行全局返回操作
- ✅ 通过文本/ID/描述查找并点击节点
- ✅ 向输入框输入文本
- ✅ 识别微信主页/聊天页并执行返回

---

**祝开发顺利！** 🎉
