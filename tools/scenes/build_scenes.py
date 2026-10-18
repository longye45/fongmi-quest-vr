"""Author the real 3D rooms and embedded PBR GLBs. Run with Blender 4.3+ in background.

blender -b --python tools/scenes/build_scenes.py -- [--preview]
All geometry is authored here in meter-based Quest coordinates (+Y up, +Z forward).
"""
import bpy
import json
import math
import random
import shutil
import struct
import sys
from pathlib import Path
from mathutils import Vector

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[1]
CACHE = ROOT / '.cache'
ASSETS = REPO / 'app/src/quest/assets/scenes'
PREVIEWS = ROOT / 'output'
ASSETS.mkdir(parents=True, exist_ok=True)
PREVIEWS.mkdir(exist_ok=True)
TINTS = {}


def pos(p):
    return (p[0], -p[2], p[1])


def reset():
    bpy.ops.object.select_all(action='SELECT')
    bpy.ops.object.delete(use_global=False)
    for collection in [bpy.data.materials, bpy.data.meshes, bpy.data.curves]:
        for block in list(collection):
            if block.users == 0:
                collection.remove(block)


def material(name, texture=None, tint=(1, 1, 1, 1), rough=.6, metal=0, emit=0):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    m.diffuse_color = tint
    bs = m.node_tree.nodes.get('Principled BSDF')
    bs.inputs['Base Color'].default_value = tint
    bs.inputs['Roughness'].default_value = rough
    bs.inputs['Metallic'].default_value = metal
    if texture:
        channel = 'diff'
        nodes, links = m.node_tree.nodes, m.node_tree.links
        image = nodes.new('ShaderNodeTexImage')
        image.image = bpy.data.images.load(str(CACHE / f'{texture}_{channel}.jpg'), check_existing=True)
        # The exporter takes the texture; the GLB factor is explicitly pinned below.
        multiply = nodes.new('ShaderNodeMixRGB')
        multiply.blend_type = 'MULTIPLY'
        multiply.inputs[0].default_value = 1
        multiply.inputs[2].default_value = tint
        links.new(image.outputs['Color'], multiply.inputs[1])
        links.new(multiply.outputs[0], bs.inputs['Base Color'])
        normal = nodes.new('ShaderNodeTexImage')
        normal.image = bpy.data.images.load(str(CACHE / f'{texture}_nor_gl.jpg'), check_existing=True)
        normal.image.colorspace_settings.name = 'Non-Color'
        normal_map = nodes.new('ShaderNodeNormalMap')
        normal_map.inputs['Strength'].default_value = .55
        links.new(normal.outputs['Color'], normal_map.inputs['Color'])
        links.new(normal_map.outputs['Normal'], bs.inputs['Normal'])
        orm = nodes.new('ShaderNodeTexImage')
        orm.image = bpy.data.images.load(str(CACHE / f'{texture}_arm.jpg'), check_existing=True)
        orm.image.colorspace_settings.name = 'Non-Color'
        separate = nodes.new('ShaderNodeSeparateColor')
        links.new(orm.outputs['Color'], separate.inputs['Color'])
        links.new(separate.outputs['Green'], bs.inputs['Roughness'])
        if metal:
            links.new(separate.outputs['Blue'], bs.inputs['Metallic'])
        group = bpy.data.node_groups.get('glTF Material Output')
        if group is None:
            group = bpy.data.node_groups.new('glTF Material Output', 'ShaderNodeTree')
            group.interface.new_socket(name='Occlusion', in_out='INPUT', socket_type='NodeSocketFloat')
        output = nodes.new('ShaderNodeGroup')
        output.node_tree = group
        links.new(separate.outputs['Red'], output.inputs['Occlusion'])
        TINTS[name] = list(tint)
    if emit:
        bs.inputs['Emission Color'].default_value = tint
        bs.inputs['Emission Strength'].default_value = emit
    return m


