#!/usr/bin/env python3
"""Collect evidence for a small real pilot; never approve or activate unverified data."""
import argparse
from collections import Counter
import copy
from datetime import datetime, timezone
import hashlib
import html
import importlib.util
import json
import math
from pathlib import Path
import re
from urllib.parse import urlparse
import xml.etree.ElementTree as ET

from building_sources import (PublicBuildingClient, SourceError, collect_gis, collect_hub_parcels,
    settings, source_id, match_heights, web_mercator)


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def structure_blockers(tags):
    blockers=[]
    for key in ("bridge", "tunnel", "covered", "indoor"):
        if tags.get(key, "").strip().lower() not in {"", "no", "false", "0"}:
            blockers.append(key)
    for key in ("layer", "level"):
        if tags.get(key, "").strip() not in {"", "0", "+0"}: blockers.append(key)
    if tags.get("location", "") in {"underground", "elevated", "rooftop"}: blockers.append("location")
    if tags.get("highway") == "steps": blockers.append("steps")
    return blockers


def subset_roads(root, way_ids):
    """Retain complete original ways/references/tags; the server applies the explicit bounds later."""
    selected=[way for way in root.findall("way") if int(way.attrib["id"]) in way_ids]
    if {int(way.attrib["id"]) for way in selected} != set(way_ids):
        raise ValueError("Missing source way")
    if any(structure_blockers({tag.attrib['k']:tag.attrib['v'] for tag in way.findall('tag')}) for way in selected):
        raise ValueError("Unsupported structure in proposed graph")
    refs={int(node.attrib["ref"]) for way in selected for node in way.findall("nd")}
    nodes=[node for node in root.findall("node") if int(node.attrib["id"]) in refs]
    if {int(node.attrib["id"]) for node in nodes} != refs: raise ValueError("Missing referenced node")
    result=ET.Element("osm", {"version":"0.6", "generator":"igilro-pilot-review"})
    meta=root.find("meta")
    if meta is None or not meta.attrib.get("osm_base"): raise ValueError("Missing original snapshot timestamp")
    result.append(copy.deepcopy(meta))
    for item in nodes+selected: result.append(copy.deepcopy(item))
    return ET.tostring(result, encoding="utf-8", xml_declaration=True)


