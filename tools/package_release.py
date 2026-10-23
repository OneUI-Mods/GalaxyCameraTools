#!/usr/bin/env python3
"""Build, inspect, align and sign the release APK with a Git-excluded distribution key."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parent.parent


def run(*args, capture=False):
    return subprocess.run([str(a) for a in args], cwd=ROOT, check=True,
                          text=True, capture_output=capture)


def validate(apk, tools):
    badging = run(tools / 'aapt2', 'dump', 'badging', apk, capture=True).stdout
    if "name='dev.ryuya.galaxycamera'" not in badging or "targetSdkVersion:'37'" not in badging:
        raise RuntimeError('Unexpected application or target SDK')
    if 'application-debuggable' in badging or 'uses-permission: name=\'android.permission.INTERNET\'' in badging:
        raise RuntimeError('Unexpected debug flag or network permission')
    with zipfile.ZipFile(apk) as archive:
        meta = 'META-INF/xposed/'
        props = archive.read(meta + 'module.prop').decode()
        if 'minApiVersion=102' not in props or 'targetApiVersion=102' not in props:
            raise RuntimeError('Expected modern API 102 metadata')
        if archive.read(meta + 'java_init.list').decode().strip() != 'dev.ryuya.galaxycamera.HookEntry':
            raise RuntimeError('Unexpected module entry')
        scope = set(archive.read(meta + 'scope.list').decode().splitlines())
        if scope != {'com.sec.android.app.camera', 'com.samsung.android.app.cameraassistant', 'com.samsung.android.app.galaxyraw', 'com.android.systemui'}:
            raise RuntimeError('Unexpected module scope')
        if 'assets/xposed_init' in archive.namelist():
            raise RuntimeError('Legacy entry is not supported')
        for name in archive.namelist():
            if name.endswith('.dex'):
                data = archive.read(name)
                if b'Lde/robv/android/xposed/' in data or b'Ldev/ryuya/galaxycamera/fixtures/' in data:
                    raise RuntimeError('Legacy API or test fixture in release')
    manifest = run(tools / 'aapt2', 'dump', 'xmltree', apk, '--file', 'AndroidManifest.xml', capture=True).stdout
    if any(word in manifest.lower() for word in ['fixture', 'probeactivity', 'testactivity', 'instrumentation']):
        raise RuntimeError('Refusing a test-fixture APK')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-ref', default='HEAD')
    parser.add_argument('--check-release-apk', type=Path)
    args = parser.parse_args()
    sdk = Path(os.environ.get('ANDROID_HOME', Path.home() / 'Library/Android/sdk'))
    tools = sdk / 'build-tools/37.0.0'
    if args.check_release_apk:
        validate(args.check_release_apk.resolve(), tools)
        print('Release APK validation passed')
        return
    signing = ROOT / '.signing'
    if not all((signing / name).is_file() for name in ['release.keystore', 'password']):
        raise RuntimeError('Missing distribution signing material; see docs/releasing.md')
    run('git', 'diff', '--exit-code', args.source_ref, '--', 'app')
    if run('git', 'ls-files', '--others', '--exclude-standard', '--', 'app', capture=True).stdout:
        raise RuntimeError('Commit application sources before packaging')
    run(ROOT / 'gradlew', ':app:assembleRelease', ':app:writeRuntimeDependencyList')
    run(sys.executable, ROOT / 'tools/check_license_catalog.py')
    output = ROOT / 'app/build/outputs/apk/release'
    metadata = json.loads((output / 'output-metadata.json').read_text())
    if metadata['variantName'] != 'release' or len(metadata['elements']) != 1:
        raise RuntimeError('Expected exactly one release variant')
    item = metadata['elements'][0]
    unsigned = output / item['outputFile']
    validate(unsigned, tools)
    version = item['versionName']
    dist = ROOT / 'dist'
    dist.mkdir(exist_ok=True)
    aligned = output / 'aligned-release.apk'
    apk = dist / f'GalaxyCameraTools-{version}-release.apk'
    run(tools / 'zipalign', '-f', '-P', '16', '4', unsigned, aligned)
    run(tools / 'apksigner', 'sign', '--ks', signing / 'release.keystore',
        '--ks-key-alias', 'galaxycamera-release', '--ks-pass', 'file:' + str(signing / 'password'),
        '--out', apk, aligned)
    run(tools / 'zipalign', '-c', '-P', '16', '4', apk)
    verification = run(tools / 'apksigner', 'verify', '--verbose', '--print-certs', apk, capture=True).stdout
    (dist / f'GalaxyCameraTools-{version}-signature.txt').write_text(verification)
    validate(apk, tools)
    source = dist / f'GalaxyCameraTools-{version}-source.zip'
    run('git', 'archive', '--format=zip', '--prefix=GalaxyCameraTools-' + version + '/', '--output=' + str(source), args.source_ref)
    (dist / f'SHA256SUMS-{version}.txt').write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest() + '  ' + p.name + '\n' for p in [apk, source]))
    print('Signed and verified release:', apk.name)


if __name__ == '__main__':
    main()