def uv_world(obj, density=1):
    mesh = obj.data
    uv = mesh.uv_layers.active or mesh.uv_layers.new()
    for poly in mesh.polygons:
        axis = max(range(3), key=lambda i: abs(poly.normal[i]))
        axes = [(1, 2), (0, 2), (0, 1)][axis]
        for loop in poly.loop_indices:
            v = mesh.vertices[mesh.loops[loop].vertex_index].co
            uv.data[loop].uv = (v[axes[0]] * density, v[axes[1]] * density)


def box(name, p, size, mat, bevel=.035, density=1):
    bpy.ops.mesh.primitive_cube_add(size=1, location=pos(p))
    obj = bpy.context.object
    obj.name = name
    obj.dimensions = (size[0], size[2], size[1])
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    uv_world(obj, density)
    obj.data.materials.append(mat)
    if bevel:
        mod = obj.modifiers.new('Soft manufactured edges', 'BEVEL')
        mod.width = min(bevel, min(size) * .4)
        mod.segments = 2
        obj.modifiers.new('Weighted edge normals', 'WEIGHTED_NORMAL')
    return obj


def cylinder(name, p, radius, depth, mat, vertical=True, segments=32):
    bpy.ops.mesh.primitive_cylinder_add(vertices=segments, radius=radius, depth=depth, location=pos(p))
    obj = bpy.context.object
    obj.name = name
    if not vertical:
        obj.rotation_euler.x = math.pi / 2
    obj.data.materials.append(mat)
    bevel = obj.modifiers.new('Rounded edge', 'BEVEL')
    bevel.width = min(.025, depth * .2)
    bevel.segments = 1
    obj.modifiers.new('Normals', 'WEIGHTED_NORMAL')
    return obj


def seat(x, z, height, upholstery, shell, trim):
    box('Seat pedestal', (x, height + .23, z), (.55, .45, .65), shell, .08)
    box('Upholstered seat cushion', (x, height + .61, z + .08), (.94, .25, .91), upholstery, .12, 2.5)
    box('Upholstered backrest', (x, height + 1.12, z - .43), (1.0, 1.13, .26), upholstery, .12, 2.5)
    box('Headrest', (x, height + 1.54, z - .37), (.75, .25, .20), upholstery, .10, 2.5)
    for side in [-1, 1]:
        box('Arm support', (x + side * .59, height + .56, z), (.20, .61, 1.04), shell, .07)
        box('Padded armrest', (x + side * .59, height + .91, z), (.24, .15, 1.10), upholstery, .065, 2)
        cylinder('Cupholder rim', (x + side * .59, height + 1.0, z + .29), .075, .018, trim, segments=16)
        cylinder('Cupholder recess', (x + side * .59, height + 1.012, z + .29), .057, .016, shell, segments=16)
    # Subtle vertical seams give the cushions scale and depth.
    for dx in [-.29, .29]:
        box('Backrest seam', (x + dx, height + 1.08, z - .292), (.012, .65, .008), shell, .003)


