"""Fail the build for a wrong or missing native ABI or missing offline runtime assets."""
import json
import os
import sys
import zipfile

ABIS = ('arm64-v8a', 'armeabi-v7a', 'x86_64')
path, expected = sys.argv[1], sys.argv[2]
assert expected in ABIS, f'Unknown ABI: {expected}'
with zipfile.ZipFile(path) as apk:
    names = set(apk.namelist())
    manifest = json.loads(apk.read('assets/web/android-build.json'))
    for asset in manifest['requiredAssets']:
        assert f'assets/web/{asset}' in names, f'Missing bundled engine: {asset}'
    assert f'lib/{expected}/libxul.so' in names, f'Missing GeckoView ABI: {expected}'
    # An ABI split that still carries a foreign ABI means the split silently fell
    # back to a universal APK, which is the size regression this guards against.
    for abi in ABIS:
        if abi != expected:
            assert not any(name.startswith(f'lib/{abi}/') for name in names), \
                f'{expected} APK also carries {abi} libraries'
    assert not any(name.startswith('assets/web/') and name.endswith('.gz') for name in names), 'Compressed web copies must not enter APK staging'
    assert 'classes.dex' in names
    assert not any('/android-smoke.' in name for name in names), 'Test harness leaked into release APK'
    libs = sum(info.file_size for info in apk.infolist() if info.filename.startswith('lib/'))
    assets = sum(info.file_size for info in apk.infolist() if info.filename.startswith('assets/'))
    print(f'Verified {expected}: {os.path.getsize(path) // 1048576} MiB APK, '
          f'{libs // 1048576} MiB native libraries, {assets // 1048576} MiB assets, '
          f'no foreign ABIs. Source: {manifest["sourceCommit"]}')
