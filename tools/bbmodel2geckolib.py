#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Convert a Blockbench ``.bbmodel`` into GeckoLib 4.x assets (R27).

The user's asset (``顶级唤魔者_冲撞三段_无恼鬼尖刺``) carries **two** embedded textures and is a
``model_format: free`` / ``box_uv: false`` document.  GeckoLib's ``GeoModel`` samples a single
texture per model, so the converter stacks the two source images into one atlas and rewrites the
UVs of every face that referenced the *non*-primary texture:

    atlas (128 x 192)             source
    +------------------+  y=0      tex[0]  128x128   (冷灰金边与技能道具)  -- UVs untouched
    |      tex[0]      |
    +------------------+  y=128    tex[1]   64x64    (原版唤魔者_evoker)   -- UVs shifted by (0,+128)
    |   tex[1] |      |
    +----------+------+  y=192

Only the second texture's faces get an offset, so all 882 faces of the main sheet keep the exact
pixels they had.  Every face is re-sampled before/after the merge and the result is reported.

Outputs (paths relative to ``--assets``):

    geo/entity/top_evoker.geo.json
    animations/entity/top_evoker.animation.json
    textures/entity/top_evoker.png

The conversion is deliberately lossless w.r.t. the source document: every bone, every cube, every
face and every keyframe is carried over; ``loop`` maps ``loop -> "loop"``, ``once -> "play_once"``,
``hold -> "hold_on_last_frame"``.  Keyframe component values are stored as *strings* in the
``.bbmodel`` and are coerced to floats here.

Usage::

    python tools/bbmodel2geckolib.py \
        --input ../models/top_evoker/top_evoker.bbmodel \
        --assets src/main/resources/assets/stealindims \
        [--report ../../_tmp/r27_convert_report.json]
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import io
import json
import os
import re
import sys
from collections import OrderedDict

try:
    from PIL import Image
except ImportError:  # pragma: no cover - the machine has Pillow, but keep the error legible
    sys.stderr.write("Pillow is required (pip install pillow)\n")
    raise

GEOMETRY_IDENTIFIER = "geometry.stealindims.top_evoker"
FORMAT_VERSION_GEO = "1.12.0"
FORMAT_VERSION_ANIM = "1.8.0"

#: Blockbench's ``loop`` enum -> Bedrock/GeckoLib loop id (verified against GeckoLib 4.9.2
#: ``Animation$LoopType.fromString`` which registers "loop"/"true", "play_once"/"false" and
#: "hold_on_last_frame").
LOOP_MAP = {
    "loop": "loop",
    "once": "play_once",
    "hold": "hold_on_last_frame",
}

#: Blockbench interpolation -> GeckoLib ``easing`` id (see ``EasingType`` registered names).
EASING_MAP = {
    "catmullrom": "catmullrom",
    "step": "step",
    "linear": "linear",
    "smooth": "catmullrom",
}

FACE_NAMES = ("north", "south", "east", "west", "up", "down")


# --------------------------------------------------------------------------- helpers


def num(value, default=0.0):
    """Blockbench stores keyframe components as strings; coerce defensively."""
    if value is None:
        return default
    if isinstance(value, bool):
        return float(value)
    if isinstance(value, (int, float)):
        return float(value)
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def clean(value):
    """Round away float noise so the emitted JSON is readable and stable."""
    f = round(float(value), 6)
    if f == int(f) and abs(f) < 1e15:
        return int(f)
    return f


def vec3(seq):
    return [clean(seq[0]), clean(seq[1]), clean(seq[2])]


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest().upper()


# --------------------------------------------------------------------------- loading


def load_document(path):
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def decode_textures(doc):
    """Return ``[(name, PIL.Image)]`` for the embedded base64 textures, in document order."""
    out = []
    for entry in doc.get("textures") or []:
        source = entry.get("source") or ""
        match = re.match(r"data:image/(\w+);base64,(.*)$", source, re.S)
        if not match:
            raise SystemExit("texture %r has no embedded base64 source" % entry.get("name"))
        image = Image.open(io.BytesIO(base64.b64decode(match.group(2)))).convert("RGBA")
        out.append((entry.get("name") or "texture", image))
    if not out:
        raise SystemExit("the .bbmodel has no textures")
    return out


