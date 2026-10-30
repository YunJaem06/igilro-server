#!/usr/bin/env python3
"""Bounded third-source geometry check; never copies floors into heights or authorizes coverage."""
import argparse
import json
import math
from pathlib import Path
from urllib.parse import urlparse

from building_sources import PublicBuildingClient,SourceError,settings,web_mercator


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report",type=Path,required=True)
    parser.add_argument("--candidate",type=int,default=1)
    parser.add_argument("--radius",type=int,default=150)
    parser.add_argument("--output",type=Path,required=True)
    parser.add_argument("--response-cache",type=Path)
    parser.add_argument("--offline",action="store_true")
    parser.add_argument("--env-file",type=Path,default=Path(".env"))
    args=parser.parse_args()
    report=json.loads(args.report.read_text(encoding="utf-8"))
    if (args.output/"audit.json").exists() or not 1<=args.candidate<=len(report["candidates"]) or not 100<=args.radius<=250:
        parser.error("Expected new output, an existing candidate, and radius 100..250m.")
    candidate=report["candidates"][args.candidate-1]
    config=settings(args.env_file)
    x,y=web_mercator(candidate["longitude"],candidate["latitude"])
    distance=args.radius/math.cos(math.radians(candidate["latitude"]))
    client=PublicBuildingClient(config,args.response_cache or args.output/"responses",offline=args.offline)
    try:
        body=client.get("address",{"service":"WFS","request":"GetFeature","version":"2.0.0","typename":"lt_c_spbd",
            "bbox":f"{x-distance},{y-distance},{x+distance},{y+distance}","srsname":"EPSG:3857","output":"application/json","count":"1000"})
        features=body["features"]
        if int(body["numberMatched"])!=len(features) or len(features)>1000 or body["crs"]["properties"]["name"]!="urn:ogc:def:crs:EPSG::3857":
            raise ValueError("Truncated reference response or unexpected CRS")
    except (SourceError,ValueError,KeyError) as error:
        parser.exit(1,(str(error) if isinstance(error,SourceError) else "Reference response validation failed")+"\n")
    url=urlparse(config.get("DB_URL","jdbc:postgresql://127.0.0.1:5432/igilro").removeprefix("jdbc:"))
    if url.scheme!="postgresql" or url.hostname not in {"127.0.0.1","localhost"}:parser.error("Local PostgreSQL only.")
    import psycopg
    try:
        with psycopg.connect(host=url.hostname,port=url.port or 5432,dbname=url.path.lstrip("/"),
            user=config.get("DB_USERNAME",config.get("POSTGRES_USER","igilro")),password=config.get("DB_PASSWORD",config.get("POSTGRES_PASSWORD","igilro"))) as connection:
            with connection.cursor() as cursor:
                cursor.execute("SET LOCAL statement_timeout='30s'")
                cursor.execute("CREATE TEMP TABLE pilot_address (id text,properties jsonb,geom geometry)")
                for feature in features:
                    cursor.execute("INSERT INTO pilot_address VALUES (%s,%s,ST_Transform(ST_SetSRID(ST_GeomFromGeoJSON(%s),3857),5179))",
                        (str(feature["properties"]["bd_mgt_sn"]),json.dumps(feature["properties"]),json.dumps(feature["geometry"])))
                cursor.execute("SELECT count(*) FROM pilot_address WHERE NOT ST_IsValid(geom) OR ST_IsEmpty(geom)")
                if cursor.fetchone()[0]:parser.error("Invalid reference geometries; no approval or repair performed.")
                cursor.execute("""SELECT id,properties,ST_Distance(geom,ST_SetSRID(ST_MakePoint(%s,%s),5179)) AS distance
                    FROM pilot_address WHERE ST_DWithin(geom,ST_SetSRID(ST_MakePoint(%s,%s),5179),%s) ORDER BY distance""",
                    (candidate["x"],candidate["y"],candidate["x"],candidate["y"],report["auditRadiusMeters"]))
                nearby=[{"id":identifier,"properties":props,"distanceMeters":distance} for identifier,props,distance in cursor.fetchall()]
                cursor.execute("""SELECT o.id,a.id,a.properties->>'buld_nm',a.properties->>'buld_nm_dc',
                    a.properties->>'gro_flo_co',ST_Area(ST_Intersection(o.footprint,a.geom))/ST_Area(o.footprint)
                    FROM building_shade_footprint o CROSS JOIN pilot_address a
                    WHERE o.dataset_id=%s AND o.id=ANY(%s) AND ST_Intersects(o.footprint,a.geom) ORDER BY o.id,6 DESC""",
                    (report["osmVersion"],[item["id"] for item in candidate["osm_comparison"]]))
                comparisons=[{"osmId":osm,"addressBuildingId":address,"name":name,"dongName":dong,
                    "groundFloors":floors,"osmFootprintCoverage":coverage} for osm,address,name,dong,floors,coverage in cursor.fetchall()]
            connection.rollback()
    except psycopg.Error:
        parser.exit(1,"Reference geometry comparison failed; connection details omitted\n")
    field_names=sorted({key for feature in features for key in feature["properties"]})
    height_fields=[name for name in field_names if name.lower() in {"height","heightmeters","hg","heit","hgt","buld_hg"}]
    result={"candidateId":candidate["id"],"publicVersion":report["publicVersion"],"source":"https://www.vworld.kr/dev/v4dv_wmsguide2_s001.do",
        "layer":"lt_c_spbd","responseCrs":"EPSG:3857","queryRadiusMeters":args.radius,"auditRadiusMeters":report["auditRadiusMeters"],
        "returnedCount":len(features),"addressRowsWithinAudit":len(nearby),"publicRowsWithinAudit":candidate["total_audit"],
        "sourceFieldNames":field_names,"directHeightFields":height_fields,"withinAuditArea":nearby,"osmComparison":comparisons,"coverageAuthorized":False,
        "networkRequestCount":client.request_count,"note":"Different sources can split buildings differently. Row counts alone do not prove missing buildings. Floor counts are not heights."}
    args.output.mkdir(parents=True,exist_ok=True)
    (args.output/"audit.json").write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps({key:value for key,value in result.items() if key not in {"withinAuditArea","osmComparison"}},indent=2))


if __name__=="__main__":
    main()
