#!/usr/bin/env python3
"""Fetch the small, redistributable source texture set for the two authored rooms."""
import concurrent.futures
import hashlib
import json
from pathlib import Path
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parent
CACHE = ROOT / '.cache'
CACHE.mkdir(exist_ok=True)
UA = 'FongMiQuestCinema/0.2 (https://github.com/longye45/fongmi-quest-vr)'
MATERIALS = {
    'velour_velvet': ['diff', 'nor_gl', 'arm'],
    'dirty_carpet': ['diff', 'nor_gl', 'arm'],
    'metal_plate_02': ['diff', 'nor_gl', 'arm'],
    'dark_wood': ['diff', 'nor_gl', 'arm'],
}


def fetch(url, path):
    if not path.exists():
        with urlopen(Request(url, headers={'User-Agent': UA}), timeout=45) as response:
            data = response.read()
        path.write_bytes(data)
    return hashlib.sha256(path.read_bytes()).hexdigest()


def material(asset):
    meta_file = CACHE / f'{asset}.json'
    fetch(f'https://api.polyhaven.com/files/{asset}', meta_file)
    meta = json.loads(meta_file.read_text())
    entries = []
    for channel in MATERIALS[asset]:
        resolved = 'Diffuse' if channel == 'diff' and 'Diffuse' in meta else channel
        if resolved not in meta:
            raise RuntimeError(f'Missing texture channel: {asset}/{channel}, available: {list(meta)}')
        info = meta[resolved]['1k']['jpg']
        name = f'{asset}_{channel}.jpg'
        digest = fetch(info['url'], CACHE / name)
        entries.append({'file': name, 'url': info['url'], 'sha256': digest, 'channel': channel})
    return {'id': asset, 'license': 'CC0-1.0', 'source': f'https://polyhaven.com/a/{asset}', 'files': entries}


if __name__ == '__main__':
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        materials = list(pool.map(material, MATERIALS))
    earth_url = 'https://eoimages.gsfc.nasa.gov/images/imagerecords/57000/57730/land_ocean_ice_2048.png'
    earth_sha = fetch(earth_url, CACHE / 'earth.png')
    env_url = 'https://raw.githubusercontent.com/meta-quest/Meta-Spatial-SDK-Samples/f233e2327b95f9871b75bdba867d6fdd726f07cc/StarterSample/app/src/main/assets/environment.env'
    env_sha = fetch(env_url, CACHE / 'studio.env')
    manifest = {'materials': materials, 'earth': {'url': earth_url, 'sha256': earth_sha, 'credit': 'NASA Earth Observatory, Blue Marble'}, 'ibl': {'url': env_url, 'sha256': env_sha, 'license': 'MIT', 'credit': 'Meta Spatial SDK Samples'}}
    (ROOT / 'sources.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(f'Fetched {sum(len(m["files"]) for m in materials)} PBR maps, NASA Earth texture, and IBL map')
