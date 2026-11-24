"""
Build the two animated aircraft models used by the globe.

Run with Blender in background mode:

    blender -b --python tools/build-aircraft-models.py

Writes app/src/main/assets/web/plane.glb and helicopter.glb.
Blender axes: -Y is forward (nose), +Z is up. The glTF exporter converts that
to the +Z-forward convention flights.js expects.
"""

import bpy
import bmesh
import math
import os
import sys
from math import cos, sin, pi, radians
from mathutils import Vector

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WEB = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'web')


# ── Scene / material helpers ────────────────────────────────────────────────

def reset_scene(fps=30, frame_end=65):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    scene = bpy.context.scene
    scene.render.fps = fps
    scene.frame_start = 1
    scene.frame_end = frame_end
    bpy.context.preferences.edit.keyframe_new_interpolation_type = 'LINEAR'


def material(name, color, metallic=0.0, roughness=0.45):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    bsdf = m.node_tree.nodes['Principled BSDF']
    bsdf.inputs['Base Color'].default_value = (color[0], color[1], color[2], 1.0)
    bsdf.inputs['Metallic'].default_value = metallic
    bsdf.inputs['Roughness'].default_value = roughness
    m.use_backface_culling = False
    m.diffuse_color = (color[0], color[1], color[2], 1.0)
    return m


# ── Generic geometry helpers ────────────────────────────────────────────────

def sgnpow(value, exponent):
    return math.copysign(abs(value) ** (2.0 / exponent), value)


def make_object(name, verts, faces, mat, smooth=True):
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata(verts, [], faces)
    mesh.validate()
    if faces:
        bm = bmesh.new()
        bm.from_mesh(mesh)
        bmesh.ops.recalc_face_normals(bm, faces=list(bm.faces))
        bm.to_mesh(mesh)
        bm.free()
    for poly in mesh.polygons:
        poly.use_smooth = smooth
    mesh.update()
    obj = bpy.data.objects.new(name, mesh)
    bpy.context.scene.collection.objects.link(obj)
    obj.data.materials.append(mat)
    return obj


def loft(rings, closed=True, cap_start=False, cap_end=False):
    """Skin a list of equally sized rings of 3D points."""
    verts = []
    for ring in rings:
        verts.extend(ring)
    faces = []
    n = len(rings[0])
    span = n if closed else n - 1
    for i in range(len(rings) - 1):
        base = i * n
        nxt = (i + 1) * n
        for j in range(span):
            jj = (j + 1) % n
            faces.append((base + j, base + jj, nxt + jj, nxt + j))
    if cap_start:
        faces.append(tuple(range(n - 1, -1, -1)))
    if cap_end:
        start = (len(rings) - 1) * n
        faces.append(tuple(range(start, start + n)))
    return verts, faces


def circle_ring_y(y, radius, cx, cz, segments, rz=None, exp=2.0):
    rz = radius if rz is None else rz
    pts = []
    for i in range(segments):
        t = 2 * pi * i / segments
        pts.append((cx + radius * sgnpow(cos(t), exp), y, cz + rz * sgnpow(sin(t), exp)))
    return pts


def lathe_y(cx, cz, profile, segments, exp=2.0):
    return [circle_ring_y(y, r, cx, cz, segments, exp=exp) for (y, r) in profile]


def airfoil_ring_x(x, y_le, chord, thickness, z, segments=14):
    """Horizontal lifting surface: chord runs along Y, thickness along Z."""
    pts = []
    for i in range(segments):
        t = i / (segments - 1)
        blend = max(0.0, 1.0 - (2.0 * t - 1.0) ** 2) ** 0.62
        pts.append((x, y_le + t * chord, z + 0.5 * thickness * blend))
    for i in range(segments - 2, 0, -1):
        t = i / (segments - 1)
        blend = max(0.0, 1.0 - (2.0 * t - 1.0) ** 2) ** 0.62
        pts.append((x, y_le + t * chord, z - 0.5 * thickness * blend))
    return pts


