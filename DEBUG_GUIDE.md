# 无障碍服务调试指南

## 🔧 已修复的问题

### 1. 移除了空的 `packageNames` 属性
**问题：** `accessibility_service_config.xml` 中设置了 `android:packageNames=""`，这可能导致系统无法正确绑定服务。

**解决：** 完全移除该属性，让服务监听所有应用（后续可改为 `com.tencent.mm`）。

### 2. 增强了日志输出
**改进：**
- `onServiceConnected()` 使用 `Log.e` 确保日志可见
- 添加了服务类名和包名输出
- `onAccessibilityEvent()` 打印所有事件类型和详细信息
- 添加了事件类型转可读字符串的工具函数

### 3. 添加了服务状态检测
**新增功能：**
- MainActivity 在 `onResume()` 时检查服务状态
- UI 实时显示"✅ 已开启"或"❌ 未开启"
- 添加了"Dump 当前窗口树"按钮用于手动测试

---

## 📋 验证步骤（严格按顺序执行）

### 第一步：重新编译安装

```bash
./gradlew clean
./gradlew installDebug
```

**重要：** 必须重新安装，因为修改了 Manifest 和 XML 配置。

---

### 第二步：开启无障碍服务

1. 打开 App
2. 点击"去开启无障碍服务"按钮
3. 在系统设置中找到"微信老年助手"
4. **关闭后再重新开启**（确保系统重新加载配置）
5. 返回 App，应该看到"✅ 无障碍服务已开启"

---

### 第三步：查看 Logcat（最关键）

#### A. 过滤设置

在 Android Studio Logcat 中：
- **Filter:** `WeChatAssistService` 或 `MainActivity`
- **Level:** 至少 `Debug`，建议 `Verbose`

#### B. 预期看到的日志

**1. 服务启动时（开启无障碍服务后）：**

```
E/WeChatAssistService: ========================================
E/WeChatAssistService: 无障碍服务已连接！
E/WeChatAssistService: 服务类名: com.example.wechat_senior_helper.service.WeChatAssistAccessibilityService
E/WeChatAssistService: 包名: com.example.wechat_senior_helper
E/WeChatAssistService: ========================================
```

**如果看不到这段日志 → 服务根本没启动，跳到"问题排查"章节。**

**2. 打开任意应用时（例如打开微信）：**

```
D/WeChatAssistService: 收到事件类型: TYPE_WINDOW_STATE_CHANGED
D/WeChatAssistService: 事件包名: com.tencent.mm
D/WeChatAssistService: 事件类名: com.tencent.mm.ui.LauncherUI
D/WeChatAssistService: ========== 窗口状态变化 ==========
D/WeChatAssistService: 包名: com.tencent.mm
D/WeChatAssistService: 类名: com.tencent.mm.ui.LauncherUI
D/WeChatAssistService: 事件时间: 1717234567890
D/WeChatAssistService: 检测到微信窗口！
D/WeChatAssistService: ========== 开始 Dump UI 树 ==========
...（节点树内容）
D/WeChatAssistService: ========== UI 树 Dump 完成 ==========
```

**如果看不到这段日志 → 服务启动了但没收到事件，检查"问题排查"第 3 节。**

**3. 点击"Dump 当前窗口树"按钮时：**

```
E/MainActivity: ========== 手动 Dump UI 树 ==========
E/MainActivity: 当前包名: com.tencent.mm
E/MainActivity: 当前类名: com.tencent.mm.ui.LauncherUI
E/MainActivity: ========== Dump 完成 ==========
```

---

## ❓ 问题排查

### 情况 1：Logcat 完全没有 `WeChatAssistService` 的任何日志

**可能原因：**
1. 服务未被系统加载
2. Logcat 过滤条件错误

**排查步骤：**

#### A. 确认 Logcat 过滤正确

```bash
# 在终端运行，看是否有输出
adb logcat | grep -i "WeChatAssistService"
```

如果终端有输出但 Android Studio 没有 → 调整 Android Studio 的 Logcat 过滤器。

#### B. 检查服务是否在系统中注册

```bash
# 查看所有无障碍服务
adb shell settings get secure enabled_accessibility_services
```

应该能看到类似：
```
com.example.wechat_senior_helper/.service.WeChatAssistAccessibilityService
```

如果看不到 → 服务未在系统中注册，检查 Manifest。

#### C. 检查 Manifest 是否正确

确认以下几点：
- ✅ `<service>` 标签在 `<application>` 内部
- ✅ `android:name=".service.WeChatAssistAccessibilityService"` 路径正确
- ✅ `android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"` 存在
- ✅ `<intent-filter>` 包含 `android.accessibilityservice.AccessibilityService`
- ✅ `<meta-data>` 的 `android:resource` 指向正确的 XML

