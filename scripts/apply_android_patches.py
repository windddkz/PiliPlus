#!/usr/bin/env python3
"""Replay the audited Android patch list without global Git/reset/cache deletion."""
from pathlib import Path
import argparse
import os
import re
import subprocess

source = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--toolchain-root', type=Path, required=True,
                    help='Dedicated directory containing FLUTTER_ROOT and PUB_CACHE')
args = parser.parse_args()
root = args.toolchain_root.resolve()
script = (source / 'lib/scripts/patch.ps1').read_text()
variables = dict(re.findall(r'^\$(\w+)\s*=\s*"(lib/scripts/[^"]+\.patch)"', script, re.M))

def patch_list(name):
    initial = re.search(r'\$' + name + r'\s*=\s*@\((.*?)\)', script, re.S).group(1)
    section = script[script.index('switch ($platform.ToLower())', script.index(initial)):]
    android = re.search(r'"android"\s*\{(.*?)\}', section, re.S).group(1)
    return [variables[n] for n in re.findall(r'\$(\w+)', initial)] + [
        variables[n] for n in re.findall(r'\$' + name + r'\s*\+=\s*\$(\w+)', android)]

def apply(directory, patches):
    directory = directory.resolve()
    assert directory.is_relative_to(root), directory
    for relative in patches:
        patch = source / relative
        command = ['git', '-c', f'safe.directory={directory}', 'apply']
        if subprocess.run(command + ['--reverse', '--check', str(patch)], cwd=directory,
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0:
            print('Already applied:', relative, flush=True)
            continue
        subprocess.run(command + ['--check', str(patch)], cwd=directory, check=True)
        subprocess.run(command + [str(patch)], cwd=directory, check=True)
        print('Applied:', relative, flush=True)

sdk_patches, material_patches = patch_list('patches'), patch_list('patches_material')
assert len(sdk_patches) == 25 and len(material_patches) == 9
assert sdk_patches[-3:] == ['lib/scripts/bottom_sheet_android.patch', 'lib/scripts/scroll_view.patch', 'lib/scripts/navigator.patch']
if __name__ == '__main__':
    apply(Path(os.environ['FLUTTER_ROOT']), sdk_patches)
    subprocess.run(['flutter', 'pub', 'get', '--enforce-lockfile'], cwd=source, check=True)
    lock = (source / 'pubspec.lock').read_text()
    version = re.search(r'\n  material_ui:\n.*?\n    version: "([^"]+)"', lock, re.S).group(1)
    apply(Path(os.environ['PUB_CACHE']) / 'hosted/pub.dev' / ('material_ui-' + version), material_patches)
