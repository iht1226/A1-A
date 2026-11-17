"""Generate public update metadata from verified APKs; contains no private signing material."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess


def main():
    output = Path('build/update-release')
    output.mkdir(parents=True, exist_ok=True)
    build_tools = Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0'
    tag = f"apk-build{os.environ['APP_VERSION_CODE']}"
    assets = {}
    certs = []
    versions = []
    names = []
    for module in ('phone', 'wear'):
        source = Path(module) / f'build/outputs/apk/release/{module}-release.apk'
        data = source.read_bytes()
        apk = output / f'{module}-update.apk'
        apk.write_bytes(data)
        signed = subprocess.check_output([str(build_tools / 'apksigner'), 'verify', '--print-certs', str(apk)], text=True)
        certs.append(re.search(r'Signer #1 certificate SHA-256 digest: (\w+)', signed).group(1))
        badging = subprocess.check_output([str(build_tools / 'aapt'), 'dump', 'badging', str(apk)], text=True)
        package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
        assert package and package[1] == 'dev.sleepalarm.app'
        code = int(package[2]); versions.append(code); names.append(package[3])
        assert code == int(os.environ['APP_VERSION_CODE'])
        assert ("uses-feature: name='android.hardware.type.watch'" in badging) == (module == 'wear')
        assets[module] = dict(module=module, versionCode=code, bytes=len(data), sha256=hashlib.sha256(data).hexdigest(),
                              url=f'https://github.com/iht1226/A1-A/releases/download/{tag}/{apk.name}')
    assert len(set(certs)) == len(set(versions)) == len(set(names)) == 1, 'phone/watch signing or version mismatch'
    (output / 'update.json').write_text(json.dumps(dict(schema=1, version=names[0], **assets), indent=2) + '\n')
    print(f'Release {tag}: both APK signatures verified, certificate SHA-256 {certs[0]}')


if __name__ == '__main__':
    main()
