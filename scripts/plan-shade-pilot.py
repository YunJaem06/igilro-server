#!/usr/bin/env python3
"""Locate small walking-route audit candidates without authorizing shade coverage."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from urllib.parse import urlparse

from building_sources import settings


def ground_way_ids(path):
    ways=ET.parse(path).getroot().findall("way")
    accepted=[]
    for way in ways:
        tags={tag.attrib["k"]:tag.attrib["v"] for tag in way.findall("tag")}
        if "highway" not in tags or tags["highway"]=="steps":continue
        if any(tags.get(key,"").lower() not in {"","no","false","0"} for key in ["bridge","tunnel","covered","indoor"]):continue
        if any(tags.get(key,"") not in {"","0","+0"} for key in ["layer","level"]):continue
        if tags.get("location","") in {"underground","elevated","rooftop"}:continue
        accepted.append(int(way.attrib["id"]))
    return accepted,len(ways)


def coverage_blockers(row,inventory_verified=False,height_bound=None):
    blockers=[]
    if not inventory_verified: blockers.append("INVENTORY_NOT_VERIFIED")
    if isinstance(height_bound,bool) or not isinstance(height_bound,(int,float)) or not math.isfinite(height_bound) or not 0<height_bound<=500:
        blockers.append("HEIGHT_BOUND_NOT_VERIFIED")
    if row["unknown_audit"]: blockers.append("UNKNOWN_HEIGHTS_IN_AUDIT_AREA")
    if row.get("unknown_inspection",0)>row["unknown_audit"]:blockers.append("UNKNOWN_HEIGHTS_IN_SURROUNDINGS")
    if row["conflicts_audit"]: blockers.append("MATCHING_CONFLICTS_IN_AUDIT_AREA")
    if not row["inspection_inside_source"]: blockers.append("INSPECTION_EXTENDS_BEYOND_SOURCE")
    if row.get("osm_unmatched",0): blockers.append("OSM_FOOTPRINTS_REQUIRE_REVIEW")
    return blockers


def select_distinct(rows,count,separation):
    selected=[]
    for row in sorted(rows,key=lambda r:(r["unknown_audit"],r["conflicts_audit"],
            0 if r["total_audit"]>0 else 1,r["unknown_inspection"],-r["total_audit"],r["edge_id"])):
        if all(math.hypot(row["x"]-other["x"],row["y"]-other["y"])>=separation for other in selected):
            selected.append(row)
            if len(selected)==count:break
    return selected


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshot",type=Path,required=True)
    parser.add_argument("--roads-osm",type=Path,required=True)
    parser.add_argument("--osm-version",required=True)
    parser.add_argument("--output",type=Path,required=True)
    parser.add_argument("--env-file",type=Path,default=Path(".env"))
    parser.add_argument("--core-radius",type=int,default=50)
    parser.add_argument("--audit-radius",type=int,default=250)
    parser.add_argument("--count",type=int,default=3)
    args=parser.parse_args()
    if not 20<=args.core_radius<=100 or not args.core_radius<args.audit_radius<=1000 or not 1<=args.count<=8:
        parser.error("Expected core radius 20..100m, larger audit radius <=1000m, and 1..8 candidates.")
    if (args.output/"report.json").exists() or not re.fullmatch(r"buildings-v1-[a-f0-9]{64}",args.osm_version):
        parser.error("Expected new output and an explicit OSM building version.")
    validation=json.loads((args.snapshot/"buildings.validated.audit.json").read_text(encoding="utf-8"))
    audit=json.loads((args.snapshot/"matching-audit.json").read_text(encoding="utf-8"))
    public_version=validation["dataVersion"]
    ground_ids,source_way_count=ground_way_ids(args.roads_osm)
    road_checksum=hashlib.sha256(args.roads_osm.read_bytes()).hexdigest()
    conflicts=["molit-gis:"+row["sourceId"] for row in audit if "CONFLICT" in row["pkValidation"]
        or row["pkValidation"] in {"DUPLICATE_GIS_PK","AMBIGUOUS_HUB_PK"} or row["heightConflict"]]
    config=settings(args.env_file)
    url=urlparse(config.get("DB_URL","jdbc:postgresql://127.0.0.1:5432/igilro").removeprefix("jdbc:"))
    if url.scheme!="postgresql" or url.hostname not in {"127.0.0.1","localhost"}:
        parser.error("This planning command is limited to local PostgreSQL.")
    import psycopg
    inspection=max(500,args.audit_radius*2)
    try:
        with psycopg.connect(host=url.hostname,port=url.port or 5432,dbname=url.path.lstrip("/"),
            user=config.get("DB_USERNAME",config.get("POSTGRES_USER","igilro")),
            password=config.get("DB_PASSWORD",config.get("POSTGRES_PASSWORD","igilro"))) as connection:
            with connection.cursor() as cursor:
                cursor.execute("SET TRANSACTION READ ONLY")
                cursor.execute("SET LOCAL statement_timeout='60s'")
                cursor.execute("SELECT active_dataset_id FROM building_shade_area WHERE area_id='seoul-forest'")
                if cursor.fetchone()!=(public_version,):parser.error("Snapshot must match the active Seoul Forest building dataset.")
                cursor.execute("SELECT d.id,d.checksum FROM route_dataset d JOIN route_service_area a ON a.active_dataset_id=d.id WHERE a.id='seoul-forest'")
                graph_info=cursor.fetchone()
                if graph_info is None or graph_info[1]!=road_checksum:parser.error("Road source checksum differs from the active walking graph.")
                cursor.execute("""WITH params AS (SELECT %s::float8 AS core,%s::float8 AS audit,%s::float8 AS inspection),
                  area AS (SELECT ST_Transform(boundary,5179) AS boundary,active_dataset_id AS graph
                    FROM route_service_area WHERE id='seoul-forest'),
                  grid AS (SELECT ST_Centroid(s.geom) AS center FROM area a CROSS JOIN LATERAL ST_SquareGrid(100,a.boundary) s
                    WHERE ST_Contains(a.boundary,ST_Centroid(s.geom))),
                  routes AS MATERIALIZED (SELECT e.id,ST_Transform(e.geometry,5179) AS geom FROM route_edge e JOIN area a ON a.graph=e.dataset_id
                    CROSS JOIN params p WHERE e.osm_way_id=ANY(%s) AND ST_Length(ST_Transform(e.geometry,5179))>=p.core),
                  anchors AS (SELECT DISTINCT ON(ST_AsEWKB(road.anchor)) road.* FROM grid g CROSS JOIN LATERAL (
                    SELECT r.id AS edge_id,r.geom,ST_ClosestPoint(r.geom,g.center) AS anchor FROM routes r
                    WHERE ST_DWithin(r.geom,g.center,40) ORDER BY ST_Distance(r.geom,g.center),r.id LIMIT 1) road),
                  dataset AS (SELECT * FROM building_shade_dataset WHERE id=%s),
                  scored AS (SELECT a.*,p.*,c.*,ST_LineLocatePoint(a.geom,a.anchor) AS fraction,
                    ST_Covers(ST_Transform(d.coverage,5179),ST_Buffer(a.anchor,p.inspection)) AS inspection_inside_source
                    FROM anchors a CROSS JOIN params p CROSS JOIN dataset d CROSS JOIN LATERAL (
                      SELECT count(*) FILTER(WHERE ST_DWithin(b.footprint,a.anchor,p.core)) AS total_core,
                        count(*) FILTER(WHERE ST_DWithin(b.footprint,a.anchor,p.core) AND b.height_meters IS NULL) AS unknown_core,
                        count(*) FILTER(WHERE ST_DWithin(b.footprint,a.anchor,p.audit)) AS total_audit,
                        count(*) FILTER(WHERE ST_DWithin(b.footprint,a.anchor,p.audit) AND b.height_meters IS NULL) AS unknown_audit,
                        count(*) FILTER(WHERE ST_DWithin(b.footprint,a.anchor,p.audit) AND b.id=ANY(%s)) AS conflicts_audit,
                        count(*) FILTER(WHERE b.height_meters IS NULL) AS unknown_inspection,
                        max(b.height_meters) AS max_known_height_inspection,
                        min(ST_Distance(b.footprint,a.anchor)) FILTER(WHERE b.height_meters IS NULL) AS nearest_unknown_meters
                      FROM building_shade_footprint b WHERE b.dataset_id=d.id AND ST_DWithin(b.footprint,a.anchor,p.inspection)) c
                  ) SELECT edge_id,ST_X(anchor) AS x,ST_Y(anchor) AS y,ST_X(ST_Transform(anchor,4326)) AS longitude,
                    ST_Y(ST_Transform(anchor,4326)) AS latitude,total_core,unknown_core,total_audit,unknown_audit,
                    conflicts_audit,unknown_inspection,max_known_height_inspection,nearest_unknown_meters,inspection_inside_source,
                    ST_AsGeoJSON(ST_Transform(ST_Buffer(anchor,core),4326)) AS core_geometry,
                    ST_AsGeoJSON(ST_Transform(ST_Buffer(anchor,audit),4326)) AS audit_geometry,
                    ST_AsGeoJSON(ST_Transform(ST_Buffer(anchor,inspection),4326)) AS inspection_geometry,
                    ST_AsGeoJSON(ST_Transform(ST_LineSubstring(geom,greatest(0,least(fraction-core/2/ST_Length(geom),1-core/ST_Length(geom))),
                      least(1,greatest(0,least(fraction-core/2/ST_Length(geom),1-core/ST_Length(geom)))+core/ST_Length(geom))),4326)) AS sample_route FROM scored""",
                    (args.core_radius,args.audit_radius,inspection,ground_ids,public_version,conflicts))
                keys=[column.name for column in cursor.description]
                rows=[dict(zip(keys,row)) for row in cursor.fetchall()]
                selected=select_distinct(rows,args.count,args.audit_radius)
                for index,row in enumerate(selected,1):
                    cursor.execute("""WITH point AS (SELECT ST_SetSRID(ST_MakePoint(%s,%s),5179) AS p),
                      nearby AS (SELECT o.* FROM building_shade_footprint o CROSS JOIN point p
                        WHERE o.dataset_id=%s AND ST_DWithin(o.footprint,p.p,%s))
                      SELECT o.id,o.height_meters,coalesce(ST_Area(ST_Intersection(o.footprint,
                        (SELECT ST_UnaryUnion(ST_Collect(b.footprint)) FROM building_shade_footprint b
                         WHERE b.dataset_id=%s AND b.footprint && o.footprint))),0)/ST_Area(o.footprint) AS public_overlap
                      FROM nearby o ORDER BY o.id""",(row["x"],row["y"],args.osm_version,args.audit_radius,public_version))
                    row["osm_comparison"]=[{"id":identifier,"heightMeters":height,"publicFootprintCoverage":overlap}
                        for identifier,height,overlap in cursor.fetchall()]
                    row["osm_unmatched"]=sum(item["publicFootprintCoverage"]<.95 for item in row["osm_comparison"])
                    cursor.execute("""SELECT b.id,b.height_meters,b.height_source,b.source_id,
                        ST_Distance(b.footprint,ST_SetSRID(ST_MakePoint(%s,%s),5179)) AS distance
                        FROM building_shade_footprint b WHERE b.dataset_id=%s
                        AND ST_DWithin(b.footprint,ST_SetSRID(ST_MakePoint(%s,%s),5179),%s)
                        ORDER BY b.height_meters IS NOT NULL,distance,b.id""",
                        (row["x"],row["y"],public_version,row["x"],row["y"],inspection))
                    row["inspection_buildings"]=[{"id":identifier,"heightMeters":height,"heightSource":kind,"sourceId":source,"distanceMeters":distance}
                        for identifier,height,kind,source,distance in cursor.fetchall()]
                    row["id"]=f"seoul-forest-candidate-{index}"
                    row["inventoryVerified"]=False
                    row["verifiedMaximumHeightMeters"]=None
                    row["blockers"]=coverage_blockers(row)
                    row["readyForShadeApi"]=False
            connection.rollback()
    except psycopg.Error:
        parser.exit(1,"PostGIS pilot planning failed; connection details omitted\n")
    args.output.mkdir(parents=True,exist_ok=True)
    overlay=[]
    for row in selected:
        for kind in ["core","audit","inspection"]:
            overlay.append({"type":"Feature","geometry":json.loads(row[kind+"_geometry"]),"properties":{
                "candidateId":row["id"],"kind":kind,"readyForShadeApi":False}})
        overlay.append({"type":"Feature","geometry":json.loads(row["sample_route"]),"properties":{"candidateId":row["id"],"kind":"sample-route"}})
        line=json.loads(row["sample_route"])["coordinates"]
        row["routeSearchRequest"]={"origin":{"longitude":line[0][0],"latitude":line[0][1]},
            "destination":{"longitude":line[-1][0],"latitude":line[-1][1]},"travelMode":"walking","mode":"fastest","departureAt":"2026-10-26T12:00:00+09:00"}
    report={"publicVersion":public_version,"graphVersion":graph_info[0],"osmVersion":args.osm_version,"coreRadiusMeters":args.core_radius,
        "roadSourceSha256":road_checksum,"sourceRoadWays":source_way_count,"groundTagEligibleWays":len(ground_ids),
        "structurePolicy":"Exclude bridge, tunnel, covered, indoor, nonzero layer/level, elevated/underground/rooftop and steps; absence of tags is not field verification.",
        "auditRadiusMeters":args.audit_radius,"inspectionRadiusMeters":inspection,"gridSpacingMeters":100,
        "scannedRouteAnchors":len(rows),"zeroUnknownAuditCandidates":sum(row["unknown_audit"]==0 for row in rows),
        "zeroUnknownAuditCandidatesWithBuildings":sum(row["unknown_audit"]==0 and row["total_audit"]>0 for row in rows),
        "authorizedAreaCount":0,"candidateCount":len(selected),"candidates":selected,
        "note":"Distance windows are audit scopes, not verified shadow limits. No inventory flag, height or active dataset changed."}
    (args.output/"report.json").write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
    (args.output/"candidates.geojson").write_text(json.dumps({"type":"FeatureCollection","features":overlay},ensure_ascii=False),encoding="utf-8")
    print(json.dumps({key:value for key,value in report.items() if key!="candidates"},indent=2))
    for row in selected:print(json.dumps({key:row[key] for key in ["id","longitude","latitude","total_audit","unknown_audit","unknown_inspection","osm_unmatched","blockers"]},indent=2))


if __name__=="__main__":
    main()
