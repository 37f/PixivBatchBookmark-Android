"""Exercise the installed APK using Android's actual accessibility tree."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import shutil
import time
import xml.etree.ElementTree as ET

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    parser.add_argument('--adb', default=shutil.which('adb'), required=shutil.which('adb') is None)
    parser.add_argument('--package', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--baseline', action='store_true')
    args = parser.parse_args()
    out = Path(args.output)
    out.mkdir(parents=True, exist_ok=True)
    checks = []

    def adb(*cmd, binary=False):
        result = subprocess.run([args.adb, '-s', args.serial, *cmd], capture_output=True, timeout=45)
        if result.returncode:
            raise RuntimeError(result.stderr.decode('utf-8', errors='replace'))
        return result.stdout if binary else result.stdout.decode('utf-8', errors='replace')

    def tree():
        # Wait for accessibility to become idle before reading the actual UI.
        adb('shell', 'uiautomator', 'dump', '/sdcard/codex-wallpaper-ui.xml')
        raw = adb('shell', 'cat', '/sdcard/codex-wallpaper-ui.xml')
        return ET.fromstring(raw)

    def find(text, root=None):
        root = root if root is not None else tree()
        matches = [n for n in root.iter('node') if n.get('text') == text]
        assert matches, f'Missing visible control: {text}'
        return matches[0]

    def tap(text):
        node = find(text)
        left, top, right, bottom = map(int, re.findall(r'\d+', node.get('bounds')))
        adb('shell', 'input', 'tap', str((left + right) // 2), str((top + bottom) // 2))

    def screenshot(name):
        (out / name).write_bytes(adb('exec-out', 'screencap', '-p', binary=True))

    adb('shell', 'am', 'force-stop', args.package)
    adb('shell', 'am', 'start', '-W', '-n', args.package + '/com.openai.pixivbatchbookmark.MainActivity')
    root = tree()
    find('切换壁纸', root)
    checks.append('wallpaper selector is visible')
    if args.baseline:
        return
    screenshot('main-new.png')

    edit = next(n for n in root.iter('node') if n.get('class') == 'android.widget.EditText')
    left, top, right, bottom = map(int, re.findall(r'\d+', edit.get('bounds')))
    # The input may extend behind the persistent bottom controls; focus its visible top.
    adb('shell', 'input', 'tap', str((left + right) // 2), str(min(top + 16, bottom - 1)))
    assert any(n.get('class') == 'android.widget.EditText' and n.get('focused') == 'true' for n in tree().iter('node'))
    adb('shell', 'input', 'text', '97003966')
    # Hardware keyboards may not open an IME; Back is safe only while an IME is shown.
    if 'mInputShown=true' in adb('shell', 'dumpsys', 'input_method'):
        adb('shell', 'input', 'keyevent', '4')

    tap('看看兽娘麻麻˃ 𖥦 ˂')
    root = tree()
    find('再见兽娘麻麻⊙﹏⊙', root)
    find('切换壁纸', root)
    assert not any(n.get('class') == 'android.widget.EditText' for n in root.iter('node'))
    checks.append('wallpaper mode hides tool interface and retains controls')
    screenshot('wallpaper-new.png')
    tap('切换壁纸')
    find('新壁纸（粉色兔耳）')
    tap('原壁纸（秋叶）')
    screenshot('wallpaper-original.png')
    assert (out / 'wallpaper-new.png').read_bytes() != (out / 'wallpaper-original.png').read_bytes()
    checks.append('both wallpapers can be selected in wallpaper mode')
    tap('再见兽娘麻麻⊙﹏⊙')
    root = tree()
    assert any(n.get('class') == 'android.widget.EditText' and n.get('text') == '97003966' for n in root.iter('node'))
    checks.append('returning to tools preserves entered ID')

    tap('切换壁纸')
    root = tree()
    assert find('原壁纸（秋叶）', root).get('checked') == 'true'
    adb('shell', 'input', 'keyevent', '4')
    adb('shell', 'am', 'force-stop', args.package)
    adb('shell', 'am', 'start', '-W', '-n', args.package + '/com.openai.pixivbatchbookmark.MainActivity')
    tap('切换壁纸')
    root = tree()
    assert find('原壁纸（秋叶）', root).get('checked') == 'true'
    checks.append('wallpaper selection survives process restart')
    tap('新壁纸（粉色兔耳）')
    tap('看看兽娘麻麻˃ 𖥦 ˂')
    screenshot('wallpaper-new-final.png')

    packages = adb('shell', 'pm', 'list', 'packages').splitlines()
    assert 'package:com.openai.pixivbatchbookmark' in packages
    assert 'package:com.openai.pixivbatchbookmark.wallpaper' in packages
    checks.append('original and independent wallpaper edition remain installed together')
    result = {'package': args.package, 'checks': checks, 'count': len(checks)}
    (out / 'ui-results.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False, indent=2))

if __name__ == '__main__':
    main()