def airfoil_ring_z(z, y_le, chord, thickness, x_center, segments=14):
    """Vertical surface: chord along Y, thickness along X."""
    pts = []
    for i in range(segments):
        t = i / (segments - 1)
        blend = max(0.0, 1.0 - (2.0 * t - 1.0) ** 2) ** 0.62
        pts.append((x_center + 0.5 * thickness * blend, y_le + t * chord, z))
    for i in range(segments - 2, 0, -1):
        t = i / (segments - 1)
        blend = max(0.0, 1.0 - (2.0 * t - 1.0) ** 2) ** 0.62
        pts.append((x_center - 0.5 * thickness * blend, y_le + t * chord, z))
    return pts


def cylinder(name, p1, p2, radius, mat, segments=16, smooth=True):
    p1 = Vector(p1)
    p2 = Vector(p2)
    axis = (p2 - p1)
    length = axis.length
    if length < 1e-6:
        return None
    axis.normalize()
    helper = Vector((0, 0, 1)) if abs(axis.z) < 0.9 else Vector((1, 0, 0))
    side = axis.cross(helper).normalized()
    up = axis.cross(side).normalized()
    rings = []
    for p in (p1, p2):
        ring = []
        for i in range(segments):
            t = 2 * pi * i / segments
            ring.append(tuple(p + side * (radius * cos(t)) + up * (radius * sin(t))))
        rings.append(ring)
    verts, faces = loft(rings, cap_start=True, cap_end=True)
    return make_object(name, verts, faces, mat, smooth=smooth)


def uv_sphere(name, center, scale, mat, segments=24, rings=12):
    bpy.ops.mesh.primitive_uv_sphere_add(segments=segments, ring_count=rings, radius=1.0, location=center)
    obj = bpy.context.view_layer.objects.active
    obj.scale = scale
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
    obj.name = name
    obj.data.materials.append(mat)
    for poly in obj.data.polygons:
        poly.use_smooth = True
    return obj


def box(name, center, size, mat, smooth=False):
    bpy.ops.mesh.primitive_cube_add(size=1.0, location=center)
    obj = bpy.context.view_layer.objects.active
    obj.scale = size
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
    obj.name = name
    obj.data.materials.append(mat)
    for poly in obj.data.polygons:
        poly.use_smooth = smooth
    return obj


def cone(name, center, radius1, radius2, depth, mat, rotation=(pi / 2, 0, 0), vertices=24):
    bpy.ops.mesh.primitive_cone_add(
        vertices=vertices, radius1=radius1, radius2=radius2, depth=depth, location=center, rotation=rotation,
    )
    obj = bpy.context.view_layer.objects.active
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
    obj.name = name
    obj.data.materials.append(mat)
    for poly in obj.data.polygons:
        poly.use_smooth = True
    return obj


def torus(name, center, major, minor, mat, axis='Y', major_segments=32, minor_segments=8):
    cx, cy, cz = center
    verts = []
    for i in range(major_segments):
        u = 2 * pi * i / major_segments
        for j in range(minor_segments):
            v = 2 * pi * j / minor_segments
            r = major + minor * cos(v)
            if axis == 'Y':
                verts.append((cx + r * cos(u), cy + minor * sin(v), cz + r * sin(u)))
            else:
                verts.append((cx + minor * sin(v), cy + r * cos(u), cz + r * sin(u)))
    faces = []
    for i in range(major_segments):
        ni = (i + 1) % major_segments
        for j in range(minor_segments):
            nj = (j + 1) % minor_segments
            faces.append((
                i * minor_segments + j,
                ni * minor_segments + j,
                ni * minor_segments + nj,
                i * minor_segments + nj,
            ))
    return make_object(name, verts, faces, mat, smooth=True)


def join_by_material(objects, prefix, skip=()):
    """Join every object that uses the same material into one mesh."""
    groups = {}
    for obj in objects:
        if obj is None or obj in skip:
            continue
        mat = obj.data.materials[0]
        groups.setdefault(mat.name, []).append(obj)
    for mat_name, group in groups.items():
        bpy.ops.object.select_all(action='DESELECT')
        for obj in group:
            obj.select_set(True)
        bpy.context.view_layer.objects.active = group[0]
        if len(group) > 1:
            bpy.ops.object.join()
        joined = bpy.context.view_layer.objects.active
        joined.name = prefix + '_' + mat_name
    bpy.ops.object.select_all(action='DESELECT')


