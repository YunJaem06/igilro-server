#!/usr/bin/env python3
"""Read-only PostGIS overlap diagnostics. Never transfers or estimates heights."""
import argparse
import json
from pathlib import Path
import re
from urllib.parse import urlparse

import psycopg
from building_sources import settings


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--public-version",required=True)
    parser.add_argument("--osm-version",required=True)
    parser.add_argument("--output",type=Path,required=True)
    parser.add_argument("--env-file",type=Path,default=Path(".env"))
    args=parser.parse_args()
    if args.output.exists() or not all(re.fullmatch(r"buildings-v1-[a-f0-9]{64}",value) for value in [args.public_version,args.osm_version]):
        parser.error("Expected new output and explicit building dataset versions.")
    config=settings(args.env_file)
    url=urlparse(config.get("DB_URL","jdbc:postgresql://127.0.0.1:5432/igilro").removeprefix("jdbc:"))
    if url.scheme!="postgresql" or url.hostname not in {"127.0.0.1","localhost"}:
        parser.error("This diagnostic command is limited to local PostgreSQL.")
    try:
        with psycopg.connect(host=url.hostname,port=url.port or 5432,dbname=url.path.lstrip("/"),
                user=config.get("DB_USERNAME",config.get("POSTGRES_USER","igilro")),
                password=config.get("DB_PASSWORD",config.get("POSTGRES_PASSWORD","igilro"))) as connection:
            with connection.cursor() as cursor:
                cursor.execute("SET TRANSACTION READ ONLY")
                cursor.execute("SET LOCAL statement_timeout='45s'")
                cursor.execute("SELECT count(*) FROM building_shade_dataset WHERE id=ANY(%s)",([args.public_version,args.osm_version],))
                if cursor.fetchone()[0]!=2:
                    parser.error("Two distinct existing datasets are required.")
                cursor.execute("""WITH pairs AS (
                    SELECT p.id AS public_id,p.source_id,p.height_meters AS public_height,p.height_source,
                        o.id AS osm_id,o.source_id AS osm_source_id,o.height_meters AS osm_height,
                        ST_Area(p.footprint) AS public_area,ST_Area(o.footprint) AS osm_area,
                        ST_Area(ST_Intersection(p.footprint,o.footprint)) AS intersection,
                        ST_Distance(ST_Centroid(p.footprint),ST_Centroid(o.footprint)) AS centroid_distance
                    FROM building_shade_footprint p JOIN building_shade_footprint o
                        ON p.footprint && o.footprint AND ST_Intersects(p.footprint,o.footprint)
                    WHERE p.dataset_id=%s AND o.dataset_id=%s
                ) SELECT *,intersection/nullif(public_area+osm_area-intersection,0) AS iou,
                    intersection/nullif(public_area,0) AS public_overlap,
                    intersection/nullif(osm_area,0) AS osm_overlap
                FROM pairs WHERE intersection/nullif(public_area+osm_area-intersection,0)>.1
                ORDER BY public_id,osm_id""",(args.public_version,args.osm_version))
                keys=[column.name for column in cursor.description]
                pairs=[dict(zip(keys,row)) for row in cursor.fetchall()]
            connection.rollback()
    except psycopg.Error:
        parser.exit(1,"PostGIS overlap diagnostic failed; connection details omitted\n")
    strong=[row for row in pairs if row["iou"]>=.95 and row["centroid_distance"]<=2
            and min(row["public_overlap"],row["osm_overlap"])>=.95]
    thresholds=[]
    for threshold in [.95,.9,.8,.7,.5]:
        selected=[row for row in pairs if row["iou"]>=threshold]
        thresholds.append({"minimumIou":threshold,"pairCount":len(selected),
            "bothKnownHeightPairs":sum(row["public_height"] is not None and row["osm_height"] is not None for row in selected),
            "unknownPublicWithOsmHeightPairs":sum(row["public_height"] is None and row["osm_height"] is not None for row in selected)})
    summary={"diagnosticOnly":True,"automaticHeightTransfers":0,"publicVersion":args.public_version,
        "osmVersion":args.osm_version,"overlapPairCount":len(pairs),"thresholdSensitivity":thresholds,
        "conservativeDiagnosticGate":{"minimumIou":.95,"minimumOverlapOfEach":.95,"maximumCentroidDistanceMeters":2,
            "pairCount":len(strong),"unknownPublicWithOsmHeightPairs":sum(row["public_height"] is None and row["osm_height"] is not None for row in strong)},
        "note":"Thresholds are review diagnostics, not validated identity or acceptance policies. No nearest-building height copy."}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(summary|{"pairs":pairs},ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps(summary,indent=2))


if __name__=="__main__":
    main()
