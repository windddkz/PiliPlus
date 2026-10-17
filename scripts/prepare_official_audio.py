#!/usr/bin/env python3
"""Prepare local research assets from the locked official APK; no network or JNI merging."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import os
import zipfile

repo = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser()
p.add_argument('--workspace', type=Path, default=repo.parent)
p.add_argument('--fixtures', action='store_true')
p.add_argument('--apk', type=Path, help='Exact local APK locked by the runtime manifest')
p.add_argument('--sdk', type=Path, help='Android SDK root')
p.add_argument('--ndk', type=Path, help='NDK root (tested: 28.2.13676358, Linux x86_64 host)')
p.add_argument('--java-home', type=Path, help='JDK 17 root')
p.add_argument('--ffmpeg-headers', type=Path, help='Configured FFmpeg 7.1.1 source directory')
p.add_argument('--fixture-dir', type=Path, help='Locally generated validation samples')
p.add_argument('--build-tools', default='37.0.0', help='Android build-tools directory name')
a = p.parse_args()
manifest_path = repo / 'android/official-runtime-manifest.json'
manifest = json.loads(manifest_path.read_text())
toolchain = a.workspace / 'execution/toolchain'
sdk = a.sdk or toolchain / 'android-sdk'
ndk = a.ndk or sdk / 'ndk/28.2.13676358'
java_home = a.java_home or toolchain / 'jdk-17.0.20.1+1'
include = a.ffmpeg_headers or toolchain / 'ffmpeg-source/ffmpeg-7.1.1'
zipalign = sdk / 'build-tools' / a.build_tools / 'zipalign'
d8 = sdk / 'build-tools' / a.build_tools / 'd8'
clang = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang'
javac = java_home / 'bin/javac'
apk_path = a.apk or a.workspace / manifest['apk_path']
for required in [apk_path, zipalign, d8, clang, javac, include / 'libavutil/avconfig.h']:
    if not required.is_file():
        p.error('Required local input not found: ' + str(required))
assert hashlib.sha256(apk_path.read_bytes()).hexdigest() == manifest['apk_sha256'], 'Wrong official sample'
destination = repo / 'android/app/src/officialAudio/assets/official_runtime'
destination.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(apk_path) as apk:
    assert len(manifest['files']) == 10
    for name, digest in manifest['files'].items():
        assert '/' not in name
        data = apk_path.read_bytes() if name == 'official.apk' else apk.read('lib/arm64-v8a/' + name)
        assert hashlib.sha256(data).hexdigest() == digest, name
        (destination / name).write_bytes(data)
# ART otherwise inflates all 33 original DEX files on every cold class-loader creation.
unaligned = destination / 'code-unaligned.apk'
try:
    with zipfile.ZipFile(apk_path) as original, zipfile.ZipFile(unaligned, 'w', allowZip64=True) as output:
        for entry in original.infolist():
            contents = original.read(entry.filename)
            if entry.filename.endswith('.dex'):
                entry.compress_type = zipfile.ZIP_STORED
            output.writestr(entry, contents)
    subprocess.run([str(zipalign),
                    '-f', '4', str(unaligned), str(destination / 'official.apk')], check=True)
    with zipfile.ZipFile(apk_path) as original, zipfile.ZipFile(destination / 'official.apk') as output:
        assert original.namelist() == output.namelist(), 'Runtime archive entries changed'
        assert all(original.read(name) == output.read(name) for name in original.namelist()), 'Runtime code/resources changed'
finally:
    unaligned.unlink(missing_ok=True)
manifest['files'].pop('official.apk')
manifest['code'] = {'official.apk': hashlib.sha256((destination / 'official.apk').read_bytes()).hexdigest()}
# Native helpers are private runtime assets, never merged with the mpv JNI namespace.
source = repo / 'android/official_audio'
build = repo / 'build/official_audio'
build.mkdir(parents=True, exist_ok=True)
for name, inputs, libs in [('libpiliplus_audio_graph.so', ['graph_adapter.c','graph_sret.S','media_index.c','media_metadata.c','first_seek.c','media_dash.c','media_switch.c','media_adaptive.c','startup_choice.c','network_read.c','tls_crypto.c'], ['-ldl','-lm','-llog']), ('libpiliplus_audio_dsp.so',['dsp_adapter.c'],['-ldl'])]:
    subprocess.run([str(clang),'-shared','-fPIC','-O2','-Wall','-Wextra','-Wunreachable-code','-Werror','-I',str(include),*[str(source/n) for n in inputs],*libs,'-o',str(build/name)],check=True)
classes=build/'classes';dex=build/'dex';classes.mkdir(exist_ok=True);dex.mkdir(exist_ok=True)
subprocess.run([str(javac),'-source','17','-target','17','-d',str(classes),str(source/'helper/dev/piliplus/dspbridge/DspBridge.java')],check=True)
env=dict(os.environ,JAVA_HOME=str(javac.parents[1]))
subprocess.run([str(d8),'--min-api','26','--output',str(dex),str(classes/'dev/piliplus/dspbridge/DspBridge.class')],env=env,check=True)
manifest['owned']={}
for name, source_file in [('audio-bridge-v1.dex',dex/'classes.dex'),('libpiliplus_audio_graph.so',build/'libpiliplus_audio_graph.so'),('libpiliplus_audio_dsp.so',build/'libpiliplus_audio_dsp.so')]:
    shutil.copy2(source_file,destination/name)
    manifest['owned'][name]=hashlib.sha256((destination/name).read_bytes()).hexdigest()
# Remove only obsolete, generated original-library assets with verified old hashes.
previous=destination/'runtime-manifest.json'
if previous.exists():
    previous_manifest=json.loads(previous.read_text())
    for group in ['files','owned']:
        for name,digest in previous_manifest.get(group,{}).items():
            if name not in manifest['files'] and name not in manifest['owned'] and name not in manifest['code']:
                obsolete=destination/name
                if obsolete.exists():
                    assert hashlib.sha256(obsolete.read_bytes()).hexdigest()==digest, 'Unexpected generated asset: '+name
                    obsolete.unlink()
(destination/'runtime-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
assert {p.name for p in destination.iterdir()} == {*manifest['files'],*manifest['owned'],*manifest['code'],'runtime-manifest.json'}
if a.fixtures:
    source = a.fixture_dir or a.workspace / 'native-audio-poc/app/src/main/assets/samples'
    target = repo / 'android/app/src/debug/assets/official_fixture'
    target.mkdir(parents=True, exist_ok=True)
    files = ['endpoint-eac3.mp4', 'endpoint-flac.mp4', 'endpoint-aac.mp4', 'stereo-44100-16.flac', 'stereo-96000-24.flac', 'surround-soft.flac', 'stereo-48000-16.flac', 'dash-muxed.mp4', 'fragmented-dash-video.mp4',
             'fragmented-dash-audio.m4a', 'dash-video.mp4', 'dash-audio.m4a', 'surround-soft.eac3', 'surround-quiet.eac3', 'loudness-stereo.m4a', 'loudness-manifest.json', 'loudness-fragmented-audio.m4a', 'frame-id.mp4', 'he-aac-v2-48000.m4a', 'he-aac-v2-44100.m4a', 'he-aac-v2-48000-implicit.aac', 'he-aac-v2-manifest.json']
    hashes = {}
    for name in files:
        shutil.copy2(source / name, target / name)
        hashes[name] = hashlib.sha256((target / name).read_bytes()).hexdigest()
    (target / 'manifest.json').write_text(json.dumps(hashes, indent=2) + '\n')
print('Original runtime hashes verified; assets isolated from packaged mpv JNI libraries')