def export_glb(path, animated):
    bpy.ops.object.select_all(action='SELECT')
    bpy.ops.export_scene.gltf(
        filepath=path,
        export_format='GLB',
        use_selection=True,
        export_apply=True,
        export_animations=animated,
        export_animation_mode='ACTIONS',
        export_yup=True,
        export_cameras=False,
        export_lights=False,
        export_materials='EXPORT',
        export_image_format='NONE',
        export_normals=True,
        export_texcoords=False,
        export_tangents=False,
    )
    print('exported', path, os.path.getsize(path), 'bytes')


def spin(obj, axis, revolutions, frames=64, keys_per_rev=8):
    obj.rotation_mode = 'XYZ'
    total = revolutions * keys_per_rev
    for i in range(total + 1):
        euler = [0.0, 0.0, 0.0]
        euler[axis] = 2 * pi * revolutions * i / total
        obj.rotation_euler = euler
        obj.keyframe_insert('rotation_euler', frame=1 + frames * i / total)


# ── Airliner ────────────────────────────────────────────────────────────────

FUSELAGE = [
    # (y, radius, z-centre)
    (-18.80, 0.16, 0.02), (-18.55, 0.44, 0.02), (-18.10, 0.74, 0.03),
    (-17.20, 1.06, 0.04), (-16.00, 1.44, 0.05), (-14.30, 1.77, 0.05),
    (-12.30, 1.94, 0.05), (-9.00, 2.01, 0.04), (-2.00, 2.02, 0.03),
    (6.00, 2.00, 0.06), (11.00, 1.93, 0.14), (13.60, 1.78, 0.28),
    (15.60, 1.48, 0.52), (17.20, 1.02, 0.92), (18.20, 0.62, 1.35),
    (18.80, 0.28, 1.72), (18.95, 0.08, 1.84),
]
FUS_EXP = 2.05
FUS_SEG = 32


def fus_station(y):
    """Linear interpolation of the fuselage profile at y."""
    if y <= FUSELAGE[0][0]:
        return FUSELAGE[0][1], FUSELAGE[0][2]
    for (y0, r0, z0), (y1, r1, z1) in zip(FUSELAGE, FUSELAGE[1:]):
        if y <= y1:
            t = (y - y0) / (y1 - y0)
            return r0 + (r1 - r0) * t, z0 + (z1 - z0) * t
    return FUSELAGE[-1][1], FUSELAGE[-1][2]


def surface(y, angle, offset=0.0):
    r, zc = fus_station(y)
    rr = r + offset
    return (rr * sgnpow(cos(angle), FUS_EXP), y, zc + rr * sgnpow(sin(angle), FUS_EXP))


def surface_band(name, y0, y1, a0, a1, mat, offset, y_steps=8, a_steps=10, smooth=True):
    rings = []
    for i in range(y_steps):
        y = y0 + (y1 - y0) * i / (y_steps - 1)
        ring = []
        for j in range(a_steps):
            a = a0 + (a1 - a0) * j / (a_steps - 1)
            ring.append(surface(y, a, offset))
        rings.append(ring)
    verts, faces = loft(rings, closed=False)
    return make_object(name, verts, faces, mat, smooth=smooth)