#### D. 检查 XML 配置

```bash
# 确认文件存在
ls app/src/main/res/xml/accessibility_service_config.xml
```

确认内容：
- ✅ 没有 `android:packageNames=""` 属性
- ✅ `android:canRetrieveWindowContent="true"` 存在
- ✅ `android:description` 引用了有效的字符串资源

---

### 情况 2：看到了 `onServiceConnected` 日志，但没有事件日志

**可能原因：**
1. `accessibilityEventTypes` 配置错误
2. 系统权限限制

**排查步骤：**

#### A. 确认配置的事件类型

检查 `accessibility_service_config.xml`：
```xml
android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged|typeViewClicked|typeViewScrolled"
```

#### B. 尝试添加更多事件类型

临时修改为监听所有事件：
```xml
android:accessibilityEventTypes="typeAllMask"
```

重新安装后测试。

#### C. 检查是否有省电限制

某些手机（小米、华为等）会限制后台服务：
- 进入"电池优化"设置
- 将你的 App 设为"不优化"或"允许后台运行"

---

### 情况 3：有事件日志，但 `rootInActiveWindow` 为空

**可能原因：**
1. 窗口尚未稳定
2. 权限不足

**解决方案：**

已在代码中实现延迟重试机制：
```kotlin
if (root == null) {
    Log.w(TAG, "rootInActiveWindow 为空，稍后重试...")
    postDelayed({ dumpCurrentWindowTree() }, 500)
    return
}
```

如果仍然为空，尝试：
1. 等待几秒后再操作
2. 切换到其他应用再切回来
3. 重启无障碍服务（关闭再开启）

---

### 情况 4：UI 显示"❌ 未开启"但系统设置里已经开启

**可能原因：**
- `WeChatAssistAccessibilityService.instance` 为 null

**排查：**

在 Logcat 中搜索 `MainActivity`，看是否有：
```
D/MainActivity: 无障碍服务状态: 未运行
```

如果服务已开启但 instance 为 null：
1. 检查 `onServiceConnected()` 是否被调用
2. 确认 `instance = this` 执行成功
3. 可能是进程被杀死后重启，需要重新开启服务

---

## 🎯 验收标准

完成以下所有步骤即表示无障碍服务正常工作：

- [ ] 能在系统设置中看到并开启"微信老年助手"
- [ ] Logcat 能看到 `onServiceConnected` 的日志（包含服务类名）
- [ ] 打开微信时能看到 `TYPE_WINDOW_STATE_CHANGED` 事件
- [ ] 事件中 `packageName = com.tencent.mm`
- [ ] 能看到完整的 UI 树 Dump 输出
- [ ] App UI 显示"✅ 无障碍服务已开启"
- [ ] 点击"Dump 当前窗口树"按钮能输出当前窗口信息

---

## 📊 快速诊断流程图

```
开始
 ↓
重新编译安装 App
 ↓
开启无障碍服务
 ↓
查看 Logcat 是否有 "无障碍服务已连接"
 ├─ 否 → 检查 Manifest/XML 配置（见情况 1）
 └─ 是 ↓
      打开微信
       ↓
      查看 Logcat 是否有事件日志
       ├─ 否 → 检查事件类型配置/省电限制（见情况 2）
       └─ 是 ↓
            是否看到 rootInActiveWindow
             ├─ 否 → 等待重试或重启服务（见情况 3）
             └─ 是 ↓
                  ✅ 服务正常工作！
```

---

## 🔍 常用调试命令

```bash
# 1. 实时查看无障碍服务日志
adb logcat | grep -E "WeChatAssistService|MainActivity"

# 2. 查看已启用的无障碍服务
adb shell settings get secure enabled_accessibility_services

# 3. 强制停止 App（用于重置服务）
adb shell am force-stop com.example.wechat_senior_helper

# 4. 清除 App 数据（完全重置）
adb shell pm clear com.example.wechat_senior_helper

# 5. 查看当前窗口信息
adb shell dumpsys window windows | grep -E "mCurrentFocus|mFocusedApp"
```

---

## 💡 下一步

服务正常工作后，继续开发：

1. **收敛监听范围**
   - 修改 `accessibility_service_config.xml`，添加 `android:packageNames="com.tencent.mm"`

2. **实现页面识别**
   - 根据 UI 树特征识别微信主页、聊天页等

3. **实现基础动作**
   - 使用 `AccessibilityActionHelper` 执行点击、输入、返回等操作

4. **构建任务引擎**
   - 将业务逻辑分解为原子操作序列

---

**祝调试顺利！** 🚀
