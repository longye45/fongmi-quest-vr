"""Keep 1K/2K textures, re-encode high-quality JPEGs for a smaller offline APK.

Run once after build_scenes.py. Requires Pillow. Geometry, UVs, PBR factors,
normal-map strength, and material/shader bindings are preserved.
"""
from pathlib import Path
import io
import json
import struct
from PIL import Image

ASSETS = Path(__file__).resolve().parents[2] / 'app/src/quest/assets/scenes'
ENCODING = {'jpegQuality': 90, 'chromaSubsampling': '4:4:4'}
manifest_path = ASSETS / 'manifest.json'
manifest = json.loads(manifest_path.read_text())
if manifest.get('textureEncoding') == ENCODING:
    print('Scene textures already optimized; no repeated lossy encoding.')
    raise SystemExit(0)

for report in manifest['scenes']:
    path = ASSETS / report['file']
    original = path.read_bytes()
    json_length, chunk = struct.unpack_from('<II', original, 12)
    assert chunk == 0x4E4F534A
    document = json.loads(original[20:20 + json_length])
    assert len(document['buffers']) == 1
    binary_length, binary_type = struct.unpack_from('<II', original, 20 + json_length)
    assert binary_type == 0x004E4942
    binary = original[28 + json_length:28 + json_length + binary_length]
    replacements = {}
    for image in document.get('images', []):
        if image.get('mimeType') != 'image/jpeg':
            continue
        index = image['bufferView']
        view = document['bufferViews'][index]
        raw = binary[view.get('byteOffset', 0):view.get('byteOffset', 0) + view['byteLength']]
        with Image.open(io.BytesIO(raw)) as source:
            encoded = io.BytesIO()
            source.convert('RGB').save(encoded, format='JPEG', quality=90, subsampling=0, optimize=True)
        if len(encoded.getvalue()) < len(raw):
            replacements[index] = encoded.getvalue()
    if replacements:
        output = bytearray()
        for index, view in enumerate(document['bufferViews']):
            assert view.get('buffer', 0) == 0
            raw = replacements.get(index, binary[view.get('byteOffset', 0):view.get('byteOffset', 0) + view['byteLength']])
            output.extend(b'\x00' * (-len(output) % 4))
            view['byteOffset'] = len(output)
            view['byteLength'] = len(raw)
            output.extend(raw)
        output.extend(b'\x00' * (-len(output) % 4))
        document['buffers'][0]['byteLength'] = len(output)
        json_bytes = json.dumps(document, separators=(',', ':')).encode()
        json_bytes += b' ' * (-len(json_bytes) % 4)
        total = 28 + len(json_bytes) + len(output)
        path.write_bytes(struct.pack('<III', 0x46546C67, 2, total) + struct.pack('<II', len(json_bytes), chunk) + json_bytes + struct.pack('<II', len(output), binary_type) + output)
    report['bytes'] = path.stat().st_size
    print(path.name, len(original), '->', report['bytes'])
manifest['textureEncoding'] = ENCODING
manifest_path.write_text(json.dumps(manifest, indent=2) + '\n')