def build_plane():
    reset_scene()
    white = material('body', (0.90, 0.915, 0.935), metallic=0.0, roughness=0.38)
    red = material('accent', (0.62, 0.018, 0.030), metallic=0.02, roughness=0.34)
    dark = material('dark', (0.045, 0.062, 0.085), metallic=0.22, roughness=0.24)
    metal = material('metal', (0.58, 0.62, 0.68), metallic=0.45, roughness=0.3)

    created = []

    # Fuselage shell.
    rings = []
    for y, r, zc in FUSELAGE:
        rings.append(circle_ring_y(y, r, 0.0, zc, FUS_SEG, exp=FUS_EXP))
    verts, faces = loft(rings, cap_start=True, cap_end=True)
    created.append(make_object('fuselage', verts, faces, white))

    # Dark belly: a thin skin following the lower fuselage.
    created.append(surface_band(
        'belly', -13.2, 15.2, pi + 0.30, 2 * pi - 0.30, dark, 0.006,
        y_steps=12, a_steps=16,
    ))

    # Red cheatline below the windows, both sides.
    for side, a0, a1 in ((1, -0.245, -0.145), (-1, pi + 0.145, pi + 0.245)):
        created.append(surface_band(
            'cheatline',
            -12.6, 15.4, a0, a1, red, 0.008, y_steps=12, a_steps=6,
        ))

    # Cockpit windshield wrapping the upper nose.
    created.append(surface_band(
        'cockpit', -17.35, -13.85, 0.42, pi - 0.42, dark, 0.018,
        y_steps=9, a_steps=16,
    ))

    # Cabin windows: small individual panes rather than a continuous stripe.
    verts = []
    faces = []
    y = -12.3
    while y < 12.9:
        for a_center in (0.185, pi - 0.185):
            ring0 = [surface(y - 0.16, a_center - 0.075, 0.012),
                     surface(y - 0.16, a_center + 0.075, 0.012)]
            ring1 = [surface(y + 0.16, a_center - 0.075, 0.012),
                     surface(y + 0.16, a_center + 0.075, 0.012)]
            base = len(verts)
            verts.extend(ring0 + ring1)
            faces.append((base, base + 1, base + 3, base + 2))
        y += 0.66
    created.append(make_object('windows', verts, faces, dark, smooth=False))

    # Wing-root fairing.
    created.append(uv_sphere('wingroot', (0.0, 2.1, -0.72), (2.05, 5.6, 0.95), white))

    # Main wings, right then mirrored.
    wing_sections = [
        (1.60, -1.80, 6.60, 0.62, -0.95),
        (5.60, 0.25, 5.55, 0.50, -0.82),
        (8.50, 2.40, 4.20, 0.36, -0.62),
        (12.50, 3.95, 3.15, 0.28, -0.48),
        (17.40, 6.00, 1.90, 0.16, -0.30),
    ]
    for side in (1, -1):
        rings = [airfoil_ring_x(side * x, y_le, chord, thick, z, segments=16)
                 for (x, y_le, chord, thick, z) in wing_sections]
        verts, faces = loft(rings, cap_start=True, cap_end=True)
        created.append(make_object('wing', verts, faces, white))

    # Upturned winglets.
    for side in (1, -1):
        rings = [
            airfoil_ring_z(-0.30, 6.00, 1.90, 0.17, side * 17.40, segments=12),
            airfoil_ring_z(0.55, 6.18, 1.58, 0.14, side * 17.62, segments=12),
            airfoil_ring_z(1.70, 6.72, 1.05, 0.10, side * 17.78, segments=12),
        ]
        verts, faces = loft(rings, cap_start=True, cap_end=True)
        created.append(make_object('winglet', verts, faces, red))

    # Horizontal stabilisers.
    for side in (1, -1):
        rings = [
            airfoil_ring_x(side * 0.30, 13.70, 2.75, 0.24, 0.86, segments=12),
            airfoil_ring_x(side * 3.40, 14.65, 1.85, 0.17, 0.94, segments=12),
            airfoil_ring_x(side * 6.40, 15.60, 1.20, 0.11, 1.02, segments=12),
        ]
        verts, faces = loft(rings, cap_start=True, cap_end=True)
        created.append(make_object('stabiliser', verts, faces, white))

    # Vertical fin.
    rings = [
        airfoil_ring_z(1.10, 12.20, 6.35, 0.50, 0.0, segments=16),
        airfoil_ring_z(5.60, 13.90, 4.10, 0.36, 0.0, segments=16),
        airfoil_ring_z(10.20, 15.60, 2.10, 0.22, 0.0, segments=16),
    ]
    verts, faces = loft(rings, cap_start=True, cap_end=True)
    created.append(make_object('fin', verts, faces, red))

    # Dorsal fin fillet.
    created.append(box('dorsal', (0.0, 12.35, 2.05), (0.36, 4.5, 0.7), red, smooth=True))

    # Engines and pylons.
    nacelle_profile = [
        (-1.92, 0.56), (-1.82, 0.82), (-1.62, 0.98), (-1.12, 1.06),
        (-0.20, 1.08), (0.60, 1.04), (1.20, 0.94), (1.55, 0.80), (1.70, 0.68),
    ]
    for side in (1, -1):
        cx = side * 5.70
        cz = -2.02
        rings = lathe_y(cx, cz, nacelle_profile, 24)
        verts, faces = loft(rings, cap_start=False, cap_end=True)
        created.append(make_object('nacelle', verts, faces, metal))

        # Chrome intake lip.
        created.append(torus('lip', (cx, -1.90, cz), 0.89, 0.055, metal, axis='Y'))

        # Fan face and spinner.
        created.append(cylinder('fan', (cx, -1.84, cz), (cx, -1.80, cz), 0.84, dark, segments=28))
        created.append(cone('spinner', (cx, -1.90, cz), 0.17, 0.015, 0.42, metal))

        # Exhaust nozzle.
        exhaust = [circle_ring_y(1.66, 0.62, cx, cz, 20), circle_ring_y(2.30, 0.45, cx, cz, 20),
                   circle_ring_y(2.34, 0.38, cx, cz, 20)]
        verts, faces = loft(exhaust, cap_start=False, cap_end=True)
        created.append(make_object('exhaust', verts, faces, metal))

        # Pylon: a tapered plate, not a cube, so the nacelle hangs cleanly.
        pylon_verts = [
            (cx - 0.17, -0.90, -0.66), (cx + 0.17, -0.90, -0.66),
            (cx + 0.17, 1.35, -0.66), (cx - 0.17, 1.35, -0.66),
            (cx - 0.17, -0.62, -1.04), (cx + 0.17, -0.62, -1.04),
            (cx + 0.17, 0.95, -1.04), (cx - 0.17, 0.95, -1.04),
        ]
        pylon_faces = [
            (0, 1, 2, 3), (4, 7, 6, 5), (0, 4, 5, 1),
            (1, 5, 6, 2), (2, 6, 7, 3), (3, 7, 4, 0),
        ]
        created.append(make_object('pylon', pylon_verts, pylon_faces, white, smooth=False))

    join_by_material(created, 'plane')
    export_glb(os.path.join(WEB, 'plane.glb'), animated=False)


