"""Build with official Android tools; signing passwords are read from the environment."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--sdk-root', default=os.environ.get('ANDROID_SDK_ROOT'))
    parser.add_argument('--java-root', default=os.environ.get('JAVA_HOME'))
    parser.add_argument('--build-tools', default='35.0.0')
    parser.add_argument('--platform', default='android-35')
    parser.add_argument('--android-jar', type=Path)
    parser.add_argument('--keystore', type=Path, required=True)
    parser.add_argument('--key-alias', default='androiddebugkey')
    parser.add_argument('--output', type=Path, default=ROOT / 'dist' / 'PixivBatchBookmark_Android_v0.1.7_fast_queue.apk')
    args = parser.parse_args()
    if not args.sdk_root or not args.java_root:
        parser.error('Provide --sdk-root and --java-root, or set ANDROID_SDK_ROOT and JAVA_HOME.')
    if not os.environ.get('KEYSTORE_PASSWORD'):
        parser.error('Set KEYSTORE_PASSWORD in the environment; do not put passwords on the command line.')
    sdk = Path(args.sdk_root).resolve()
    jdk = Path(args.java_root).resolve()
    bt = sdk / 'build-tools' / args.build_tools
    android_jar = (args.android_jar or sdk / 'platforms' / args.platform / 'android.jar').resolve()
    suffix = '.exe' if os.name == 'nt' else ''
    java = jdk / 'bin' / ('java' + suffix)
    javac = jdk / 'bin' / ('javac' + suffix)
    jar = jdk / 'bin' / ('jar' + suffix)
    aapt = bt / ('aapt2' + suffix)
    zipalign = bt / ('zipalign' + suffix)
    for path in (java, javac, jar, aapt, zipalign, android_jar, bt / 'lib' / 'd8.jar', bt / 'lib' / 'apksigner.jar', args.keystore):
        if not path.is_file():
            parser.error('Missing build component: ' + str(path))
    if args.output.exists():
        parser.error('Output already exists; choose another path: ' + str(args.output))
    config = (ROOT / 'app' / 'build.gradle').read_text(encoding='utf-8')
    def setting(key):
        match = re.search(r'\b' + key + r"\s+['\"]?([^'\"\s]+)", config)
        if not match:
            raise ValueError('Missing Gradle setting: ' + key)
        return match.group(1)
    app_id, namespace = setting('applicationId'), setting('namespace')
    min_sdk, target_sdk = setting('minSdk'), setting('targetSdk')
    version_code, version_name = setting('versionCode'), setting('versionName')
    source = ROOT / 'app' / 'src' / 'main'
    build_root = ROOT / 'build'
    build_root.mkdir(exist_ok=True)
    # Each build uses a fresh directory; existing source and outputs are preserved.
    work = Path(tempfile.mkdtemp(prefix='manual-', dir=build_root))
    generated, classes, dex = (work / name for name in ('generated', 'classes', 'dex'))
    for path in (generated, classes, dex):
        path.mkdir()
    def run(*command):
        subprocess.run([str(x) for x in command], check=True, cwd=ROOT)
    manifest = ET.parse(source / 'AndroidManifest.xml')
    manifest.getroot().set('package', app_id)
    ET.register_namespace('android', 'http://schemas.android.com/apk/res/android')
    manifest.write(work / 'AndroidManifest.xml', encoding='utf-8', xml_declaration=True)
    run(aapt, 'compile', '--dir', source / 'res', '-o', work / 'resources.zip')
    run(aapt, 'link', '-o', work / 'unsigned.apk', '-I', android_jar,
        '--manifest', work / 'AndroidManifest.xml', '--custom-package', namespace,
        '--min-sdk-version', min_sdk, '--target-sdk-version', target_sdk,
        '--version-code', version_code, '--version-name', version_name,
        '--java', generated, '--auto-add-overlay', '-R', work / 'resources.zip', '-A', source / 'assets')
    java_sources = sorted((source / 'java').rglob('*.java')) + sorted(generated.rglob('*.java'))
    # Java 8's lambda metafactory comes from the JDK; Android APIs remain on the classpath.
    run(javac, '-encoding', 'UTF-8', '--release', '8',
        '-classpath', android_jar, '-d', classes, *java_sources)
    run(jar, 'cf', work / 'classes.jar', '-C', classes, '.')
    run(java, '-cp', bt / 'lib' / 'd8.jar', 'com.android.tools.r8.D8',
        '--lib', android_jar, '--min-api', min_sdk, '--output', dex, work / 'classes.jar')
    with zipfile.ZipFile(work / 'unsigned.apk', 'a') as apk:
        for path in sorted(dex.glob('*.dex')):
            apk.write(path, path.name, compress_type=zipfile.ZIP_STORED)
    run(zipalign, '-f', '-p', '4', work / 'unsigned.apk', work / 'aligned.apk')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    run(java, '-jar', bt / 'lib' / 'apksigner.jar', 'sign', '--ks', args.keystore.resolve(),
        '--ks-key-alias', args.key_alias, '--ks-pass', 'env:KEYSTORE_PASSWORD',
        '--key-pass', 'env:KEY_PASSWORD' if os.environ.get('KEY_PASSWORD') else 'env:KEYSTORE_PASSWORD',
        '--out', args.output.resolve(), work / 'aligned.apk')
    run(java, '-jar', bt / 'lib' / 'apksigner.jar', 'verify', '--verbose', '--print-certs', args.output.resolve())
    run(zipalign, '-c', '-p', '4', args.output.resolve())
    print('APK: ' + str(args.output.resolve()))

if __name__ == '__main__':
    main()
