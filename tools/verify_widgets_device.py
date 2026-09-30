#!/usr/bin/env python3
"""验证本应用桌面组件；仅输出额度缓存的成功状态，不访问认证文件。"""
import subprocess
import os
import shutil
import time
import re
import xml.etree.ElementTree as ET
from pathlib import Path
# 使用显式 ADB / PATH / Android SDK，不依赖开发者本机目录。
_sdk_root = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
_adb_path = os.environ.get("ADB") or shutil.which("adb") or (str(Path(_sdk_root) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")) if _sdk_root else None)
if not _adb_path:
    raise SystemExit("未找到 adb，请设置 ADB 或 ANDROID_HOME，或者加入 PATH。")
ADB = Path(_adb_path)
DEADLINE=time.monotonic()+55

def adb(*args):
    remaining=DEADLINE-time.monotonic()
    if remaining<=0: raise TimeoutError('组件检查达到55秒限制。')
    return subprocess.run([str(ADB),*args],check=True,capture_output=True,text=True,timeout=min(12,remaining)).stdout

def foreground():
    state=adb('shell','dumpsys','window')
    if 'mDreamingLockscreen=true' in state: raise RuntimeError('手机已锁屏，停止操作。')
    return next((line for line in state.splitlines() if 'mCurrentFocus=' in line),'')

def cache():
    root=ET.fromstring(adb('shell','run-as','cn.luxy.codexquota','cat','shared_prefs/usage_snapshot.xml'))
    entries={n.get('name'):n for n in root}
    return int(entries['fetchedAt'].get('value','0')),entries.get('failed').get('value')=='true'

try:
    if 'com.android.launcher' not in foreground(): raise RuntimeError('请停留在已添加小组件的桌面。')
    adb('shell','uiautomator','dump','/data/local/tmp/codex-widget-ui.xml')
    root=ET.fromstring(adb('exec-out','cat','/data/local/tmp/codex-widget-ui.xml'))
    refresh=next(n for n in root.iter('node') if n.get('resource-id')=='cn.luxy.codexquota:id/widget_refresh')
    before,_=cache()
    left,top,right,bottom=map(int,re.findall(r'\d+',refresh.get('bounds')))
    if 'com.android.launcher' not in foreground(): raise RuntimeError('桌面已切换，停止操作。')
    adb('shell','input','tap',str((left+right)//2),str((top+bottom)//2))
    while time.monotonic()<DEADLINE:
        current,failed=cache()
        if current>before and not failed:
            if 'com.android.launcher' not in foreground(): raise RuntimeError('检查期间已离开桌面。')
            print('通过：点击小组件刷新，后台额度查询成功，桌面保持前台。')
            card=next(n for n in root.iter('node') if n.get('resource-id')=='cn.luxy.codexquota:id/widget_root')
            left,top,right,bottom=map(int,re.findall(r'\d+',card.get('bounds')))
            adb('shell','input','tap',str((left+right)//2),str((top+bottom)//2))
            time.sleep(1)
            if 'cn.luxy.codexquota' not in foreground(): raise RuntimeError('卡片点击未打开额度APP。')
            print('通过：点击小组件卡片打开额度APP。')
            break
        time.sleep(1)
    else: raise TimeoutError('后台刷新未在55秒内完成。')
except (RuntimeError,TimeoutError,subprocess.SubprocessError,StopIteration) as error:
    raise SystemExit(str(error) or '未找到本应用组件控件。')