# ── Helicopter ──────────────────────────────────────────────────────────────

HELI_BODY = [
    # (y, radius-x, radius-z, z-centre, exponent)
    (-3.45, 0.18, 0.22, 0.05, 2.00), (-3.15, 0.55, 0.62, 0.05, 2.00),
    (-2.70, 0.86, 0.92, 0.08, 2.10), (-2.00, 1.02, 1.06, 0.12, 2.15),
    (-1.00, 1.08, 1.10, 0.16, 2.15), (0.00, 1.08, 1.10, 0.20, 2.15),
    (0.90, 1.00, 1.00, 0.28, 2.10), (1.60, 0.82, 0.82, 0.40, 2.05),
    (2.20, 0.55, 0.58, 0.55, 2.00), (2.80, 0.40, 0.42, 0.70, 2.00),
    (4.00, 0.33, 0.36, 0.85, 2.00), (5.60, 0.30, 0.34, 1.02, 2.00),
    (7.20, 0.26, 0.32, 1.16, 2.00), (8.20, 0.22, 0.30, 1.25, 2.00),
    (8.60, 0.16, 0.26, 1.30, 2.00),
]


def heli_station(y):
    if y <= HELI_BODY[0][0]:
        return HELI_BODY[0][1:]
    for a, b in zip(HELI_BODY, HELI_BODY[1:]):
        if y <= b[0]:
            t = (y - a[0]) / (b[0] - a[0])
            return tuple(a[i] + (b[i] - a[i]) * t for i in range(1, 5))
    return HELI_BODY[-1][1:]


def heli_surface(y, angle, offset=0.0):
    rx, rz, zc, exp = heli_station(y)
    kx = (rx + offset) / max(rx, 1e-4)
    kz = (rz + offset) / max(rz, 1e-4)
    return (rx * kx * sgnpow(cos(angle), exp), y, zc + rz * kz * sgnpow(sin(angle), exp))


def heli_band(name, y0, y1, a0, a1, mat, offset, y_steps=8, a_steps=10):
    rings = []
    for i in range(y_steps):
        y = y0 + (y1 - y0) * i / (y_steps - 1)
        ring = []
        for j in range(a_steps):
            a = a0 + (a1 - a0) * j / (a_steps - 1)
            ring.append(heli_surface(y, a, offset))
        rings.append(ring)
    verts, faces = loft(rings, closed=False)
    return make_object(name, verts, faces, mat)