def theatre():
    velvet = material('Burgundy Velvet', 'velour_velvet', (.50, .035, .055, 1))
    wall = material('Charcoal Acoustic Fabric', 'velour_velvet', (.085, .08, .09, 1))
    carpet = material('Charcoal Carpet', 'dirty_carpet', (.20, .16, .18, 1))
    wood = material('Walnut Wainscot', 'dark_wood', (.50, .32, .20, 1))
    black = material('Seat Black Shell', rough=.38)
    black.node_tree.nodes.get('Principled BSDF').inputs['Base Color'].default_value = (.018, .022, .027, 1)
    brass = material('Brushed Brass', 'metal_plate_02', (.58, .34, .11, 1), metal=.8)
    warm = material('Fixture Warm Guide Light', tint=(1, .39, .10, 1), emit=2)
    box('Carpet floor', (0, -.14, 2.5), (18.6, .28, 25.5), carpet, 0, 1.5)
    box('Ceiling', (0, 7.6, 2.5), (18.6, .24, 25.5), wall, .04, .5)
    box('Front acoustic wall', (0, 3.8, 15.2), (18.6, 7.6, .25), wall, .02, 1)
    box('Rear acoustic wall', (0, 3.8, -10.1), (18.6, 7.6, .25), wall, .02, 1)
    box('Projection stage', (0, .20, 13.7), (14, .4, 2.5), wood, .06, .6)
    for side in [-1, 1]:
        x = side * 9.15
        box('Side wall', (x, 3.8, 2.5), (.25, 7.6, 25.5), wall, .02, 1)
        box('Walnut lower wall', (side * 8.97, .65, 2.5), (.15, 1.3, 25.2), wood, .02, .65)
        box('Brass chair rail', (side * 8.85, 1.35, 2.5), (.045, .06, 25.2), brass, .012)
        for z in [-8, -4, 0, 4, 8, 12]:
            box('Vertical brass divider', (side * 8.91, 4.4, z - 1.9), (.06, 5.8, .07), brass, .015)
            for y in [2.6, 5.2]:
                box('Acoustic panel', (side * 8.91, y, z), (.18, 2.3, 3.35), wall, .10, 1.6)
            box('Wall sconce body', (side * 8.72, 3.8, z), (.30, .72, .30), brass, .06)
            box('Wall sconce diffuser', (side * 8.52, 3.8, z), (.06, .5, .19), warm, .02)
        box('Aisle safety line', (side * 1.15, .035, -2.5), (.025, .02, 14), warm, .003)
    for row, z in enumerate([-2.5, -5.0, -7.5]):
        height = row * .23
        for side in [-1, 1]:
            box('Seating riser', (side * 5.1, height / 2 - .015, z), (7.1, height + .08, 2.2), carpet, .025)
        for x in [-6.6, -4.9, -3.2, 3.2, 4.9, 6.6]:
            seat(x, z, height, velvet, black, brass)
    for x in [-6.6, -4.9, 4.9, 6.6]:
        seat(x, 1.2, 0, velvet, black, brass)
    for z in [-7, -1, 5, 11]:
        box('Ceiling rib', (0, 7.35, z), (18.0, .20, .2), wood, .035)
        for x in [-5.2, 5.2]:
            cylinder('Recessed ceiling light', (x, 7.20, z), .19, .05, warm)


