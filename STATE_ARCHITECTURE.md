# 无障碍服务状态管理架构重构说明

## 核心问题

之前的架构存在**服务状态单一真相来源错误**的问题：
- UI层依赖 `WeChatAssistAccessibilityService.instance != null` 这种**进程内临时引用**
- 这是内存态，不等于系统态
- Compose界面不会自动感知它的变化
- 返回应用时，UI与服务生命周期不同步，出现"需要重启才刷新"的现象

## 重构方案

### 1. 引入状态管理器（单一真相源）

创建 `AccessibilityServiceStateManager` 作为服务状态的唯一权威来源：

```
AccessibilityServiceStateManager
├── serviceStatus: StateFlow<ServiceStatus>  // 响应式状态流
├── isAccessibilityEnabled(Context)          // 系统级启用状态检测
└── refreshStatus(Context)                   // 状态刷新入口
```

**关键设计决策：**
- 使用 `StateFlow` 而非普通变量，确保状态可被观察
- 分离"系统启用状态"与"服务连接状态"两个概念
- 提供 `refreshStatus()` 方法在特定时机主动刷新

### 2. 服务生命周期主动发布状态

`WeChatAssistAccessibilityService` 在生命周期回调中主动通知状态管理器：

```kotlin
onServiceConnected()  → updateStatus(CONNECTED)
onInterrupt()         → updateStatus(DISCONNECTED)
onDestroy()           → updateStatus(DISABLED)
```

**不再直接操作instance变量**，而是通过状态管理器统一管理。

### 3. UI层订阅响应式状态

MainActivity的Composable函数使用 `collectAsState()` 订阅状态流：

```kotlin
val serviceStatus by AccessibilityServiceStateManager.serviceStatus.collectAsState(...)
```

**优势：**
- 状态变化自动触发UI重组，无需手动刷新
- 不依赖Activity生命周期的偶然检查
- 状态来源唯一，避免多处判断不一致

### 4. Activity生命周期辅助刷新

在 `onResume()` 中调用 `refreshStatus()`，确保从系统设置返回时同步最新状态：

```kotlin
override fun onResume() {
    super.onResume()
    AccessibilityServiceStateManager.refreshStatus(this)
}
```

## 重构后的状态链路

### Before（错误架构）
```
用户开启服务 → 系统启动服务 → instance变量赋值 
              ↓
         Activity.onResume()（可能错过时机）
              ↓
         手动检查instance（可能为空）
              ↓
         UI不更新 ❌
```

### After（正确架构）
```
用户开启服务 → 系统启动服务 → onServiceConnected()
                                    ↓
                          StateManager.updateStatus(CONNECTED)
                                    ↓
                          StateFlow发射新值
                                    ↓
                          collectAsState接收到变化
                                    ↓
                          Compose自动重组UI ✅
```

## 状态枚举说明

```kotlin
enum class ServiceStatus {
    DISABLED,      // 系统无障碍未启用
    ENABLED,       // 系统已启用但服务未连接（过渡状态）
    CONNECTED,     // 服务正在运行且已连接（可用状态）
    DISCONNECTED   // 曾连接但被中断（异常状态）
}
```

**UI展示映射：**
- `CONNECTED` → "✅ 无障碍服务已连接"（可用）
- `ENABLED` → "⚠️ 服务已启用但未连接"（等待中）
- `DISCONNECTED` → "❌ 服务已断开"（需重启）
- `DISABLED` → "❌ 无障碍服务未启用"（需开启）

## 技术要点

### 1. StateFlow vs LiveData
选择 `StateFlow` 的原因：
- Kotlin协程原生支持，与项目技术栈一致
- 冷流特性，仅在有人订阅时才活跃
- 支持 `value` 直接访问和 `emit()` 异步发射
- 更好的线程控制能力

### 2. 系统级状态检测
通过 `Settings.Secure` 读取系统无障碍配置：
```kotlin
Settings.Secure.ACCESSIBILITY_ENABLED
Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
```
这是**真正的系统态**，不依赖进程内变量。

### 3. 向后兼容
保留 `WeChatAssistAccessibilityService.instance` 但标记为 `@Deprecated`，给迁移留出缓冲期。

## 下一步开发节点

### ✅ 第一节点：状态层稳定化（已完成）
- [x] 创建 `AccessibilityServiceStateManager`
- [x] 修改服务生命周期调用
- [x] UI层改用 `collectAsState()`
- [x] 添加协程依赖

### 🔄 第二节点：最小事务闭环（待开发）
验证完整流程：
1. 识别到微信窗口
2. 识别页面类型
3. 执行一个最小动作（如点击）
4. 看到结果变化

### 📋 第三节点：页面识别与动作执行分离
- 页面识别器只负责"当前在哪里"
- 执行器只负责"做什么"
- 两者通过清晰接口交互

### 🎯 第四节点：任务调度层
将用户语义转换为步骤序列，接入ASR/TTS/混元等能力。

## 测试验证

### 验证场景1：正常启动
1. 安装应用
2. 点击"去开启无障碍服务"
3. 在系统设置中启用服务
4. 返回应用
5. **预期：** UI立即显示"✅ 无障碍服务已连接"

### 验证场景2：服务断开
1. 在系统设置中关闭服务
2. 返回应用
3. **预期：** UI立即显示"❌ 无障碍服务未启用"

### 验证场景3：后台重启
1. 开启服务后按Home键
2. 从最近任务重新打开应用
3. **预期：** UI正确显示服务状态，无需重启应用

## 关键文件清单

- `AccessibilityServiceStateManager.kt` - 状态管理器（新增）
- `WeChatAssistAccessibilityService.kt` - 服务类（修改）
- `MainActivity.kt` - UI层（修改）
- `build.gradle.kts` - 依赖配置（修改）
- `libs.versions.toml` - 版本管理（修改）

---

**作者：** moxuan  
**重构日期：** 2026-06-02  
**架构原则：** 状态驱动 > 生命周期驱动