def walk_outliner(nodes, parent=None, parents=None):
    """Map every outliner node uuid -> its enclosing bone uuid.

    A ``.bbmodel`` stores no back-pointer on the element itself: a cube only knows its bone through
    the ``outliner`` tree, where the cube's uuid appears as a *child* of the group that owns it.
    """
    parents = {} if parents is None else parents
    for node in nodes or []:
        if isinstance(node, dict):
            uuid = node.get("uuid")
            if uuid:
                parents[uuid] = parent
            walk_outliner(node.get("children"), uuid, parents)
        else:
            parents[node] = parent
    return parents


# --------------------------------------------------------------------------- atlas


def build_atlas(images):
    """Stack the source textures vertically (width = widest) and return (atlas, offsets).

    ``offsets[i]`` is the ``(dx, dy)`` that must be *added* to a UV that referenced texture ``i``.
    The first texture is placed at the origin so its UVs are a no-op.
    """
    width = max(image.width for _, image in images)
    height = sum(image.height for _, image in images)
    atlas = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    offsets = []
    y = 0
    for _, image in images:
        atlas.paste(image, (0, y))
        offsets.append((0, y))
        y += image.height
    return atlas, offsets, (width, height)


# --------------------------------------------------------------------------- geo.json


def build_geometry(doc, offsets, atlas_size):
    groups = {g["uuid"]: g for g in doc.get("groups") or []}
    parents = walk_outliner(doc.get("outliner") or [])

    # bones with their (already deterministic) document order, parents first
    order = []
    seen = set()

    def visit(uuid):
        if uuid in seen or uuid not in groups:
            return
        parent = parents.get(uuid)
        if parent in groups:
            visit(parent)
        seen.add(uuid)
        order.append(uuid)

    for uuid in groups:
        visit(uuid)

    cubes_by_bone = {uuid: [] for uuid in groups}
    for element in doc.get("elements") or []:
        bone = parents.get(element.get("uuid"))
        if bone not in groups:
            raise SystemExit("element %r belongs to no known bone" % element.get("name"))
        cubes_by_bone[bone].append(element)

    bones = []
    for uuid in order:
        group = groups[uuid]
        parent = parents.get(uuid)
        bone = OrderedDict()
        bone["name"] = group["name"]
        if parent in groups:
            bone["parent"] = groups[parent]["name"]
        bone["pivot"] = vec3(group.get("origin") or [0, 0, 0])
        rotation = group.get("rotation") or [0, 0, 0]
        if any(num(v) != 0.0 for v in rotation):
            bone["rotation"] = vec3(flip_rotation([num(v) for v in rotation]))
        cubes = []
        for element in cubes_by_bone[uuid]:
            cubes.append(convert_cube(element, offsets, atlas_size))
        if cubes:
            bone["cubes"] = cubes
        bones.append(bone)

    geometry = OrderedDict()
    geometry["description"] = OrderedDict(
        [
            ("identifier", GEOMETRY_IDENTIFIER),
            ("texture_width", atlas_size[0]),
            ("texture_height", atlas_size[1]),
            ("visible_bounds_width", 3.0),
            ("visible_bounds_height", 3.5),
            ("visible_bounds_offset", [0, 1.5, 0]),
        ]
    )
    geometry["bones"] = bones
    return OrderedDict(
        [("format_version", FORMAT_VERSION_GEO), ("minecraft:geometry", [geometry])]
    )