def space():
    metal = material('Satin Alloy Panels', 'metal_plate_02', (.43, .52, .64, 1), metal=.85)
    dark = material('Graphite Alloy Frames', 'metal_plate_02', (.075, .10, .15, 1), metal=.8)
    floor = material('Textured Deck Plate', 'metal_plate_02', (.19, .24, .32, 1), metal=.7)
    fabric = material('Slate Lounge Upholstery', 'velour_velvet', (.14, .23, .32, 1))
    blue = material('Fixture Cyan Navigation Strip', tint=(.04, .51, 1, 1), emit=2.5)
    white = material('Fixture Cool Ceiling', tint=(.56, .78, 1, 1), emit=2)
    box('Deck', (0, -.15, 2.5), (18.4, .3, 25.4), floor, .02, .65)
    box('Ceiling core', (0, 7.65, 2.5), (12, .25, 25.4), metal, .04)
    box('Aft bulkhead', (0, 3.8, -10), (18.4, 7.6, .32), metal, .04, .6)
    for side in [-1, 1]:
        for y in [.40, 6.8]:
            box('Panoramic window longitudinal frame', (side * 8.9, y, 2.5), (.34, .45, 25.2), dark, .08)
        box('Window sill', (side * 8.6, .9, 2.5), (.9, .18, 25.2), metal, .04)
        box('Navigation light', (side * 8.34, 1.03, 2.5), (.035, .035, 25.2), blue, .006)
        box('Ceiling light rail', (side * 5.5, 7.35, 2.5), (.055, .055, 24), white, .008)
        for z in [-9, -5, -1, 3, 7, 11, 15]:
            box('Window mullion', (side * 8.9, 3.6, z), (.38, 6.6, .34), dark, .08)
            beam = box('Angled roof rib', (side * 7.45, 7.05, z), (3.5, .35, .34), metal, .07)
            beam.rotation_euler.y = side * math.radians(-17)
            box('Deck transverse rib', (side * 5.05, .015, z), (6.8, .045, .12), dark, .02)
        # Lounge consoles sit beside, rather than across, the viewing axis.
        box('Low side console', (side * 7.1, .45, -.7), (1.1, .9, 3.1), dark, .08)
        for z in [-1.5, -.7, .1]:
            box('Console display', (side * 7.1, .92, z), (.6, .015, .4), blue, .01)
    for y in [.5, 7]:
        box('Forward panoramic header', (0, y, 15), (18.4, .45, .38), dark, .08)
    for x in [-8.9, -4.5, 4.5, 8.9]:
        box('Forward window pillar', (x, 3.8, 15), (.3, 6.4, .38), metal, .06)
    for x in [-4.9, -3.2, 3.2, 4.9]:
        seat(x, -3.7, 0, fabric, dark, metal)
    cylinder('Aft airlock outer', (0, 2.45, -9.65), 2.1, .15, dark, False, 64)
    cylinder('Aft airlock door', (0, 2.45, -9.54), 1.8, .14, metal, False, 64)
    box('Door seam', (0, 2.45, -9.43), (.035, 3.5, .02), dark, .005)
    for x in [-1.9, 1.9]:
        box('Airlock edge light', (x, 2.45, -9.42), (.035, 1.5, .03), blue, .008)
    # A real textured sphere outside the panoramic windows provides positional parallax.
    earth = bpy.data.materials.new('NASA Blue Marble Earth')
    earth.use_nodes = True
    n, l = earth.node_tree.nodes, earth.node_tree.links
    n.clear()
    output, emission, image = n.new('ShaderNodeOutputMaterial'), n.new('ShaderNodeEmission'), n.new('ShaderNodeTexImage')
    image.image = bpy.data.images.load(str(CACHE / 'earth.png'), check_existing=True)
    emission.inputs['Strength'].default_value = .7
    l.new(image.outputs['Color'], emission.inputs['Color'])
    l.new(emission.outputs[0], output.inputs[0])
    bpy.ops.mesh.primitive_uv_sphere_add(segments=64, ring_count=32, radius=13.5, location=pos((20, 13, 39)))
    obj = bpy.context.object
    obj.name = 'Earth beyond the starboard window'
    obj.data.materials.append(earth)
    for p in obj.data.polygons:
        p.use_smooth = True
    stars = material('Distant Stars', tint=(.7, .8, 1, 1), emit=1)
    rng = random.Random(301)
    for i in range(260):
        direction = Vector((rng.uniform(-1, 1), rng.uniform(-1, 1), rng.uniform(-.35, 1))).normalized()
        bpy.ops.mesh.primitive_ico_sphere_add(subdivisions=1, radius=rng.uniform(.025, .075), location=direction * 85)
        bpy.context.object.name = 'Star'
        bpy.context.object.data.materials.append(stars)


def starlight():
    # One merged draw call, not hundreds of point lights. Keep dots below the
    # ceiling and clear of the existing structural ribs and recessed lamps.
    fiber = material('Fixture Fiber Stars', tint=(.68, .82, 1, 1), emit=1)
    rng = random.Random(9301)
    vertices, faces = [], []
    count = 0
    while count < 620:
        x, z = rng.uniform(-8.65, 8.65), rng.uniform(-9.55, 14.55)
        if any(abs(z - rib) < .23 for rib in [-7, -1, 5, 11]):
            continue
        if any(abs(x - lamp) < .3 for lamp in [-5.2, 5.2]) and any(abs(z - rib) < .4 for rib in [-7, -1, 5, 11]):
            continue
        # Discs face down into the room, with a few brighter/large stars.
        radius = rng.uniform(.012, .027) if count % 13 else rng.uniform(.03, .045)
        start = len(vertices)
        for step in range(8):
            angle = step * math.tau / 8
            vertices.append(pos((x + radius * math.cos(angle), 7.43, z + radius * math.sin(angle))))
        faces.append(tuple(start + step for step in range(8)))
        count += 1
    mesh = bpy.data.meshes.new('620 downward-facing fiber optic stars')
    mesh.from_pydata(vertices, [], faces)
    mesh.update()
    obj = bpy.data.objects.new('Fiber optic ceiling', mesh)
    bpy.context.collection.objects.link(obj)
    obj.data.materials.append(fiber)


