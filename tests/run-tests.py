"""Run the existing native and JavaScript regressions on Windows, Linux or macOS."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--java-root', default=os.environ.get('JAVA_HOME'))
    parser.add_argument('--node', default=shutil.which('node'))
    args = parser.parse_args()
    if not args.node:
        parser.error('Provide a Node.js executable.')
    suffix = '.exe' if os.name == 'nt' else ''
    java = str(Path(args.java_root) / 'bin' / ('java' + suffix)) if args.java_root else shutil.which('java')
    javac = str(Path(args.java_root) / 'bin' / ('javac' + suffix)) if args.java_root else shutil.which('javac')
    if not java:
        parser.error('Provide --java-root/JAVA_HOME or java on PATH.')
    compiler = [javac] if javac and Path(javac).is_file() else [java, '-m', 'jdk.compiler/com.sun.tools.javac.Main']
    source = ROOT / 'app/src/main/java/com/openai/pixivbatchbookmark'
    with tempfile.TemporaryDirectory(prefix='pixiv-tests-') as work:
        subprocess.run(compiler + ['--release', '8', '-encoding', 'UTF-8', '-d', work,
                        str(source / 'PixivIdParser.java'), str(source / 'BatchGate.java'), str(source / 'RecoveryStore.java'),
                        str(ROOT / 'tests/TestParser.java'), str(ROOT / 'tests/TestBatchGate.java'), str(ROOT / 'tests/TestRecovery.java'), str(ROOT / 'tests/TestOwnership.java')], check=True)
        for test in ('TestParser', 'TestBatchGate', 'TestRecovery', 'TestOwnership'):
            subprocess.run([str(java), '-cp', work, test], check=True)
        subprocess.run([args.node, str(ROOT / 'tests/test-bookmark.cjs')], check=True)
        subprocess.run([args.node, str(ROOT / 'tests/test-queue.cjs')], check=True)

if __name__ == '__main__':
    main()