def convert_cube(element, offsets, atlas_size):
    origin = [num(v) for v in element["from"]]
    size = [num(element["to"][i]) - num(element["from"][i]) for i in range(3)]
    cube = OrderedDict()
    cube["origin"] = vec3(origin)
    cube["size"] = vec3(size)

    uvs = OrderedDict()
    faces = element.get("faces") or {}
    for face in FACE_NAMES:
        data = faces.get(face)
        if not data or not data.get("uv"):
            continue
        u1, v1, u2, v2 = [num(v) for v in data["uv"]]
        texture = data.get("texture", 0)
        if not isinstance(texture, int) or texture < 0 or texture >= len(offsets):
            texture = 0
        dx, dy = offsets[texture]
        entry = OrderedDict()
        # GeckoLib's FaceUV takes ``uv`` (the first corner) plus ``uv_size``; a negative size is
        # the Bedrock way of flipping the face, which is exactly what Blockbench's [u1,v1,u2,v2]
        # encodes when u2 < u1 or v2 < v1.
        entry["uv"] = [clean(u1 + dx), clean(v1 + dy)]
        entry["uv_size"] = [clean(u2 - u1), clean(v2 - v1)]
        uvs[face] = entry
    if uvs:
        cube["uv"] = uvs

    rotation = element.get("rotation") or [0, 0, 0]
    if any(num(v) != 0.0 for v in rotation):
        cube["pivot"] = vec3([num(v) for v in (element.get("origin") or [0, 0, 0])])
        cube["rotation"] = vec3(flip_rotation([num(v) for v in rotation]))
    inflate = element.get("inflate")
    if inflate:
        cube["inflate"] = clean(num(inflate))
    if element.get("mirror_modeling"):
        cube["mirror"] = True
    return cube


# --------------------------------------------------------------------------- animation.json


# Bedrock/GeckoLib rotate a bone about +X in the *opposite* sense to Blockbench's model space, so a
# raw `.bbmodel` X rotation has to be negated on the way out. Without this the boss's limbs swing the
# wrong way along the front/back axis while every other axis is right — exactly the defect reported on
# 2026-10-06 ("手应该向前伸的时候向后伸了 … 应该是镜像反了"). The Y and Z conventions match, so only X is
# touched; the cube and rest-pose rotations are negated too, because they live in the same space.
FLIP_ROTATION_X = True


def flip_rotation(vector):
    """Apply the Blockbench -> Bedrock X-rotation sign convention to a 3-component rotation."""
    if not FLIP_ROTATION_X:
        return vector
    return [-vector[0], vector[1], vector[2]]


def build_animations(doc):
    groups = {g["uuid"]: g for g in doc.get("groups") or []}
    animations = OrderedDict()
    for animation in doc.get("animations") or []:
        name = animation["name"]
        loop = LOOP_MAP.get(animation.get("loop"))
        if loop is None:
            raise SystemExit("animation %r has unknown loop %r" % (name, animation.get("loop")))
        entry = OrderedDict()
        entry["loop"] = loop
        entry["animation_length"] = clean(speed(num(animation.get("length"), 0.0)))
        bones = OrderedDict()
        for uuid, animator in (animation.get("animators") or {}).items():
            keyframes = animator.get("keyframes") or []
            if not keyframes:
                continue
            bone_name = groups.get(uuid, {}).get("name", animator.get("name"))
            if bone_name is None:
                raise SystemExit("animator %r in %r names no bone" % (uuid, name))
            channels = OrderedDict()
            buckets = OrderedDict((("rotation", []), ("position", []), ("scale", [])))
            for keyframe in keyframes:
                channel = keyframe.get("channel")
                if channel not in buckets:
                    raise SystemExit("animation %r: unknown channel %r" % (name, channel))
                points = keyframe.get("data_points") or []
                if len(points) != 1:
                    raise SystemExit(
                        "animation %r: %d data points on a %s keyframe" % (name, len(points), channel)
                    )
                point = points[0]
                value = [num(point.get("x")), num(point.get("y")), num(point.get("z"))]
                if channel == "rotation":
                    value = flip_rotation(value)
                easing = EASING_MAP.get((keyframe.get("interpolation") or "linear").lower())
                if easing is None:
                    raise SystemExit(
                        "animation %r: unknown interpolation %r" % (name, keyframe.get("interpolation"))
                    )
                buckets[channel].append((num(keyframe.get("time")), value, easing))
            for channel, frames in buckets.items():
                if not frames:
                    continue
                frames.sort(key=lambda item: item[0])
                # GeckoLib keyframe maps are JSON objects, so identical timestamps are impossible;
                # the source document never repeats a (bone, channel, time) triple.
                channel_map = OrderedDict()
                for time, value, easing in frames:
                    channel_map[format_time(speed(time))] = OrderedDict(
                        [("vector", vec3(value)), ("easing", easing)]
                    )
                channels[channel] = channel_map
            if channels:
                bones[bone_name] = channels
        if bones:
            entry["bones"] = bones
        animations[name] = entry
    return OrderedDict([("format_version", FORMAT_VERSION_ANIM), ("animations", animations)])


