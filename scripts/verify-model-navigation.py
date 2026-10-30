#!/usr/bin/env python3
"""Verify a running prediction server: real walking edges and shade partitions.

Input cases are public map coordinates, not a substitute for field observations.
No external provider calls; saves requests/responses and measured HTTP timings.
"""
import argparse
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen


def meters(a, b):
    lat1, lat2 = map(math.radians, (a[1], b[1]))
    dlat, dlon = lat2-lat1, math.radians(b[0]-a[0])
    return 6371008.8*2*math.asin(min(1, math.sqrt(math.sin(dlat/2)**2+math.cos(lat1)*math.cos(lat2)*math.sin(dlon/2)**2)))


def on_edge(p, a, b):
    # All API geometry and source edges are straight WGS84 segments.
    xscale = 111320*math.cos(math.radians(p[1]))
    ax, ay = (a[0]-p[0])*xscale, (a[1]-p[1])*111320
    bx, by = (b[0]-p[0])*xscale, (b[1]-p[1])*111320
    dx, dy = bx-ax, by-ay
    fraction = -(ax*dx+ay*dy)/(dx*dx+dy*dy)
    return -.000001 <= fraction <= 1.000001 and math.hypot(ax+fraction*dx, ay+fraction*dy) < .02


def verify_response(response, edges):
    route = response['routes'][0]
    coordinates = route['geometry']['coordinates']
    for a, b in zip(coordinates, coordinates[1:]):
        if not any(on_edge(a, x, y) and on_edge(b, x, y) for x, y in edges):
            raise ValueError('Route contains an invented source edge')
    sections = route['shadeSegments']
    if not sections or sections[0]['fromDistanceMeters'] != 0:
        raise ValueError('Missing route partition')
    if sections[0]['geometry']['coordinates'][0] != coordinates[0] or sections[-1]['geometry']['coordinates'][-1] != coordinates[-1]:
        raise ValueError('Partition does not reach snapped endpoints')
    for previous, current in zip(sections, sections[1:]):
        if (previous['toDistanceMeters'] != current['fromDistanceMeters'] or
                previous['geometry']['coordinates'][-1] != current['geometry']['coordinates'][0] or previous['state'] == current['state']):
            raise ValueError('Partition has gaps, overlaps or unmerged runs')
    total, shaded = 0, 0
    for section in sections:
        if section['state'] not in ('SHADE', 'SUNLIGHT'):
            raise ValueError('Unknown modeled state')
        points = section['geometry']['coordinates']
        length = sum(meters(a, b) for a, b in zip(points, points[1:]))
        if section['distanceMeters'] <= 0 or abs(length-section['distanceMeters']) > .001:
            raise ValueError('Section distance disagrees with its geometry')
        # Every sample boundary must lie on an original route leg, not just on
        # some other nearby walking edge.
        for p in points:
            if not any(on_edge(p, a, b) for a, b in zip(coordinates, coordinates[1:])):
                raise ValueError('Shade geometry is off the selected route')
        total += section['distanceMeters']
        if section['state'] == 'SHADE':
            shaded += section['distanceMeters']
    if abs(total-route['distanceMeters']) > .051:
        raise ValueError('Partition distance differs from rounded route distance')
    if abs(shaded/total-route['prediction']['modeledShadeRatio']) > 1e-8:
        raise ValueError('Aggregate ratio disagrees with shaded sections')
    if route['prediction']['physicalShadeCoverage'] is not None:
        raise ValueError('Model prediction claimed physical coverage')
    guidance = route.get('guidance')
    if guidance is not None:
        verify_guidance(route)
    return {'distanceMeters': route['distanceMeters'], 'durationSeconds': route['durationSeconds'],
        'modeledShadeRatio': route['prediction']['modeledShadeRatio'], 'shadeSegmentCount': len(sections),
        'shadedMeters': shaded, 'maxSnapMeters': max(response['snapping'][key]['distanceMeters'] for key in ('origin','destination')),
        'guidanceStepCount': len(guidance['steps']) if guidance else None}


