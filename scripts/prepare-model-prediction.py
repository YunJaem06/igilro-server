#!/usr/bin/env python3
"""Prepare a bounded, separate public-3D walking prediction scene.

Original OSM node identities and access tags survive extraction. No synthetic
connections or ACTUAL height transfers. Server ingestion owns walking policy.
"""
import argparse
import base64
import copy
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import struct
import xml.etree.ElementTree as ET
from building_sources import settings
from vworld_spatial import SpatialClient, TILE_ROOTS, collect_model_tiles, korean_bounds, model_footprints, sample_dem, tile_indices


def surface_way(tags):
    for key in ('bridge', 'tunnel', 'covered', 'indoor'):
        if tags.get(key, '').strip().lower() not in ('', 'no', 'false', '0'):
            return False
    return (all(tags.get(k, '').strip() in ('', '0', '+0') for k in ('layer', 'level'))
            and tags.get('location', '') not in ('underground', 'elevated', 'rooftop')
            and tags.get('highway') != 'steps')


def extract_surface_roads(payload, bounds):
    root = ET.fromstring(payload)
    nodes = {n.attrib['id']: n for n in root.findall('node')}
    def inside(n):
        return bounds[0] <= float(n.attrib['lon']) <= bounds[2] and bounds[1] <= float(n.attrib['lat']) <= bounds[3]
    ways, excluded = [], []
    for way in root.findall('way'):
        refs = [n.attrib['ref'] for n in way.findall('nd')]
        if any(r not in nodes for r in refs):
            raise ValueError('Missing source road references')
        if not any(inside(nodes[r]) for r in refs):
            continue
        tags = {t.attrib['k']: t.attrib['v'] for t in way.findall('tag')}
        if surface_way(tags):
            ways.append(way)
        else:
            excluded.append(way.attrib['id'])
    refs = {n.attrib['ref'] for way in ways for n in way.findall('nd')}
    result = ET.Element('osm', version='0.6', generator='igilro-model-surface-extract-v2')
    meta = root.find('meta')
    if meta is None or not meta.attrib.get('osm_base'):
        raise ValueError('Missing original OSM snapshot timestamp')
    result.append(copy.deepcopy(meta))
    for n in sorted(refs, key=int):
        result.append(copy.deepcopy(nodes[n]))
    for way in ways:
        result.append(copy.deepcopy(way))
    return ET.tostring(result, encoding='utf-8', xml_declaration=True), excluded


def projection_fit(bounds):
    import numpy as np
    from pyproj import Transformer
    lon, lat = (bounds[0]+bounds[2])/2, (bounds[1]+bounds[3])/2
    def terms(x, y):
        a, b = x-lon, y-lat
        return [a, b, 1, a*a, a*b, b*b]
    project = Transformer.from_crs(4326, 5179, always_xy=True)
    fit = [(x, y) for x in np.linspace(bounds[0], bounds[2], 9) for y in np.linspace(bounds[1], bounds[3], 9)]
    coef = np.linalg.lstsq(np.array([terms(x, y) for x, y in fit]), np.array([project.transform(x, y) for x, y in fit]), rcond=None)[0]
    error = max(float(np.linalg.norm(np.array(terms(x, y))@coef - np.array(project.transform(x, y))))
                for x in np.linspace(bounds[0], bounds[2], 33) for y in np.linspace(bounds[1], bounds[3], 33))
    if error > .05:
        raise ValueError('Local projection approximation exceeds 5 cm')
    return {'origin': [lon, lat], 'coefficients': coef.tolist(), 'checkedMaxErrorMeters': error, 'targetCrs': 'EPSG:5179'}


