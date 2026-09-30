#!/usr/bin/env python3
"""仅操作额度原型的真机冒烟检查；不读取认证文件或其他应用页面。"""

import argparse
import re
import subprocess
import os
import shutil
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
# 使用显式 ADB / PATH / Android SDK，不依赖开发者本机目录。
_sdk_root = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
_adb_path = os.environ.get("ADB") or shutil.which("adb") or (str(Path(_sdk_root) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")) if _sdk_root else None)
if not _adb_path:
    raise SystemExit("未找到 adb，请设置 ADB 或 ANDROID_HOME，或者加入 PATH。")
ADB = Path(_adb_path)
PACKAGE = "cn.luxy.codexquota"
DEADLINE = time.monotonic() + 55


def adb(*arguments: str) -> str:
    remaining = DEADLINE - time.monotonic()
    if remaining <= 0:
        raise TimeoutError("真机检查达到 55 秒限制。")
    result = subprocess.run([str(ADB), *arguments], capture_output=True, text=True,
                            timeout=min(15, remaining), check=True)
    return result.stdout


def assert_foreground() -> None:
    windows = adb("shell", "dumpsys", "window")
    focused = next((line for line in windows.splitlines() if "mCurrentFocus=" in line), "")
    if PACKAGE not in focused:
        raise RuntimeError("额度 APP 不在前台或手机已锁屏，已停止操作。")


def hierarchy():
    assert_foreground()
    adb("shell", "uiautomator", "dump", "/data/local/tmp/codex-quota-smoke.xml")
    raw = adb("exec-out", "cat", "/data/local/tmp/codex-quota-smoke.xml")
    root = ET.fromstring(raw)
    return root, [node for node in root.iter("node") if node.get("package") == PACKAGE]


def bounds(node):
    return [int(value) for value in re.findall(r"\d+", node.get("bounds", ""))]


def scroll(nodes, down: bool) -> None:
    rects = [bounds(node) for node in nodes if len(bounds(node)) == 4]
    left, top, right, bottom = max(rects, key=lambda box: (box[2] - box[0]) * (box[3] - box[1]))
    x = (left + right) // 2
    high = top + int((bottom - top) * 0.23)
    low = top + int((bottom - top) * 0.82)
    start, end = (low, high) if down else (high, low)
    assert_foreground()
    adb("shell", "input", "swipe", str(x), str(start), str(x), str(end), "250")


def renew() -> None:
    root, nodes = hierarchy()
    target = next((node for node in nodes if node.get("text") == "验证登录续期"), None)
    if target is None:
        scroll(nodes, True)
        root, nodes = hierarchy()
        target = next((node for node in nodes if node.get("text") == "验证登录续期"), None)
    if target is None:
        raise RuntimeError("未找到续期按钮。")
    parents = {child: parent for parent in root.iter() for child in parent}
    while target.get("clickable") != "true" and target in parents:
        target = parents[target]
    if target.get("enabled") != "true" or target.get("clickable") != "true":
        raise RuntimeError("续期按钮尚不可点击。")
    left, top, right, bottom = bounds(target)
    assert_foreground()
    adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
    scroll(nodes, False)
    while time.monotonic() < DEADLINE:
        _, updated = hierarchy()
        texts = [node.get("text") for node in updated if node.get("text")]
        if "登录续期与额度查询成功。" in texts:
            print("通过：手机登录续期后额度查询成功。")
            return
        if "暂时无法更新，请重试。" in texts:
            raise RuntimeError("续期或查询返回失败。")
        time.sleep(1)
    raise TimeoutError("未捕获明确的续期成功提示。")


def restart() -> None:
    assert_foreground()
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    while time.monotonic() < DEADLINE:
        _, nodes = hierarchy()
        texts = [node.get("text") for node in nodes if node.get("text")]
        if "已更新手机账户的额度。" in texts and any("手机独立查询" in text for text in texts):
            print("通过：结束应用进程后重新启动，登录保留且额度重新查询成功。")
            return
        if "请登录你的 ChatGPT 账户。" in texts:
            raise RuntimeError("重启后未保留登录。")
        if "暂时无法更新，请重试。" in texts:
            raise RuntimeError("重启后的查询失败。")
        time.sleep(1)
    raise TimeoutError("重启后查询未在时限内完成。")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("stage", choices=["renewal", "restart"])
    args = parser.parse_args()
    try:
        renew() if args.stage == "renewal" else restart()
    except (RuntimeError, TimeoutError, subprocess.SubprocessError) as error:
        raise SystemExit(str(error))
