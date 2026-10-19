#!/usr/bin/env python3
"""Fetch the pinned, public FongMi Media3 sources without large upstream test data."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / '.deps/media3'
REPOSITORY = 'https://github.com/FongMi/media.git'
COMMIT = '3c2cbe8ac742c2fe15eff52f03eeb3b1b648848d'
BRANCH = 'release-1.11.0-fongmi'
PATTERNS = ['/*', '!/*/', '/gradle/', '/libraries/*/build.gradle.kts',
            '/libraries/*/proguard*', '/libraries/*/consumer*',
            '/libraries/*/src/main/', '!/libraries/test_data/']

def git(*args):
    subprocess.run(['git', '-C', str(DEST), *args], check=True)

if not (DEST / '.git').exists():
    DEST.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(['git', 'clone', '--filter=blob:none', '--no-checkout', '--depth', '1',
                    '--branch', BRANCH, REPOSITORY, str(DEST)], check=True)
head = subprocess.check_output(['git', '-C', str(DEST), 'rev-parse', 'HEAD'], text=True).strip()
if head != COMMIT:
    git('fetch', '--depth', '1', 'origin', COMMIT)
git('sparse-checkout', 'set', '--no-cone', *PATTERNS)
git('checkout', '--detach', COMMIT)
print('FongMi Media3 pinned source ready:', COMMIT)
