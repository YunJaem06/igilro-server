#!/usr/bin/env python3
"""Collect a bounded GIS/HUB snapshot; never declare an unverified inventory complete."""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import sys

from building_sources import PublicBuildingClient, SourceError, collect_gis, collect_hub, match_heights, settings, HUB_ENDPOINT, VWORLD_ENDPOINT, HUB_PK_RULE


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--env-file", type=Path, default=Path(".env"))
    parser.add_argument("--response-cache", type=Path, help="Reuse a previous sanitized response snapshot")
    parser.add_argument("--offline", action="store_true", help="Reject every cache miss without network access")
    parser.add_argument("--bbox", nargs=4, type=float, default=[127.022,37.528,127.060,37.563])
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    target = args.output / "buildings.incomplete.geojson"
    if target.exists():
        parser.error("Retaining existing output. Choose a new output directory.")
    client = PublicBuildingClient(settings(args.env_file), args.response_cache or args.output / "responses", offline=args.offline)
    try:
        gis, gis_audit = collect_gis(client, args.bbox)
        print(f"GIS buildings: {len(gis)} unique features, {len(gis_audit)} bounded spatial requests", flush=True)
        dongs = {str(feature["properties"].get("pnu", ""))[:10] for feature in gis.values()
                 if len(str(feature["properties"].get("pnu", ""))) == 19}
        hub, hub_audit = collect_hub(client, dongs)
        features, matches = match_heights(gis, hub)
    except (SourceError, ValueError, KeyError, TypeError) as error:
        print(str(error) if isinstance(error, SourceError) else "Source normalization failed; values omitted", file=sys.stderr)
        return 1
    observed = datetime.now(timezone.utc).isoformat()
    source_observations=sorted(datetime.fromisoformat(value) for value in client.observations)
    source_timestamp=source_observations[-1].isoformat()
    west,south,east,north = args.bbox
    document = {"type":"FeatureCollection","metadata":{"areaId":"seoul-forest","source":VWORLD_ENDPOINT+" + "+HUB_ENDPOINT,
        "sourceTimestamp":source_timestamp,"sourceTimestampBasis":"latest source-response observation, not normalization or provider survey time",
        "attribution":"국토교통부 GIS건물통합정보(브이월드) / 건축HUB 건축물대장정보; source-specific terms apply",
        "inventoryComplete":False,"maximumHeightMeters":None,
        "coverage":{"type":"Polygon","coordinates":[[[west,south],[east,south],[east,north],[west,north],[west,south]]]},
        "qualityNotes":["Complete provider retrieval is not proof of complete physical building inventory.",
            "No verified upper bound for unknown heights. No floor-based heights have been generated.",
            "Public heights are direct source values, not independently measured ground truth."]},"features":features}
    payload = json.dumps(document,ensure_ascii=False,sort_keys=True,separators=(",",":")).encode("utf-8")
    if len(payload)>20_000_000:
        raise SystemExit("Normalized inventory exceeds server size limit")
    target.write_bytes(payload)
    (args.output/"matching-audit.json").write_text(json.dumps(matches,ensure_ascii=False,indent=2),encoding="utf-8")
    report = {"bbox4326":args.bbox,"observedAt":observed,"buildingCount":len(features),"hubRecordCount":len(hub),
        "gisSourceRowCount":gis_audit[0]["matched"],"exactDuplicateSourceRows":gis_audit[0]["matched"]-len(gis),
        "heightKinds":dict(Counter(feature["properties"]["heightSource"] for feature in features)),
        "heightProviders":dict(Counter(match["heightSourceReference"] or "UNKNOWN" for match in matches)),
        "matchingMethods":dict(Counter(match["method"] or "UNMATCHED_OR_AMBIGUOUS" for match in matches)),
        "pkValidation":dict(Counter(match["pkValidation"] for match in matches)),
        "heightGapReasons":dict(Counter(match["heightGapReason"] for match in matches if match["heightMeters"] is None)),
        "heightConflicts":sum(match["heightConflict"] for match in matches),"pkRule":HUB_PK_RULE,
        "responseCache":str(client.cache),
        "sourceResponseObservations":{"first":source_observations[0].isoformat(),"last":source_timestamp},
        "gisRequests":gis_audit,"hubRetrieval":hub_audit,"networkRequestCount":client.request_count,
        "inventoryComplete":False,"maximumHeightMeters":None,"dataVersion":"buildings-v1-"+hashlib.sha256(payload).hexdigest()}
    (args.output/"audit.json").write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps({key:value for key,value in report.items() if key not in {"gisRequests","hubRetrieval"}},ensure_ascii=True,indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
