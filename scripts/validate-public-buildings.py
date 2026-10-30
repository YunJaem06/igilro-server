#!/usr/bin/env python3
"""Validate a collected pilot with PostGIS; quarantine invalid shapes without repairing them.

Uses temporary tables and rolls back. Does not activate a dataset or change application tables.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import sys
from urllib.parse import urlparse

import psycopg
from building_sources import settings


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--env-file", type=Path, default=Path(".env"))
    args = parser.parse_args()
    if args.output.exists():
        parser.error("Retaining existing output. Choose a new output file.")
    document = json.loads(args.input.read_text(encoding="utf-8"))
    features = document["features"]
    if document["metadata"]["inventoryComplete"] is not False or len(features) > 25000:
        parser.error("Only bounded, explicitly incomplete public inventories are accepted.")
    config = settings(args.env_file)
    url = urlparse(config.get("DB_URL", "jdbc:postgresql://127.0.0.1:5432/igilro").removeprefix("jdbc:"))
    if url.scheme != "postgresql" or url.hostname not in {"127.0.0.1", "localhost"}:
        parser.error("This validation command is limited to local PostgreSQL.")
    try:
        with psycopg.connect(host=url.hostname, port=url.port or 5432, dbname=url.path.lstrip("/"),
                user=config.get("DB_USERNAME", config.get("POSTGRES_USER", "igilro")),
                password=config.get("DB_PASSWORD", config.get("POSTGRES_PASSWORD", "igilro"))) as connection:
            with connection.cursor() as cursor:
                cursor.execute("SET LOCAL statement_timeout='60s'")
                cursor.execute("CREATE TEMP TABLE pilot_shape (ordinal integer, source_json text)")
                with cursor.copy("COPY pilot_shape (ordinal,source_json) FROM STDIN") as copy:
                    for index, feature in enumerate(features):
                        copy.write_row((index, json.dumps(feature["geometry"])))
                cursor.execute("""CREATE TEMP TABLE pilot_geometry AS
                    SELECT ordinal,ST_SetSRID(ST_GeomFromGeoJSON(source_json),4326) AS geom FROM pilot_shape""")
                cursor.execute("""SELECT ordinal,ST_IsValid(geom),ST_IsEmpty(geom),ST_IsValidReason(geom),
                    ST_NPoints(geom),GeometryType(geom),CASE WHEN ST_IsValid(geom) AND NOT ST_IsEmpty(geom)
                    THEN ST_Intersects(geom,ST_GeomFromGeoJSON(%s)) ELSE false END
                    FROM pilot_geometry ORDER BY ordinal""", (json.dumps(document["metadata"]["coverage"]),))
                rows = cursor.fetchall()
                quarantine = []
                accepted = []
                coordinate_count = 5
                for index, valid, empty, reason, points, kind, intersects in rows:
                    if not valid or empty or kind not in {"POLYGON","MULTIPOLYGON"} or not intersects:
                        quarantine.append({"id":features[index]["id"],"valid":valid,"empty":empty,
                            "geometryType":kind,"intersectsRequestedBbox":intersects,"reason":reason})
                    else:
                        accepted.append(features[index])
                        coordinate_count += points
                rejected = {row["id"] for row in quarantine}
                accepted_ordinals = [index for index, feature in enumerate(features) if feature["id"] not in rejected]
                cursor.execute("""SELECT count(*) FILTER (WHERE NOT ST_IsValid(ST_Transform(geom,5179))),
                    max(ST_HausdorffDistance(ST_Boundary(ST_Transform(ST_Transform(geom,5179),4326)),ST_Boundary(geom))),
                    sum(ST_Area(ST_Transform(geom,5179))) FROM pilot_geometry WHERE ordinal=ANY(%s)""",(accepted_ordinals,))
                invalid_projected, roundtrip_error, total_area = cursor.fetchone()
                if invalid_projected or coordinate_count > 250000:
                    raise ValueError("Projection validation or server coordinate limit failed")
                cursor.execute("""SELECT count(*),coalesce(sum(n-1),0) FROM
                    (SELECT count(*) AS n FROM pilot_geometry WHERE ordinal=ANY(%s)
                    GROUP BY ST_AsEWKB(ST_Normalize(geom)) HAVING count(*)>1) duplicates""",(accepted_ordinals,))
                duplicate_groups, duplicate_extra_rows = cursor.fetchone()
            connection.rollback()
    except (psycopg.Error, ValueError):
        print("PostGIS validation failed; credentials and connection details omitted",file=sys.stderr)
        return 1
    document["features"] = accepted
    document["metadata"]["qualityNotes"].append("Invalid source geometries quarantined without automatic repair.")
    payload = json.dumps(document,ensure_ascii=False,sort_keys=True,separators=(",",":")).encode("utf-8")
    if not accepted or len(payload)>20000000:
        parser.error("Validated inventory exceeds server bounds or is empty.")
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_bytes(payload)
    report = {"inputSha256":hashlib.sha256(args.input.read_bytes()).hexdigest(),
        "dataVersion":"buildings-v1-"+hashlib.sha256(payload).hexdigest(),"buildingCount":len(accepted),
        "knownHeightCount":sum(f["properties"]["heightMeters"] is not None for f in accepted),
        "unknownHeightCount":sum(f["properties"]["heightMeters"] is None for f in accepted),
        "heightKinds":dict(Counter(f["properties"]["heightSource"] for f in accepted)),
        "heightProviders":dict(Counter(f["properties"]["heightSourceReference"] or "UNKNOWN" for f in accepted)),
        "quarantinedCount":len(quarantine),"quarantined":quarantine,"coordinateCount":coordinate_count,
        "identicalGeometryGroups":duplicate_groups,"additionalIdenticalGeometryRows":int(duplicate_extra_rows),
        "invalidProjectedCount":invalid_projected,"maximumRoundtripErrorDegrees":roundtrip_error,
        "summedFootprintAreaMeters2":total_area,"inventoryComplete":False,"maximumHeightMeters":None}
    args.output.with_suffix(".audit.json").write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps({k:v for k,v in report.items() if k!="quarantined"},ensure_ascii=True,indent=2))
    return 0


if __name__=="__main__":
    raise SystemExit(main())