def build_helicopter():
    reset_scene()
    white = material('body', (0.92, 0.93, 0.945), metallic=0.0, roughness=0.38)
    red = material('accent', (0.62, 0.018, 0.030), metallic=0.03, roughness=0.34)
    dark = material('dark', (0.04, 0.055, 0.075), metallic=0.25, roughness=0.22)
    metal = material('metal', (0.55, 0.60, 0.66), metallic=0.4, roughness=0.3)

    created = []

    # Fuselage + tail boom shell.
    rings = []
    for y, rx, rz, zc, exp in HELI_BODY:
        ring = []
        for i in range(28):
            t = 2 * pi * i / 28
            ring.append((rx * sgnpow(cos(t), exp), y, zc + rz * sgnpow(sin(t), exp)))
        rings.append(ring)
    verts, faces = loft(rings, cap_start=True, cap_end=True)
    created.append(make_object('heli_fuselage', verts, faces, white))

    # Dark belly and red side stripe.
    created.append(heli_band('belly', -2.9, 2.4, pi + 0.35, 2 * pi - 0.35, dark, 0.008, y_steps=10, a_steps=14))
    for a0, a1 in ((-0.225, -0.115), (pi + 0.115, pi + 0.225)):
        created.append(heli_band('stripe', -2.85, 1.55, a0, a1, red, 0.01, y_steps=8, a_steps=5))

    # Wrap-around canopy.
    created.append(heli_band('canopy', -3.38, -1.55, 0.38, pi - 0.38, dark, 0.016, y_steps=9, a_steps=16))

    # Cabin side windows.
    window_verts = []
    window_faces = []
    for y0, y1 in ((-1.42, -0.48), (0.05, 0.85)):
        for a_center in (0.06, pi - 0.06):
            base = len(window_verts)
            window_verts.extend([
                heli_surface(y0, a_center - 0.10, 0.012),
                heli_surface(y0, a_center + 0.10, 0.012),
                heli_surface(y1, a_center + 0.10, 0.012),
                heli_surface(y1, a_center - 0.10, 0.012),
            ])
            window_faces.append((base, base + 1, base + 2, base + 3))
    created.append(make_object('heli_windows', window_verts, window_faces, dark, smooth=False))

    # Engine cowling and exhausts.
    created.append(uv_sphere('cowling', (0.0, 0.55, 1.12), (0.80, 1.75, 0.62), white))
    for side in (1, -1):
        created.append(cylinder(
            'exhaust', (side * 0.48, 1.55, 1.15), (side * 0.48, 2.35, 1.22), 0.145, metal, segments=16,
        ))

    # Nose sensor ball.
    created.append(uv_sphere('flir', (0.0, -3.05, -0.42), (0.20, 0.22, 0.20), metal, segments=20, rings=10))
    created.append(cylinder('flir_lens', (0.0, -3.24, -0.42), (0.0, -3.20, -0.42), 0.12, dark, segments=18))

    # Tail fin.
    fin_rings = [
        airfoil_ring_z(1.15, 8.00, 2.15, 0.30, 0.0, segments=14),
        airfoil_ring_z(2.35, 8.42, 1.62, 0.24, 0.0, segments=14),
        airfoil_ring_z(3.95, 8.95, 0.98, 0.16, 0.0, segments=14),
    ]
    verts, faces = loft(fin_rings, cap_start=True, cap_end=True)
    created.append(make_object('heli_fin', verts, faces, red))

    # Horizontal stabiliser on the boom.
    for side in (1, -1):
        rings = [
            airfoil_ring_x(side * 0.15, 7.25, 1.05, 0.14, 1.36, segments=10),
            airfoil_ring_x(side * 1.65, 7.55, 0.72, 0.09, 1.40, segments=10),
        ]
        verts, faces = loft(rings, cap_start=True, cap_end=True)
        created.append(make_object('heli_stab', verts, faces, white))

    # Tail-rotor guard ring.
    created.append(torus('tail_guard', (0.24, 8.72, 2.15), 1.05, 0.055, red, axis='X'))

    # Tail-rotor hub (blades go in the animated mesh below).
    created.append(cylinder('tail_hub', (0.14, 8.72, 2.15), (0.40, 8.72, 2.15), 0.16, metal, segments=16))

    # Skids and struts.
    for side in (1, -1):
        created.append(cylinder('skid', (side * 1.06, -2.85, -1.66), (side * 1.06, 3.15, -1.66), 0.07, metal, segments=12))
        for y in (-1.15, 1.25):
            created.append(cylinder('strut',
                                    (side * 0.84, y, -0.55), (side * 1.06, y, -1.63), 0.055, metal, segments=10))
    for y in (-2.45, 2.75):
        created.append(cylinder('skid_cross', (-1.06, y, -1.66), (1.06, y, -1.66), 0.055, metal, segments=10))

    # Main-rotor mast and hub.
    created.append(cylinder('mast', (0.0, 0.0, 1.88), (0.0, 0.0, 2.58), 0.11, metal, segments=14))
    created.append(uv_sphere('hub', (0.0, 0.0, 2.62), (0.34, 0.34, 0.16), dark, segments=20, rings=10))

    join_by_material(created, 'heli')

    # ── Animated main rotor ────────────────────────────────────────────────
    rotor = bpy.data.objects.new('main_rotor', None)
    rotor.empty_display_size = 0.4
    bpy.context.scene.collection.objects.link(rotor)
    rotor.location = (0.0, 0.0, 2.62)
    spin(rotor, 2, revolutions=6, frames=64)

    blade_verts = []
    blade_faces = []
    for k in range(4):
        angle = k * pi / 2
        ca, sa = cos(angle), sin(angle)
        rings = [
            airfoil_ring_x(0.25, -0.19, 0.40, 0.075, 0.0, segments=10),
            airfoil_ring_x(2.20, -0.17, 0.36, 0.065, -0.04, segments=10),
            airfoil_ring_x(4.60, -0.14, 0.31, 0.050, -0.09, segments=10),
            airfoil_ring_x(5.35, -0.11, 0.26, 0.040, -0.12, segments=10),
        ]
        base = len(blade_verts)
        for ring in rings:
            for (x, y, z) in ring:
                blade_verts.append((x * ca - y * sa, x * sa + y * ca, z))
        n = len(rings[0])
        for i in range(len(rings) - 1):
            b = base + i * n
            nb = b + n
            for j in range(n):
                jj = (j + 1) % n
                blade_faces.append((b + j, b + jj, nb + jj, nb + j))
        blade_faces.append(tuple(range(base + n - 1, base - 1, -1)))
        last = base + (len(rings) - 1) * n
        blade_faces.append(tuple(range(last, last + n)))
    blade = make_object('main_blades', blade_verts, blade_faces, dark)
    blade.parent = rotor

    # ── Animated tail rotor ────────────────────────────────────────────────
    tail = bpy.data.objects.new('tail_rotor', None)
    tail.empty_display_size = 0.3
    bpy.context.scene.collection.objects.link(tail)
    tail.location = (0.30, 8.72, 2.15)
    spin(tail, 0, revolutions=14, frames=64)

    tail_verts = []
    tail_faces = []
    for k in range(3):
        angle = k * 2 * pi / 3
        ca, sa = cos(angle), sin(angle)
        # A blade in the local YZ disc, thin along X.
        y0, y1 = 0.04, 1.00
        chord, thick = 0.20, 0.035
        pts = []
        for dy in (y0, y1):
            for z in (-chord / 2, chord / 2):
                pts.append((thick / 2, dy, z))
        for dy in (y0, y1):
            for z in (-chord / 2, chord / 2):
                pts.append((-thick / 2, dy, z))
        base = len(tail_verts)
        for (x, y, z) in pts:
            tail_verts.append((x, y * ca - z * sa, y * sa + z * ca))
        # Two boxes per blade (front and back faces).
        tail_faces.extend([
            (base + 0, base + 1, base + 3, base + 2),
            (base + 4, base + 6, base + 7, base + 5),
            (base + 0, base + 4, base + 5, base + 1),
            (base + 2, base + 3, base + 7, base + 6),
            (base + 0, base + 2, base + 6, base + 4),
            (base + 1, base + 5, base + 7, base + 3),
        ])
    tail_blade = make_object('tail_blades', tail_verts, tail_faces, dark)
    tail_blade.parent = tail

    export_glb(os.path.join(WEB, 'helicopter.glb'), animated=True)


if __name__ == '__main__':
    build_plane()
    build_helicopter()
