// npm install --prefix tools/scenes && node tools/scenes/validate.cjs
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const validator = require('gltf-validator');
const dir = path.resolve(__dirname, '../../app/src/quest/assets/scenes');
(async () => {
  for (const name of ['private_cinema', 'orbital_lounge', 'screen_frame']) {
    const file = name + '.glb';
    const bytes = fs.readFileSync(path.join(dir, file));
    const doc = JSON.parse(bytes.toString('utf8', 20, 20 + bytes.readUInt32LE(12)));
    assert(doc.images.every(image => Number.isInteger(image.bufferView)), 'All textures must be embedded');
    assert(!doc.buffers.some(buffer => buffer.uri), 'No network buffer dependencies');
    if (name === 'orbital_lounge') {
      const earth = doc.materials.find(m => m.name === 'NASA Blue Marble Earth');
      assert(earth.pbrMetallicRoughness.baseColorTexture, 'Unlit Earth needs a base-color texture');
    }
    const report = await validator.validateBytes(new Uint8Array(bytes), {uri: file, maxIssues: 100});
    console.log(`${file}: ${report.issues.numErrors} errors, ${report.issues.numWarnings} warnings, ${report.issues.numInfos} info`);
    assert.equal(report.issues.numErrors, 0);
    assert.equal(report.issues.numWarnings, 0);
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