def frame():
    m = material('Screen Black Frame', 'metal_plate_02', (.025, .028, .035, 1), metal=.65)
    for x in [-3.065, 3.065]:
        box('Screen vertical bezel', (x, 0, 0), (.13, 3.64, .12), m, .025)
    for y in [-1.755, 1.755]:
        box('Screen horizontal bezel', (0, y, 0), (6.26, .13, .12), m, .025)


def merge_material_batches():
    # Keep draw calls low while preserving authored normals, UVs, and material maps.
    groups = {}
    for obj in list(bpy.context.scene.objects):
        if obj.type == 'MESH':
            groups.setdefault(obj.data.materials[0].name, []).append(obj)
    for name, objects in groups.items():
        bpy.ops.object.select_all(action='DESELECT')
        for obj in objects:
            obj.select_set(True)
            bpy.context.view_layer.objects.active = obj
            for modifier in list(obj.modifiers):
                bpy.ops.object.modifier_apply(modifier=modifier.name)
            triangulate = obj.modifiers.new('Export triangles', 'TRIANGULATE')
            bpy.ops.object.modifier_apply(modifier=triangulate.name)
        bpy.context.view_layer.objects.active = objects[0]
        bpy.ops.object.join()
        bpy.context.object.name = name + ' geometry'


def pin_factors(path):
    raw = path.read_bytes()
    length, kind = struct.unpack_from('<II', raw, 12)
    assert kind == 0x4E4F534A
    doc = json.loads(raw[20:20 + length])
    for m in doc.get('materials', []):
        if m['name'] in TINTS:
            m.setdefault('pbrMetallicRoughness', {})['baseColorFactor'] = TINTS[m['name']]
        if m['name'].startswith('Fixture') or m['name'] in ['Distant Stars', 'NASA Blue Marble Earth']:
            # Small practical fixtures stay visible even at the minimum room brightness.
            # Unlit rendering reads base color, not the emissive slot.
            if m['name'] == 'NASA Blue Marble Earth':
                m['pbrMetallicRoughness'] = {'baseColorFactor': [1, 1, 1, 1], 'baseColorTexture': m['emissiveTexture']}
            m.pop('emissiveTexture', None)
            m.pop('emissiveFactor', None)
            m.setdefault('extensions', {}).pop('KHR_materials_emissive_strength', None)
            m.setdefault('extensions', {})['KHR_materials_unlit'] = {}
            m.setdefault('extras', {})['meta_spatial_sdk_shader'] = 'data/shaders/unlit/unlit'
    # Untextured fixture geometry has degenerate UV tangents; those attributes
    # are not used by its shader and must not be passed to the runtime.
    for mesh in doc['meshes']:
        for primitive in mesh['primitives']:
            m = doc['materials'][primitive['material']]
            if 'normalTexture' not in m:
                primitive['attributes'].pop('TANGENT', None)
    used = set(doc.get('extensionsUsed', []))
    if not any('KHR_materials_emissive_strength' in m.get('extensions', {}) for m in doc['materials']):
        used.discard('KHR_materials_emissive_strength')
    used.add('KHR_materials_unlit')
    doc['extensionsUsed'] = sorted(used)
    data = json.dumps(doc, separators=(',', ':')).encode()
    data += b' ' * (-len(data) % 4)
    tail = raw[20 + length:]
    path.write_bytes(struct.pack('<III', 0x46546C67, 2, 20 + len(data) + len(tail)) + struct.pack('<II', len(data), kind) + data + tail)
    triangles = sum(a['count'] // 3 for a in [doc['accessors'][p['indices']] for m in doc['meshes'] for p in m['primitives']])
    return {'file': path.name, 'bytes': path.stat().st_size, 'triangles': triangles, 'primitives': sum(len(m['primitives']) for m in doc['meshes']), 'materials': len(doc.get('materials', [])), 'textures': len(doc.get('images', []))}


def preview(name, ceiling_view=False):
    # Offline render of the authored assets, not a headset screenshot.
    scene = bpy.context.scene
    scene.render.engine = 'CYCLES'
    scene.cycles.samples = 16
    scene.cycles.use_denoising = False
    scene.render.resolution_x = 1120
    scene.render.resolution_y = 700
    scene.render.resolution_percentage = 100
    scene.world.color = (.018, .022, .03)
    for x in [-4.7, 4.7]:
        bpy.ops.object.light_add(type='AREA', location=pos((x, 6.7, 1)))
        light = bpy.context.object
        light.data.energy = 1400
        light.data.shape = 'DISK'
        light.data.size = 4
        light.data.color = (1, .65, .35) if name == 'private_cinema' else (.35, .65, 1)
    # Add a placeholder screen so the composition can be assessed with the real room geometry.
    screen = material('Preview screen only', tint=(.06, .19, .29, 1), emit=.8)
    box('Preview movie screen', (0, 2.6, 7), (6, 3.375, .02), screen, 0)
    bpy.ops.object.camera_add(location=pos((0, 1.65, -1.6)))
    camera = bpy.context.object
    target = (0, 7.0, 4) if ceiling_view else (1.8 if name == 'orbital_lounge' else -3, 2.6, 4)
    direction = Vector(pos(target)) - camera.location
    camera.rotation_euler = direction.to_track_quat('-Z', 'Y').to_euler()
    camera.data.lens = 19
    scene.camera = camera
    filename = 'starlight_ceiling' if ceiling_view else name
    scene.render.filepath = str(PREVIEWS / f'{filename}.png')
    bpy.ops.render.render(write_still=True)


if __name__ == '__main__':
    if '--ceiling-preview' in sys.argv:
        reset()
        # Preview exactly the packaged assets, with offline lighting and camera.
        for filename in ['private_cinema.glb', 'starlight_ceiling.glb']:
            bpy.ops.import_scene.gltf(filepath=str(ASSETS / filename))
        preview('private_cinema', ceiling_view=True)
        sys.exit(0)
    reports = []
    builders = [('private_cinema', theatre), ('orbital_lounge', space), ('screen_frame', frame), ('starlight_ceiling', starlight)]
    if '--stars-only' in sys.argv:
        builders = [('starlight_ceiling', starlight)]
    for name, builder in builders:
        reset()
        builder()
        merge_material_batches()
        path = ASSETS / f'{name}.glb'
        bpy.ops.export_scene.gltf(filepath=str(path), export_format='GLB', export_apply=True, export_yup=True,
                                  export_animations=False, export_cameras=False, export_lights=False,
                                  export_texcoords=True, export_normals=True, export_tangents=True,
                                  export_image_format='JPEG', export_jpeg_quality=85)
        reports.append(pin_factors(path))
        if '--preview' in sys.argv and name in ['private_cinema', 'orbital_lounge']:
            preview(name)
    if '--stars-only' in sys.argv:
        previous = json.loads((ASSETS / 'manifest.json').read_text())['scenes']
        reports = [report for report in previous if report['file'] != 'starlight_ceiling.glb'] + reports
    shutil.copy2(CACHE / 'studio.env', ASSETS / 'studio.env')
    shutil.copy2(REPO / 'ASSET_CREDITS.md', ASSETS / 'CREDITS.md')
    (ASSETS / 'manifest.json').write_text(json.dumps({'format': 1, 'coordinateSystem': '+Y up, +Z forward', 'scenes': reports}, indent=2) + '\n')
    print('SCENE ASSET REPORT', json.dumps(reports))
