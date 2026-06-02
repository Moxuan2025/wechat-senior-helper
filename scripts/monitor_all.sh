#!/bin/bash

# 综合日志监控脚本
# 用途：同时监控所有关键组件的日志
# 作者：moxuan

echo "=========================================="
echo "微信老年助手 - 综合日志监控"
echo "=========================================="
echo ""
echo "监控组件："
echo "- WeChatWindowTreeProvider (窗口树)"
echo "- WeChatAssistService (无障碍服务)"
echo "- FloatingBallService (悬浮球)"
echo "- WeChatAnchorPageDetector (页面检测)"
echo "- MainActivity (主活动)"
echo ""
echo "按 Ctrl+C 停止监控"
echo "=========================================="
echo ""

# 清除之前的日志
adb logcat -c

# 开始综合监控
adb logcat \
    WeChatWindowTreeProvider:V \
    WeChatAssistService:V \
    FloatingBallService:V \
    WeChatAnchorPageDetector:V \
    MainActivity:V \
    AccessibilityTreeInspector:V \
    TransactionScheduler:V \
    *:S | while read line; do
    
    # 根据标签和关键词进行颜色编码
    if echo "$line" | grep -q "WeChatWindowTreeProvider"; then
        if echo "$line" | grep -q "窗口\["; then
            echo -e "\033[34m[WINDOW] $line\033[0m"
        elif echo "$line" | grep -q "✅"; then
            echo -e "\033[32m[WINDOW] $line\033[0m"
        elif echo "$line" | grep -q "❌\|未找到"; then
            echo -e "\033[31m[WINDOW] $line\033[0m"
        else
            echo -e "[WINDOW] $line"
        fi
        
    elif echo "$line" | grep -q "WeChatAssistService"; then
        if echo "$line" | grep -q "📱\|✅"; then
            echo -e "\033[32m[SERVICE] $line\033[0m"
        elif echo "$line" | grep -q "❌"; then
            echo -e "\033[31m[SERVICE] $line\033[0m"
        else
            echo -e "[SERVICE] $line"
        fi
        
    elif echo "$line" | grep -q "FloatingBallService"; then
        if echo "$line" | grep -q "✅\|🚀"; then
            echo -e "\033[32m[FLOAT] $line\033[0m"
        elif echo "$line" | grep -q "❌"; then
            echo -e "\033[31m[FLOAT] $line\033[0m"
        else
            echo -e "[FLOAT] $line"
        fi
        
    elif echo "$line" | grep -q "WeChatAnchorPageDetector"; then
        if echo "$line" | grep -q "📱\|✅"; then
            echo -e "\033[32m[DETECTOR] $line\033[0m"
        else
            echo -e "[DETECTOR] $line"
        fi
        
    elif echo "$line" | grep -q "MainActivity"; then
        if echo "$line" | grep -q "✅"; then
            echo -e "\033[32m[MAIN] $line\033[0m"
        elif echo "$line" | grep -q "❌"; then
            echo -e "\033[31m[MAIN] $line\033[0m"
        else
            echo -e "[MAIN] $line"
        fi
        
    else
        echo "$line"
    fi
done
