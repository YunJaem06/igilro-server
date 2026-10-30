#!/usr/bin/env python3
"""Reproducible public-source terrain/3D cross-check for a small walking pilot.

This command writes evidence only. No DB mutations, inferred ACTUAL heights,
ground qualification, or activation of model datasets occur here.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import xml.etree.ElementTree as ET

from building_sources import settings, SourceError
from vworld_spatial import (SpatialClient, TILE_ROOTS, SDK_CONFIG, SDK_TERRAIN,
    korean_bounds, tile_indices, sample_dem, collect_model_tiles, model_footprints)


def digest(file): return hashlib.sha256(Path(file).read_bytes()).hexdigest()


def compare_buildings(gis, models):
    from pyproj import Transformer
    from shapely.geometry import shape
    from shapely.ops import transform
    project=Transformer.from_crs(4326,5179,always_xy=True).transform
    prepared=[(feature,transform(project,shape(feature['geometry']))) for feature in models]
    results=[]
    for feature in gis:
        polygon=transform(project,shape(feature['geometry']))
        if not polygon.is_valid or polygon.area<=0: raise ValueError('Invalid GIS geometry')
        matches=[]
        for model,other in prepared:
            if not polygon.intersects(other):continue
            intersection=polygon.intersection(other).area
            if intersection<.01:continue
            p=model['properties']
            matches.append({'modelId':model['id'],'gisCovered':intersection/polygon.area,
                'modelCovered':intersection/other.area,'iou':intersection/polygon.union(other).area,
                'modelHeightAttributeMeters':p.get('modelHeightAttributeMeters'),
                'meshMinEllipsoidHeightMeters':p['meshMinEllipsoidHeightMeters'],
                'meshMaxEllipsoidHeightMeters':p['meshMaxEllipsoidHeightMeters'],
                'modelPnu':p.get('PNU_NO'),'modelUseDate':p.get('USECON_DE'),
                'tileUrl':p['tileUrl'],'tileSha256':p['tileSha256']})
        matches.sort(key=lambda row:row['iou'],reverse=True)
        props=feature['properties']
        for match in matches:
            candidate=match['modelHeightAttributeMeters']
            direct=props.get('heightMeters') if props.get('heightSource')=='ACTUAL' else None
            match['differenceFromDirectHeightMeters']=candidate-direct if isinstance(candidate,(int,float)) and isinstance(direct,(int,float)) else None
            match['modelIsDirectHeight']=False
        results.append({'gisId':feature['id'],'gisSourceId':props['sourceId'],
            'heightMeters':props.get('heightMeters'),'heightSource':props.get('heightSource'),
            'matches':matches[:3],
            'decision':'REVIEW_CANDIDATE' if matches else 'NO_MODEL_OVERLAP',
            'heightTransferred':False})
    return results


def run(args):
    from shapely.geometry import shape, box, LineString
    from pyproj import Transformer
    pilot=json.loads(args.pilot_audit.read_text(encoding='utf-8'))
    bounds=korean_bounds(pilot['graphPreview']['bounds'])
    if len(pilot['groundStations'])>100:raise ValueError('Station budget exceeded')
    buildings=json.loads(args.buildings.read_text(encoding='utf-8'))
    if len(buildings['features'])>25000:raise ValueError('Building budget exceeded')
    source_geometry=box(*bounds)
    selected=[f for f in buildings['features'] if shape(f['geometry']).intersects(source_geometry)]
    args.output.mkdir(parents=True,exist_ok=True)
    client=SpatialClient(settings(),args.cache or args.output/'responses',offline=args.offline,budget=80)
    terrain={tile:client.terrain(*tile) for tile in tile_indices(bounds)}
    def elevation(lon,lat):
        size=36/2**15;x=math.floor((lon+180)/size);y=math.floor((lat+90)/size)
        return sample_dem(terrain[(15,x,y)],15,x,y,lon,lat)
    stations=[]
    for f in pilot['groundStations']:
        lon,lat=f['geometry']['coordinates']
        stations.append({'coordinate':[lon,lat],'modelElevationMeters':elevation(lon,lat),'groundVerified':False})
    ground_references=[]
    for feature in selected:
        center=shape(feature['geometry']).centroid
        size=36/2**15;tile=(15,math.floor((center.x+180)/size),math.floor((center.y+90)/size))
        ground_references.append({'gisSourceId':feature['properties']['sourceId'],
            'coordinate':[center.x,center.y],
            'terrainModelElevationMeters':elevation(center.x,center.y) if tile in terrain else None,
            'kind':'DEM_AT_FOOTPRINT_CENTROID_ONLY',
            'buildingBaseElevationVerified':False})
    (args.output/'building-ground-references.json').write_text(json.dumps(ground_references,ensure_ascii=False,indent=2),encoding='utf-8')
    models={};model_audits={}
    for lod,url in TILE_ROOTS.items():
        tiles,documents=collect_model_tiles(client,url,bounds)
        features=[]
        for tile in tiles:
            for f in model_footprints(client.content(tile['url'])):
                f['properties']['sourceModelId']=f['id']
                f['id']=f"vworld:{lod}:{tile['sha256']}:{f['id']}"
                f['properties'].update({'tileUrl':tile['url'],'tileSha256':tile['sha256']})
                if shape(f['geometry']).intersects(source_geometry):features.append(f)
        if len({f['id'] for f in features})!=len(features):raise ValueError('Duplicate source model')
        models[lod]=features
        model_audits[lod]={'tiles':len(tiles),'sourceModels':sum(t['batchLength'] for t in tiles),
                          'intersectingModels':len(features),'documents':documents}
        (args.output/f'{lod}-footprints.geojson').write_text(json.dumps({'type':'FeatureCollection','features':features},ensure_ascii=False),encoding='utf-8')
        (args.output/f'{lod}-building-matches.json').write_text(json.dumps(compare_buildings(selected,features),ensure_ascii=False,indent=2),encoding='utf-8')
    # Elevation profiles keep both OSM structure tags and source-only limitations.
    osm=ET.parse(args.roads_osm).getroot()
    eligible=set()
    if args.eligible_roads_osm:
        if digest(args.eligible_roads_osm)!=pilot['graphPreview']['sha256']:raise ValueError('Graph preview checksum mismatch')
        eligible={w.attrib['id'] for w in ET.parse(args.eligible_roads_osm).getroot().findall('way')}
    nodes={n.attrib['id']:(float(n.attrib['lon']),float(n.attrib['lat'])) for n in osm.findall('node')}
    if osm.find('meta') is None:raise ValueError('Missing OSM snapshot')
    project=Transformer.from_crs(4326,5179,always_xy=True)
    inverse=Transformer.from_crs(5179,4326,always_xy=True)
    projected_bounds=shape({'type':'Polygon','coordinates':[[[bounds[0],bounds[1]],[bounds[2],bounds[1]],[bounds[2],bounds[3]],[bounds[0],bounds[3]],[bounds[0],bounds[1]]]]})
    from shapely.ops import transform
    clip=transform(project.transform,projected_bounds)
    profiles=[]
    for way in osm.findall('way'):
        refs=[n.attrib['ref'] for n in way.findall('nd')]
        if any(r not in nodes for r in refs):raise ValueError('Missing road source node')
        points=[project.transform(*nodes[r]) for r in refs]
        tags={t.attrib['k']:t.attrib['v'] for t in way.findall('tag')}
        if len(points)<2 or not tags.get('highway'):continue
        line=LineString(points).intersection(clip)
        lines=[line] if line.geom_type=='LineString' else list(line.geoms) if line.geom_type=='MultiLineString' else []
        for part in lines:
            if part.length<.1:continue
            count=math.ceil(part.length/5);samples=[]
            for i in range(count+1):
                point=part.interpolate(part.length*i/count);lon,lat=inverse.transform(point.x,point.y)
                samples.append({'distanceMeters':part.length*i/count,'coordinate':[lon,lat],'modelElevationMeters':elevation(lon,lat)})
            heights=[s['modelElevationMeters'] for s in samples]
            profiles.append({'sourceWayId':way.attrib['id'],'tags':tags,'lengthMeters':part.length,
                'elevationRangeMeters':max(heights)-min(heights),'samples':samples,'groundVerified':False,
                'includedInGraphPreview':way.attrib['id'] in eligible if args.eligible_roads_osm else None})
    (args.output/'road-terrain-profiles.json').write_text(json.dumps(profiles,ensure_ascii=False,indent=2),encoding='utf-8')
    report={'observedAt':datetime.now(timezone.utc).isoformat(),'bounds':bounds,
        'inputs':{'pilotAuditSha256':digest(args.pilot_audit),'buildingsSha256':digest(args.buildings),'roadsSha256':digest(args.roads_osm),
                  'eligibleRoadsSha256':digest(args.eligible_roads_osm) if args.eligible_roads_osm else None},
        'sourceReferences':[SDK_CONFIG,SDK_TERRAIN,*TILE_ROOTS.values()],
        'horizontalCrs':{'inputs':'EPSG:4326','analysis':'EPSG:5179','modelVertices':'EPSG:4978'},
        'terrain':{'level':15,'tileCount':len(terrain),'gridSamplesPerTile':[65,65],
                   'nominalSampleSpacingMetersApprox':[36/2**15*111320*math.cos(math.radians(bounds[1]))/64,36/2**15*110540/64],
                   'verticalDatum':'UNCONFIRMED','stations':stations,'sourceAcquisitionYear':'UNCONFIRMED',
                   'roadProfiles':len(profiles)},
        'modelAudits':model_audits,'gisBuildingsIntersectingScope':len(selected),
        'requestCount':client.request_count,'observations':client.observations,
        'heightTransfers':0,'inventoryComplete':False,'maximumHeightMeters':None,'groundVerified':False,
        'readyForShadeRouting':False,
        'limitations':['SOURCE_MODEL_HEIGHT_IS_NOT_ACTUAL_HEIGHT','MESH_Z_EXTENT_IS_NOT_BUILDING_HEIGHT',
            'VERTICAL_REFERENCE_UNCONFIRMED','MODEL_CAPTURE_DATE_UNCONFIRMED',
            'TERRAIN_IS_NOT_BRIDGE_OR_TUNNEL_EVIDENCE','MODEL_AND_GIS_FOOTPRINTS_DIFFER',
            'FLAT_GROUND_MODEL_HAS_NOT_BEEN_QUALIFIED',
            'NO_AUTOMATIC_DATASET_ACTIVATION']}
    (args.output/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'terrainTiles':len(terrain),'modelCounts':{k:len(v) for k,v in models.items()},
                      'roadProfiles':len(profiles),'networkRequests':client.request_count,'readyForShadeRouting':False}))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pilot-audit',type=Path,required=True)
    parser.add_argument('--buildings',type=Path,required=True)
    parser.add_argument('--roads-osm',type=Path,required=True)
    parser.add_argument('--eligible-roads-osm',type=Path,help='Original-tag-preserving graph preview; checksum must match pilot audit')
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--cache',type=Path)
    parser.add_argument('--offline',action='store_true')
    args=parser.parse_args()
    if (args.output/'report.json').exists():parser.error('Use a new report directory; previous observations are immutable')
    try:run(args)
    except (SourceError,ValueError) as error:raise SystemExit(str(error)) from None


if __name__=='__main__': main()