def field_html(report, buildings, osm_buildings):
    """Offline, schematic field sheet; geography is for locating evidence, never scoring shade."""
    lon,lat=report['groundStations'][0]['geometry']['coordinates']
    def xy(point): return ((point[0]-lon)*111320*math.cos(math.radians(lat)), -(point[1]-lat)*110540)
    def shape(geometry):
        polygons=geometry['coordinates'] if geometry['type']=='MultiPolygon' else [geometry['coordinates']]
        return ' '.join('M'+' L'.join(f'{x:.3f},{y:.3f}' for x,y in map(xy,ring))+' Z' for polygon in polygons for ring in polygon)
    focus=[xy(station['geometry']['coordinates']) for station in report['groundStations']]
    school_ids={row['sourceId'] for row in report['freshBuildingsWithin200m'] if row['pnu']=='1120011400106850143'}
    for feature in buildings['features']:
        if feature['properties']['sourceId'] not in school_ids: continue
        geometry=feature['geometry']; polygons=geometry['coordinates'] if geometry['type']=='MultiPolygon' else [geometry['coordinates']]
        focus.extend(xy(point) for polygon in polygons for ring in polygon for point in ring)
    west,east=min(x for x,y in focus),max(x for x,y in focus)
    north,south=min(y for x,y in focus),max(y for x,y in focus)
    side=max(east-west,south-north,120)+60
    left,top=(west+east-side)/2,(north+south-side)/2
    pieces=[]
    for feature in buildings['features']:
        known=feature['properties'].get('heightSource') in {'ACTUAL','OSM'} and feature['properties'].get('heightMeters') is not None
        pieces.append(f'<path d="{shape(feature["geometry"])}" fill="{"#bee2cd" if known else "#f6e1b3"}" stroke="#b6b9b3" stroke-width=".4" fill-rule="evenodd"/>')
    for road in report['roads']+report['excludedRoads']:
        points=' '.join(f'{x:.3f},{y:.3f}' for x,y in map(xy,road['geometry']['coordinates']))
        color='#d04b43' if road['structureBlockers'] else '#6f7d84'
        pieces.append(f'<polyline points="{points}" fill="none" stroke="{color}" stroke-width="1.3"/>')
    for index,row in enumerate(report['osmWithin100m'],1):
        feature=next((f for f in osm_buildings['features'] if f['id']==row['id']),None)
        if feature is not None and row['gisFootprintCoverage']<.95:
            pieces.append(f'<path d="{shape(feature["geometry"])}" fill="none" stroke="#a43d9b" stroke-width="1.4" stroke-dasharray="3,2"/>')
    stations=[]
    for station in report['groundStations']:
        point=station['geometry']['coordinates']; x,y=xy(point); n=station['properties']['station']
        pieces.append(f'<circle cx="{x:.3f}" cy="{y:.3f}" r="2.2" fill="#1760a2"/><text x="{x+4:.3f}" y="{y+2:.3f}" font-size="5" fill="#114b80">A{n}</text>')
        stations.append(f'<tr><td>A{n}</td><td>{point[0]:.7f}</td><td>{point[1]:.7f}</td></tr>')
    for road in report['excludedRoads']:
        x,y=xy(road['geometry']['coordinates'][0]); pieces.append(f'<text x="{x:.3f}" y="{y-4:.3f}" font-size="5" fill="#b63232">지하 통로 · 제외</text>')
    pieces.append(f'<rect x="{left+12:.3f}" y="{top+side-18:.3f}" width="50" height="1.5" fill="#233b30"/><text x="{left+12:.3f}" y="{top+side-7:.3f}" font-size="6">약 50m · 위치 안내용 투영</text>')
    pieces.append(f'<text x="{left+side-25:.3f}" y="{top+15:.3f}" font-size="6">북 ↑</text>')
    school=[]
    for row in report['freshBuildingsWithin200m']:
        if row['pnu']!='1120011400106850143': continue
        number=len(school)+1
        feature=next((f for f in buildings['features'] if f['properties']['sourceId']==row['sourceId']),None)
        if feature:
            geometry=feature['geometry']; ring=geometry['coordinates'][0][0] if geometry['type']=='MultiPolygon' else geometry['coordinates'][0]
            x,y=xy([sum(p[0] for p in ring)/len(ring),sum(p[1] for p in ring)/len(ring)])
            pieces.append(f'<text x="{x:.3f}" y="{y:.3f}" font-size="7" font-weight="bold">B{number}</text>')
        value=row['match']['heightMeters']; height=f'{value:g}m' if value is not None else '높이 미확인'
        school.append(f'<tr><td>B{number}</td><td>{html.escape(row["dongName"] or row["name"] or "이름 없음")}</td><td>{height}</td></tr>')
    return '''<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'">
<title>서울숲 50m 현장 확인 구간</title><style>
body{font-family:system-ui,sans-serif;background:#f5f7f4;color:#233b30;margin:0;padding:24px}main{max-width:1200px;margin:auto}
h1{font-size:28px;margin:4px 0 10px}.notice{background:#fff1cd;padding:16px;border-radius:10px;font-weight:600}
.grid{display:grid;grid-template-columns:1.3fr 1fr;gap:20px;margin-top:20px}.card{background:white;padding:20px;border:1px solid #dfe6dd;border-radius:12px}
svg{display:block;width:100%;background:#fbfcfa;border:1px solid #e1e6df}table{width:100%;border-collapse:collapse;font-size:13px}td,th{text-align:left;padding:8px 4px;border-bottom:1px solid #e4e9e2}
p,li{line-height:1.65}small{color:#5e6a62}h2{font-size:18px}code{font-size:12px}a{color:#1760a2}@media(max-width:850px){.grid{grid-template-columns:1fr}body{padding:12px}}
</style><main><small>이길로 · 2026-10-27 공공 원본 대조</small><h1>서울숲 50m 현장 확인 구간</h1>
<div class="notice">검토 대기 · 실제 그늘 경로 검색 미개통<br>이 지도는 원본 대조용이며 지상·평탄 여부나 건물 목록의 완전성을 승인한 결과가 아닙니다.</div>
<div class="grid"><section class="card"><h2>성수중·고등학교 서쪽 보도</h2><svg viewBox="'''+f'{left:.3f} {top:.3f} {side:.3f} {side:.3f}'+'''" role="img" aria-label="공공 건물과 OSM 도형, A0부터 A5까지 약 10m 간격의 현장 확인 지점, 제외된 지하 통로">'''+''.join(pieces)+'''</svg>
<p><small>초록: 직접 높이가 있는 공공 건물 · 노랑: 높이 미확인 · 보라 점선: OSM 형상 재대조 · 파랑 A0–A5: 확인 지점 · 빨강: 제외한 지하 통로. 배경 항공사진 없이 원본 좌표로 그린 약도입니다. 나무 그늘은 계산 대상에 포함하지 않습니다.</small></p>
</section><section class="card"><h2>현장 확인 위치 · WGS84</h2><table><tr><th>지점</th><th>경도</th><th>위도</th></tr>'''+''.join(stations)+'''
</table><h2>각 지점에서 기록할 내용</h2><ol><li>지상 보도인지, 고가·데크·지하·터널·실내 구간인지 확인합니다.</li>
<li>앞뒤 방향 사진과 계단·경사·통행 차단·현재 공사 여부, 위치·관찰 시각을 기록합니다. 구간 사이의 연결도 확인합니다.</li>
<li>학교 쪽 건물 외곽이 약도와 다른 곳은 사진과 위치를 남깁니다. 사진만으로 높이를 추정하지 않습니다.</li></ol>
<h2>학교 건물 직접 높이 대조</h2><table><tr><th>ID</th><th>건물</th><th>높이</th></tr>'''+''.join(school)+'''
</table><p>건물 높이는 공공 원본 값입니다. 현장 실측과 구분합니다. 정보센터의 높이·도면과 OSM 도형 두 곳의 식별 근거가 추가로 필요합니다.</p>
<p><small>출처: 국토교통부 GIS건물통합정보(브이월드), 건축HUB 건축물대장정보, © OpenStreetMap contributors. 현재 실제 데이터의 그늘 비율은 null이며 주변 높이 상한도 미확인입니다.</small></p></section></div></main></html>'''


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan",type=Path,required=True)
    parser.add_argument("--snapshot",type=Path,required=True)
    parser.add_argument("--roads-osm",type=Path,required=True)
    parser.add_argument("--osm-buildings",type=Path,required=True)
    parser.add_argument("--candidate",default="seoul-forest-candidate-1")
    parser.add_argument("--graph-radius",type=int,default=100,help="Preview half-width 100..250m; never a verification or shadow bound")
    parser.add_argument("--output",type=Path,required=True)
    parser.add_argument("--offline",action="store_true")
    parser.add_argument("--response-cache",type=Path)
    args=parser.parse_args()
    if not 100<=args.graph_radius<=250: parser.error("Graph radius must be 100..250m")
    if args.output.exists(): parser.error("Choose a new output directory")
    plan=json.loads(args.plan.read_text(encoding="utf-8"))
    snapshot=json.loads((args.snapshot/"buildings.validated.geojson").read_text(encoding="utf-8"))
    version="buildings-v1-"+digest(args.snapshot/"buildings.validated.geojson")
    if plan["publicVersion"]!=version or plan["roadSourceSha256"]!=digest(args.roads_osm) or plan['osmVersion']!='buildings-v1-'+digest(args.osm_buildings):
        parser.error("Plan/source snapshot mismatch")
    candidate=next((row for row in plan["candidates"] if row["id"]==args.candidate),None)
    if candidate is None: parser.error("Unknown candidate")
    config=settings()
    url=urlparse(config.get("DB_URL","jdbc:postgresql://127.0.0.1:5432/igilro").removeprefix("jdbc:"))
    if url.scheme!="postgresql" or url.hostname not in {"localhost","127.0.0.1"}: parser.error("Local DB only")
    args.output.mkdir(parents=True)
    client=PublicBuildingClient(config,args.response_cache or args.output/"responses",offline=args.offline)
    lon,lat=candidate["longitude"],candidate["latitude"]
    # 200m source refresh window; the older 500m inspection remains separately labelled.
    dx=200/(111000*math.cos(math.radians(lat))); dy=200/111000
    bbox=[lon-dx,lat-dy,lon+dx,lat+dy]
    import psycopg
    try:
        with psycopg.connect(host=url.hostname,port=url.port or 5432,dbname=url.path.lstrip("/"),
            user=config.get("DB_USERNAME",config.get("POSTGRES_USER","igilro")),
            password=config.get("DB_PASSWORD",config.get("POSTGRES_PASSWORD","igilro"))) as connection:
            with connection.cursor() as cursor:
                cursor.execute("SET LOCAL statement_timeout='30s'")
                cursor.execute("SELECT active_dataset_id FROM route_service_area WHERE id='seoul-forest'")
                if cursor.fetchone()!=(plan["graphVersion"],): raise ValueError("Active graph mismatch")
                cursor.execute("SELECT active_dataset_id FROM building_shade_area WHERE area_id='seoul-forest'")
                if cursor.fetchone()!=(version,): raise ValueError("Active buildings mismatch")
                cursor.execute("""SELECT id FROM building_shade_footprint WHERE dataset_id=%s AND
                    ST_DWithin(footprint,ST_SetSRID(ST_MakePoint(%s,%s),5179),500)""",(version,candidate["x"],candidate["y"]))
                local_ids={row[0] for row in cursor.fetchall()}
                features=[feature for feature in snapshot["features"] if feature["id"] in local_ids]
                if len(features)!=len(local_ids): raise ValueError("Missing local source features")
                root=ET.parse(args.roads_osm).getroot()
                ways={int(way.attrib["id"]):way for way in root.findall("way")}
                # A bounded preview graph. No inventing nodes or straight connections at the boundary.
                gx=args.graph_radius/(111000*math.cos(math.radians(lat))); gy=args.graph_radius/111000
                bounds=[lon-gx,lat-gy,lon+gx,lat+gy]
                cursor.execute("""SELECT e.id,e.osm_way_id,e.from_node,e.to_node,e.distance_meters,ST_AsGeoJSON(e.geometry)
                    FROM route_edge e WHERE e.dataset_id=%s AND ST_CoveredBy(e.geometry,ST_MakeEnvelope(%s,%s,%s,%s,4326))
                    ORDER BY e.id""",(plan["graphVersion"],*bounds))
                roads=[]
                excluded=[]
                for edge,way,from_node,to_node,distance,geometry in cursor.fetchall():
                    if way not in ways: raise ValueError("Graph way missing from source")
                    tags={tag.attrib["k"]:tag.attrib["v"] for tag in ways[way].findall("tag")}
                    record={"edgeId":edge,"osmWayId":way,"fromNode":from_node,"toNode":to_node,
                        "distanceMeters":distance,"geometry":json.loads(geometry),"tags":tags,
                        "structureBlockers":structure_blockers(tags),"groundVerified":False}
                    (excluded if record["structureBlockers"] else roads).append(record)
                if not roads: raise ValueError("No source roads in bounded preview")
                gis,gis_requests=collect_gis(client,bbox)
                # Refresh only the school parcel and at most three nearest unresolved parcels.
                raw={source_id(feature):feature for feature in gis.values()}
                cursor.execute("CREATE TEMP TABLE pilot_gis (id text,props jsonb,geom geometry) ON COMMIT DROP")
                for identifier,feature in raw.items():
                    cursor.execute("INSERT INTO pilot_gis VALUES (%s,%s,ST_Transform(ST_SetSRID(ST_GeomFromGeoJSON(%s),3857),5179))",
                        (identifier,json.dumps(feature["properties"]),json.dumps(feature["geometry"])))
                cursor.execute("SELECT count(*) FROM pilot_gis WHERE NOT ST_IsValid(geom) OR ST_IsEmpty(geom)")
                if cursor.fetchone()[0]: raise ValueError("Invalid source geometry")
                cursor.execute("""SELECT id,props->>'pnu',ST_Distance(geom,ST_SetSRID(ST_MakePoint(%s,%s),5179)) AS distance
                    FROM pilot_gis ORDER BY distance,id""",(candidate["x"],candidate["y"]))
                distance_rows=cursor.fetchall()
                parcels={pnu for _,pnu,distance in distance_rows if distance<=100 and re.fullmatch(r"\d{19}",pnu or "")}
                for old in candidate["inspection_buildings"]:
                    if old["heightMeters"] is not None: continue
                    # Original natural identity is retained in the public source ID.
                    triple=old["sourceId"].split(":")[:3]
                    match=next((feature for identifier,feature in raw.items() if identifier.split(":")[:3]==triple),None)
                    if match and len(parcels)<4: parcels.add(str(match["properties"]["pnu"]))
                    if len(parcels)>=4: break
                hub,hub_audit=collect_hub_parcels(client,parcels)
                normalized,matches=match_heights(gis,hub)
                decisions={row["sourceId"]:row for row in matches}
                close_buildings=[]
                for identifier,pnu,distance in distance_rows:
                    if distance>200: continue
                    properties=raw[identifier]["properties"]
                    decision=copy.deepcopy(decisions[identifier])
                    if pnu not in parcels:
                        decision["pkValidation"]="HUB_PARCEL_NOT_REFRESHED"
                        if decision["heightMeters"] is None: decision["heightGapReason"]="HUB_PARCEL_NOT_REFRESHED"
                    close_buildings.append({"sourceId":identifier,"distanceMeters":distance,
                        "name":properties.get("buld_nm"),"dongName":properties.get("dong_nm"),"pnu":pnu,
                        "sourceProperties":properties,"hubRefreshedForParcel":pnu in parcels,"match":decision})
                x,y=web_mercator(lon,lat); radius=200/math.cos(math.radians(lat))
                address=client.get("address",{"service":"WFS","request":"GetFeature","version":"2.0.0","typename":"lt_c_spbd",
                    "bbox":f"{x-radius},{y-radius},{x+radius},{y+radius}","srsname":"EPSG:3857","output":"application/json","count":"1000"})
                if address.get("crs",{}).get("properties",{}).get("name")!="urn:ogc:def:crs:EPSG::3857" or int(address["numberMatched"])!=len(address["features"]):
                    raise ValueError("Invalid or incomplete address source")
                cursor.execute("CREATE TEMP TABLE pilot_address (id text,props jsonb,geom geometry) ON COMMIT DROP")
                for feature in address["features"]:
                    cursor.execute("INSERT INTO pilot_address VALUES (%s,%s,ST_Transform(ST_SetSRID(ST_GeomFromGeoJSON(%s),3857),5179))",
                        (str(feature["properties"]["bd_mgt_sn"]),json.dumps(feature["properties"]),json.dumps(feature["geometry"])))
                cursor.execute("SELECT count(*) FROM pilot_address WHERE NOT ST_IsValid(geom) OR ST_IsEmpty(geom)")
                if cursor.fetchone()[0]: raise ValueError("Invalid address geometry")
                cursor.execute("""SELECT a.id,a.props,ST_Distance(a.geom,ST_SetSRID(ST_MakePoint(%s,%s),5179)),
                    coalesce(ST_Area(ST_Intersection(a.geom,(SELECT ST_UnaryUnion(ST_Collect(g.geom)) FROM pilot_gis g))),0)/ST_Area(a.geom)
                    FROM pilot_address a WHERE ST_DWithin(a.geom,ST_SetSRID(ST_MakePoint(%s,%s),5179),100) ORDER BY a.id""",
                    (candidate["x"],candidate["y"],candidate["x"],candidate["y"]))
                address_comparison=[{"id":i,"properties":p,"distanceMeters":d,"gisFootprintCoverage":c} for i,p,d,c in cursor.fetchall()]
                cursor.execute("""SELECT o.id,ST_Distance(o.footprint,ST_SetSRID(ST_MakePoint(%s,%s),5179)),
                    coalesce(ST_Area(ST_Intersection(o.footprint,(SELECT ST_UnaryUnion(ST_Collect(g.geom)) FROM pilot_gis g))),0)/ST_Area(o.footprint)
                    FROM building_shade_footprint o WHERE o.dataset_id=%s
                    AND ST_DWithin(o.footprint,ST_SetSRID(ST_MakePoint(%s,%s),5179),100) ORDER BY o.id""",
                    (candidate["x"],candidate["y"],plan["osmVersion"],candidate["x"],candidate["y"]))
                osm_comparison=[{"id":i,"distanceMeters":d,"gisFootprintCoverage":c} for i,d,c in cursor.fetchall()]
                cursor.execute("""SELECT ST_AsGeoJSON(ST_Transform(ST_LineInterpolatePoint(
                    ST_Transform(ST_SetSRID(ST_GeomFromGeoJSON(%s),4326),5179),f),4326))
                    FROM generate_series(0.0,1.0,0.2) f ORDER BY f""",(candidate["sample_route"],))
                stations=[{"type":"Feature","geometry":json.loads(row[0]),"properties":{"station":index,"groundVerified":False}}
                    for index,row in enumerate(cursor.fetchall())]
            connection.rollback()
    except (psycopg.Error,SourceError,ValueError,KeyError,TypeError) as error:
        parser.exit(1,(str(error) if isinstance(error,SourceError) else "Pilot audit failed; credentials and connection details omitted")+"\n")
    roads_file=args.output/"roads.pending-review.osm"
    roads_file.write_bytes(subset_roads(root,{row["osmWayId"] for row in roads}))
    document=copy.deepcopy(snapshot)
    points=[point for feature in features for polygon in (feature["geometry"]["coordinates"] if feature["geometry"]["type"]=="MultiPolygon" else [feature["geometry"]["coordinates"]]) for ring in polygon for point in ring]
    west=min([bbox[0]]+[p[0] for p in points]); east=max([bbox[2]]+[p[0] for p in points])
    south=min([bbox[1]]+[p[1] for p in points]); north=max([bbox[3]]+[p[1] for p in points])
    document["metadata"].update(areaId="seoul-forest-pilot-school",inventoryComplete=False,maximumHeightMeters=None,
        coverage={"type":"Polygon","coordinates":[[[west,south],[east,south],[east,north],[west,north],[west,south]]]})
    document["metadata"]["qualityNotes"].append("Pending pilot review; subset is not a complete physical inventory. Not activated.")
    document["features"]=features
    building_file=args.output/"buildings.pending-review.geojson"
    building_file.write_text(json.dumps(document,ensure_ascii=False,sort_keys=True,separators=(",",":")),encoding="utf-8")
    overlay=stations+[{"type":"Feature","geometry":row["geometry"],"properties":{k:v for k,v in row.items() if k!="geometry"}} for row in roads+excluded]
    (args.output/"ground-review.geojson").write_text(json.dumps({"type":"FeatureCollection","features":overlay},ensure_ascii=False),encoding="utf-8")
    result={"candidateId":candidate["id"],"observedAt":datetime.now(timezone.utc).isoformat(),
        "sourceGraphVersion":plan["graphVersion"],"sourceBuildingsVersion":version,"roadSourceSha256":digest(args.roads_osm),
        "graphPreview":{"file":roads_file.name,"sha256":digest(roads_file),"areaId":"seoul-forest-pilot-school","bounds":bounds,
            "halfWidthMetersApprox":args.graph_radius,"eligibleEdges":len(roads),"excludedEdges":len(excluded),"groundVerified":False},
        "buildingPreview":{"file":building_file.name,"sha256":digest(building_file),"count":len(features),"inventoryComplete":False,"maximumHeightMeters":None},
        "routeSearchRequest":candidate["routeSearchRequest"],"groundStations":stations,
        "currentKnownSourceHeightsWithin100m":[row for row in close_buildings if row["distanceMeters"]<=100],
        "freshBuildingsWithin200m":close_buildings,"refreshedParcels":hub_audit,"refreshedHubRecords":hub,
        "gisRequests":gis_requests,"addressWithin100m":address_comparison,"osmWithin100m":osm_comparison,
        "roads":roads,"excludedRoads":excluded,"old500mUnknownHeightCount":candidate["unknown_inspection"],
        "networkRequests":client.request_count,"sourceResponseCount":len(client.observations),"sourceObservedAt":client.observations,
        "blockers":["PHYSICAL_INVENTORY_NOT_VERIFIED","HEIGHT_BOUND_NOT_VERIFIED","GROUND_CONTEXT_NOT_VERIFIED",
            "CROSS_SOURCE_GEOMETRIES_REQUIRE_REVIEW"],"readyForShadeApi":False,"activeDataChanged":False}
    (args.output/"audit.json").write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding="utf-8")
    (args.output/"field-review.html").write_text(field_html(result,document,json.loads(args.osm_buildings.read_text(encoding='utf-8'))),encoding='utf-8')
    print(json.dumps({"candidateId":candidate["id"],"freshGisRows":len(gis),"freshHubRecords":len(hub),
        "networkRequests":client.request_count,"previewEdges":len(roads),"excludedEdges":len(excluded),
        "buildingPreviewCount":len(features),"readyForShadeApi":False,"output":str(args.output)},indent=2))


if __name__=="__main__": main()