def verify_guidance(route):
    guide = route['guidance']
    if guide['status'] != 'AVAILABLE' or guide['language'] != 'ko':
        raise ValueError('Expected available Korean guidance for the real route')
    steps, path = guide['steps'], route['geometry']['coordinates']
    if len(steps) < 2 or steps[0]['type'] != 'DEPART' or steps[-1]['type'] != 'ARRIVE':
        raise ValueError('Missing departure/arrival guidance')
    if steps[0]['geometryIndex'] != 0 or steps[-1]['geometryIndex'] != len(path)-1 or steps[0]['distanceFromStartMeters'] != 0:
        raise ValueError('Guidance endpoints differ from the snapped route')
    for index, step in enumerate(steps):
        if step['index'] != index or not 0 <= step['geometryIndex'] < len(path):
            raise ValueError('Invalid guidance index')
        coordinate = step['coordinate']
        if [coordinate['longitude'], coordinate['latitude']] != path[step['geometryIndex']]:
            raise ValueError('Guidance left the selected route')
        if step['distanceToNextStepMeters'] < 0 or step['durationToNextStepSeconds'] < 0 or not step['instruction'].strip():
            raise ValueError('Invalid guidance cost or missing instruction')
        if index:
            previous = steps[index-1]
            if step['geometryIndex'] <= previous['geometryIndex'] or step['distanceFromStartMeters'] <= previous['distanceFromStartMeters']:
                raise ValueError('Guidance is not in travel order')
            if abs(previous['distanceToNextStepMeters'] - (step['distanceFromStartMeters']-previous['distanceFromStartMeters'])) > 1e-6:
                raise ValueError('Guidance distance contains a gap')
        if guide['metadataStatus'] == 'TOPOLOGY_ONLY' and (step['streetName'] is not None or step['crossing'] is not None):
            raise ValueError('Invented metadata in topology-only guidance')
    if steps[-1]['distanceToNextStepMeters'] != 0 or steps[-1]['durationToNextStepSeconds'] != 0:
        raise ValueError('Arrival has an outgoing leg')
    if abs(sum(s['distanceToNextStepMeters'] for s in steps)-route['distanceMeters']) > .051:
        raise ValueError('Guidance distance differs from the existing route distance')
    seconds = sum(s['durationToNextStepSeconds'] for s in steps)
    if seconds > route['durationSeconds']+1e-6 or route['durationSeconds']-seconds > 1.000001:
        raise ValueError('Guidance time differs from the existing walking time')


def run(args):
    args.output.mkdir(parents=True, exist_ok=True)
    def call(path, payload=None):
        request = Request(args.base_url.rstrip('/')+path, data=None if payload is None else json.dumps(payload).encode(),
                          headers={'Accept':'application/json','Content-Type':'application/json'})
        start = time.monotonic()
        try:
            with urlopen(request, timeout=30) as result:
                return result.status, json.load(result), time.monotonic()-start
        except HTTPError as error:
            return error.code, json.load(error), time.monotonic()-start
    status, coverage, _ = call('/api/v1/routes/predictions/coverage')
    if status != 200:
        raise ValueError('Coverage unavailable')
    (args.output/'coverage.json').write_text(json.dumps(coverage, ensure_ascii=False), encoding='utf-8')
    edges = coverage['walkingNetwork']['coordinates']
    cases = json.loads(args.cases.read_text(encoding='utf-8-sig'))
    if args.bidirectional:
        cases += [dict(case, name=case['name']+' (역방향)', request=dict(case['request'],
            origin=case['request']['destination'], destination=case['request']['origin']))
            for case in list(cases) if case.get('expectedHttp',200)==200]
    results = []
    for index, case in enumerate(cases):
        pair = []
        for mode in ('fastest','shade'):
            request = dict(case['request'], mode=mode)
            status, response, elapsed = call('/api/v1/routes/predictions/search', request)
            stem = f'{index:02}-{mode}'
            for suffix, document in [('request',request),('response',response)]:
                (args.output/f'{stem}-{suffix}.json').write_text(json.dumps(document, ensure_ascii=False, indent=2), encoding='utf-8')
            row = {'case':case['name'],'mode':mode,'http':status,'elapsedSeconds':elapsed}
            row['expectedHttp'] = case.get('expectedHttp', 200)
            if status == 200:
                row.update(verify_response(response, edges))
                if response['dataVersion'] != coverage['dataVersion'] or response['predictionDataVersion'] != coverage['predictionDataVersion']:
                    raise ValueError('Dataset changed during verification')
                pair.append(response)
            else:
                row['code'] = response['code']
                if case.get('expectedCode') and response['code'] != case['expectedCode']:
                    raise ValueError('Unexpected error code')
            print(json.dumps(row, ensure_ascii=False), flush=True)
            results.append(row)
        if len(pair) == 2:
            fastest, shade = (p['routes'][0] for p in pair)
            if pair[0]['snapping'] != pair[1]['snapping']:
                raise ValueError('Fastest/shade compared different snapped endpoints')
            # Displayed times are individually rounded up; the raw optimizer
            # budget is 30%, so allow at most one second of display rounding.
            if shade['durationSeconds'] > fastest['durationSeconds']*1.3+1:
                raise ValueError('Shade route exceeds allowed detour')
            sunlight = lambda r: r['distanceMeters']*(1-r['prediction']['modeledShadeRatio'])
            if sunlight(shade) > sunlight(fastest)+ModelTolerance:
                raise ValueError('Optimized sunlight exposure regressed')
    summary = {'checkedAt':datetime.now(timezone.utc).isoformat(),'coverage':coverage['connectivity'],
        'bounds':coverage['bounds'],'results':results,'allExpectationsMet':all(r['http']==r['expectedHttp'] for r in results)}
    (args.output/'verification.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    if not summary['allExpectationsMet']:
        raise SystemExit('Some map queries failed; inspect verification.json')


ModelTolerance = 1.0  # <= two midpoint samples plus displayed distance rounding
if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--base-url', default='http://127.0.0.1:8080')
    p.add_argument('--cases', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--bidirectional', action='store_true', help='Also verify reverse routes for every success case')
    run(p.parse_args())