# Every clip plays 10% faster than the source document (user request, 2026-10-06: "动作可以快 10%，
# 同步把攻击判定加快一下"). The factor lives here rather than in the animation controller on purpose: the
# server's release frames and volley times are *read out of this same file*
# (see TopEvokerSkillTiming), so scaling the timeline speeds the performance and the hit windows up
# together and they cannot drift apart. Keyframe times and animation_length are divided by it.
ANIMATION_SPEED = 1.10


def speed(time):
    """Source seconds -> played-back seconds."""
    return time / ANIMATION_SPEED


def format_time(value):
    text = ("%.6f" % float(value)).rstrip("0").rstrip(".")
    return text if text else "0"


# --------------------------------------------------------------------------- verification


def face_pixels(image, u1, v1, u2, v2):
    left, right = sorted((u1, u2))
    top, bottom = sorted((v1, v2))
    width, height = image.size
    left = max(0, min(width - 1, int(left)))
    right = max(0, min(width - 1, int(max(right - 1, left))))
    top = max(0, min(height - 1, int(top)))
    bottom = max(0, min(height - 1, int(max(bottom - 1, top))))
    samples = []
    for ty in {top, (top + bottom) // 2, bottom}:
        for tx in {left, (left + right) // 2, right}:
            samples.append(((tx, ty), image.getpixel((tx, ty))))
    return samples


def verify_faces(doc, sources, offsets, atlas):
    """Re-sample every face before and after the atlas merge; return a report dict."""
    checked = 0
    mismatched = []
    per_texture = {}
    for element in doc.get("elements") or []:
        for face, data in (element.get("faces") or {}).items():
            if not data or not data.get("uv"):
                continue
            texture = data.get("texture", 0)
            if not isinstance(texture, int) or texture < 0 or texture >= len(sources):
                texture = 0
            u1, v1, u2, v2 = [num(v) for v in data["uv"]]
            dx, dy = offsets[texture]
            source_image = sources[texture][1]
            before = face_pixels(source_image, u1, v1, u2, v2)
            after = face_pixels(atlas, u1 + dx, v1 + dy, u2 + dx, v2 + dy)
            checked += 1
            per_texture[texture] = per_texture.get(texture, 0) + 1
            for (pos_before, colour_before), (pos_after, colour_after) in zip(before, after):
                if colour_before != colour_after:
                    mismatched.append(
                        {
                            "element": element.get("name"),
                            "face": face,
                            "texture": texture,
                            "source_px": list(pos_before),
                            "atlas_px": list(pos_after),
                            "before": list(colour_before),
                            "after": list(colour_after),
                        }
                    )
                    break
    return {
        "faces_checked": checked,
        "faces_per_texture": {str(k): v for k, v in sorted(per_texture.items())},
        "faces_mismatched": len(mismatched),
        "mismatches": mismatched[:20],
    }


# --------------------------------------------------------------------------- writing


def write_json(path, payload):
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    text = json.dumps(payload, ensure_ascii=False, indent=1)
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text + "\n")


def count_keyframes(animations):
    total = 0
    for animation in animations["animations"].values():
        for channels in animation.get("bones", {}).values():
            for frames in channels.values():
                total += len(frames)
    return total


# --------------------------------------------------------------------------- main


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--input", required=True, help="the .bbmodel to convert")
    parser.add_argument(
        "--assets",
        required=True,
        help="the mod's assets/stealindims directory (geo/, animations/ and textures/ live here)",
    )
    parser.add_argument("--report", default=None, help="optional path for a JSON conversion report")
    args = parser.parse_args(argv)

    doc = load_document(args.input)
    if doc.get("meta", {}).get("box_uv"):
        raise SystemExit("this converter only handles per-face UV models (box_uv: false)")

    sources = decode_textures(doc)
    atlas, offsets, atlas_size = build_atlas(sources)

    geometry = build_geometry(doc, offsets, atlas_size)
    animations = build_animations(doc)

    geo_path = os.path.join(args.assets, "geo", "entity", "top_evoker.geo.json")
    anim_path = os.path.join(args.assets, "animations", "entity", "top_evoker.animation.json")
    tex_path = os.path.join(args.assets, "textures", "entity", "top_evoker.png")

    os.makedirs(os.path.dirname(tex_path), exist_ok=True)
    atlas.save(tex_path, "PNG")
    write_json(geo_path, geometry)
    write_json(anim_path, animations)

    verification = verify_faces(doc, sources, offsets, atlas)

    bone_count = len(geometry["minecraft:geometry"][0]["bones"])
    cube_count = sum(len(b.get("cubes", [])) for b in geometry["minecraft:geometry"][0]["bones"])
    face_count = sum(
        len(c.get("uv", {})) for b in geometry["minecraft:geometry"][0]["bones"] for c in b.get("cubes", [])
    )
    keyframe_count = count_keyframes(animations)

    report = OrderedDict(
        [
            ("source", os.path.abspath(args.input)),
            ("source_sha256", sha256_file(args.input)),
            ("source_name", doc.get("name")),
            ("model_format", doc.get("meta", {}).get("model_format")),
            ("source_textures", [
                {"name": name, "width": image.width, "height": image.height, "offset": list(offsets[i])}
                for i, (name, image) in enumerate(sources)
            ]),
            ("atlas", {"width": atlas_size[0], "height": atlas_size[1]}),
            ("bones", bone_count),
            ("cubes", cube_count),
            ("faces", face_count),
            ("animations", len(animations["animations"])),
            ("keyframes", keyframe_count),
            ("raw_bones", len(doc.get("groups") or [])),
            ("raw_cubes", len(doc.get("elements") or [])),
            ("raw_keyframes", sum(
                len(a.get("keyframes") or [])
                for anim in doc.get("animations") or []
                for a in (anim.get("animators") or {}).values()
            )),
            ("outputs", {
                "geometry": os.path.abspath(geo_path),
                "animations": os.path.abspath(anim_path),
                "texture": os.path.abspath(tex_path),
            }),
            ("uv_verification", verification),
        ]
    )

    if args.report:
        write_json(args.report, report)

    print("bones      : %d (raw %d)" % (bone_count, report["raw_bones"]))
    print("cubes      : %d (raw %d)" % (cube_count, report["raw_cubes"]))
    print("faces      : %d" % face_count)
    print("animations : %d" % len(animations["animations"]))
    print("keyframes  : %d (raw %d)" % (keyframe_count, report["raw_keyframes"]))
    print("atlas      : %dx%d, offsets %s" % (atlas_size[0], atlas_size[1], offsets))
    print("uv check   : %d faces, %d mismatched" % (
        verification["faces_checked"], verification["faces_mismatched"]))
    print("written    : %s" % geo_path)
    print("             %s" % anim_path)
    print("             %s" % tex_path)

    missing = []
    for key in ("raw_bones", "raw_cubes", "raw_keyframes"):
        if report[key.replace("raw_", "")] != report[key]:
            missing.append(key)
    if bone_count != report["raw_bones"] or cube_count != report["raw_cubes"]:
        raise SystemExit("conversion lost geometry: %s" % missing)
    if keyframe_count != report["raw_keyframes"]:
        raise SystemExit("conversion lost keyframes: %d -> %d" % (report["raw_keyframes"], keyframe_count))
    if verification["faces_mismatched"]:
        raise SystemExit("atlas merge changed %d faces' pixels" % verification["faces_mismatched"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
