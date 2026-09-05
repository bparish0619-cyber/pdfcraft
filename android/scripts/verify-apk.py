"""Fail the build for missing native ABIs or essential offline runtime assets."""
import json
import sys
import zipfile
with zipfile.ZipFile(sys.argv[1]) as apk:
    names = set(apk.namelist())
    manifest = json.loads(apk.read('assets/web/android-build.json'))
    for asset in manifest['requiredAssets']:
        assert f'assets/web/{asset}' in names, f'Missing bundled engine: {asset}'
    for abi in ('arm64-v8a', 'armeabi-v7a', 'x86_64'):
        assert f'lib/{abi}/libxul.so' in names, f'Missing GeckoView ABI: {abi}'
    assert 'classes.dex' in names
    assert not any('/android-smoke.' in name for name in names), 'Test harness leaked into release APK'
    print('Verified bundled engines and native ABIs. Source:', manifest['sourceCommit'])