def run(args):
    if (args.output/'scene.json').exists():
        raise ValueError('Use a fresh output folder')
    from shapely.geometry import shape, box
    if args.pilot_audit:
        audit = json.loads(args.pilot_audit.read_text(encoding='utf-8'))
        bounds = korean_bounds(audit['graphPreview']['bounds'])
        if hashlib.sha256(args.roads.read_bytes()).hexdigest() != audit['graphPreview']['sha256']:
            raise ValueError('Preview road checksum mismatch')
        area_id = audit['graphPreview']['areaId']
    else:
        bounds = korean_bounds(args.bounds)
        area_id = args.area_id
    buffer = 150
    lat = (bounds[1]+bounds[3])/2
    dx, dy = buffer/(111320*math.cos(math.radians(lat))), buffer/110540
    scene_bounds = korean_bounds([bounds[0]-dx, bounds[1]-dy, bounds[2]+dx, bounds[3]+dy])
    projection = projection_fit(scene_bounds)
    roads, excluded = extract_surface_roads(args.roads.read_bytes(), bounds)
    client = SpatialClient(settings(), args.cache, offline=args.offline, budget=5000)
    terrain = {}
    indices = tile_indices(scene_bounds, limit=512)
    for index, tile in enumerate(indices):
        terrain[tile] = client.terrain(*tile)
        if index % 25 == 0:
            print(json.dumps({'stage': 'terrain', 'completed': index+1, 'total': len(indices)}), flush=True)
    def elevation(x, y):
        size = 36/2**15
        ix, iy = math.floor((x+180)/size), math.floor((y+90)/size)
        values = terrain.get((15, ix, iy))
        if values is None:
            raise ValueError('Model base is outside collected terrain')
        return sample_dem(values, 15, ix, iy, x, y)
    tiles, documents = collect_model_tiles(client, TILE_ROOTS['lod1'], scene_bounds, max_documents=128, max_tiles=4096)
    print(json.dumps({'stage': 'model-geometry', 'tiles': len(tiles)}), flush=True)
    models, omitted = [], []
    for index, tile in enumerate(tiles):
        for feature in model_footprints(client.content(tile['url'])):
            polygon = shape(feature['geometry'])
            if not polygon.intersects(box(*scene_bounds)):
                continue
            height = feature['properties'].get('modelHeightAttributeMeters')
            identifier = f"vworld:lod1:{tile['sha256']}:{feature['id']}"
            if isinstance(height, bool) or not isinstance(height, (int, float)) or not 0 < height <= 500:
                omitted.append(identifier)
                continue
            point = polygon.representative_point()
            try:
                base = elevation(point.x, point.y)
            except ValueError:
                omitted.append(identifier)
                continue
            g = feature['geometry']
            models.append({'id': identifier, 'heightMeters': height, 'heightSource': 'VWORLD_MODEL_ATTRIBUTE',
                'baseElevationMeters': base, 'baseMethod': 'DEM_AT_MODEL_INTERIOR_POINT',
                'polygons': g['coordinates'] if g['type'] == 'MultiPolygon' else [g['coordinates']],
                'sourceUrl': tile['url'], 'sourceSha256': tile['sha256'], 'sourceModelId': feature['id'],
                'sourceObservedAt': next(o['observedAt'] for o in client.observations if o['sha256'] == tile['sha256'])})
        if index % 10 == 0:
            print(json.dumps({'stage': 'model-geometry', 'completed': index+1, 'models': len(models)}), flush=True)
    if not models or len(models) > 8000 or len({m['id'] for m in models}) != len(models):
        raise ValueError('Missing, duplicate or excessive source models')
    access_points = json.loads(args.access_points.read_text(encoding='utf-8')) if args.access_points else []
    root = {'schemaVersion': 2, 'kind': 'PUBLIC_3D_MODEL_PREDICTION_ONLY', 'observedAt': datetime.now(timezone.utc).isoformat(),
        'graph': {'areaId': area_id, 'areaName': args.area_name, 'bounds': bounds, 'roadsFile': 'roads.osm',
                  'roadsSha256': hashlib.sha256(roads).hexdigest(), 'componentPolicy': 'LARGEST_CONNECTED_COMPONENT'},
        'sceneBounds': scene_bounds, 'sceneBufferMetersApprox': buffer, 'sceneComplete': False,
        'projection': projection,
        'terrain': [{'level': z, 'x': x, 'y': y, 'float32leBase64': base64.b64encode(struct.pack('<4225f', *values)).decode('ascii')}
                    for (z, x, y), values in terrain.items()],
        'buildings': models, 'omittedModelIds': omitted, 'modelVersion': 'public-3d-prism-dem-v2-step0.5m',
        'accessPoints': access_points,
        'attribution': 'VWorld public 3D model and terrain / OpenStreetMap contributors; source-specific terms apply',
        'limitations': ['MODEL_HEIGHTS_ARE_NOT_ACTUAL_HEIGHTS', 'PARTIAL_PROVIDER_SCENE', 'CAPTURE_DATE_UNCONFIRMED',
            'DEM_BASE_AT_ONE_INTERIOR_POINT', 'VERTICAL_REFERENCE_ASSUMED_CONSISTENT', 'STRUCTURE_TAG_SCREENING_ONLY',
            'TREES_CLOUDS_AND_TERRAIN_HORIZON_NOT_MODELED', 'FIXED_DEPARTURE_SUN',
            'MIDPOINT_SAMPLING_NOT_EXACT_SHADOW_BOUNDARY', 'SCENE_BUFFER_150M_DISTANT_CASTERS_MAY_BE_MISSING'],
        'observations': list({json.dumps(o['identity'], sort_keys=True): o for o in client.observations}.values()),
        'sourceDocuments': documents,
        'roadExtraction': {'sourceSha256': hashlib.sha256(args.roads.read_bytes()).hexdigest(), 'excludedStructureWayIds': excluded}}
    encoded = json.dumps(root, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
    if len(encoded) > 32_000_000:
        raise ValueError('Scene exceeds 32 MB')
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output/'roads.osm').write_bytes(roads)
    (args.output/'scene.json').write_bytes(encoded)
    print(json.dumps({'models': len(models), 'omitted': len(omitted), 'terrainTiles': len(terrain),
        'projectionErrorMeters': projection['checkedMaxErrorMeters'], 'requests': client.request_count, 'bytes': len(encoded), 'actualHeightTransfers': 0}), flush=True)


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    scope = p.add_mutually_exclusive_group(required=True)
    scope.add_argument('--pilot-audit', type=Path)
    scope.add_argument('--bounds', type=float, nargs=4)
    p.add_argument('--area-id', default='seoul-forest-navigation')
    p.add_argument('--area-name', default='서울숲 산책로와 주변 출입구')
    p.add_argument('--access-points', type=Path)
    p.add_argument('--roads', type=Path, required=True)
    p.add_argument('--cache', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--offline', action='store_true')
    run(p.parse_args())
