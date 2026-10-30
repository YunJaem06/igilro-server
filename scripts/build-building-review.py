#!/usr/bin/env python3
"""Build a self-contained, offline building review map from local audited snapshots."""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import secrets
import xml.etree.ElementTree as ET

from building_sources import source_id

ASSETS = Path(__file__).with_name("building-review")
PROVIDER_NAMES = {
    "https://api.vworld.kr/ned/wfs/getBldgisSpceWFS":"브이월드 GIS건물통합정보",
    "https://apis.data.go.kr/1613000/BldRgstHubService/getBrTitleInfo":"건축HUB 표제부",
}


def load_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def safe_embedded_json(value):
    return (json.dumps(value,ensure_ascii=False,separators=(",",":"),allow_nan=False)
            .replace("&","\\u0026").replace("<","\\u003c").replace(">","\\u003e")
            .replace("\u2028","\\u2028").replace("\u2029","\\u2029"))


def read_source_properties(cache):
    gis,hub={},{}
    for path in sorted(cache.glob("*.json")):
        if not path.name.startswith(("vworld-","hub-")):
            continue
        record=load_json(path)
        body=record["body"]
        encoded=json.dumps(body,ensure_ascii=False,sort_keys=True,separators=(",",":")).encode("utf-8")
        if hashlib.sha256(encoded).hexdigest()!=record["sanitizedBodySha256"]:
            raise ValueError("Source cache checksum mismatch")
        if record["endpoint"] not in PROVIDER_NAMES:
            raise ValueError("Unexpected source cache endpoint")
        if path.name.startswith("vworld-"):
            for feature in body["features"]:
                gis[source_id(feature)]=feature["properties"]
        else:
            items=(body["response"]["body"].get("items") or {}).get("item",[])
            for item in items if isinstance(items,list) else [items]:
                key=str(item["mgmBldrgstPk"])
                public={name:item.get(name) for name in ("mgmBldrgstPk","bldNm","dongNm","platPlc","newPlatPlc",
                    "archArea","heit","grndFlrCnt","useAprDay","sigunguCd","bjdongCd","platGbCd","bun","ji")}
                if key in hub and hub[key]!=public:
                    raise ValueError("Ambiguous HUB source identity")
                hub[key]=public
    return gis,hub


def checked_point(point):
    if len(point)!=2 or any(isinstance(v,bool) or not isinstance(v,(int,float)) or not math.isfinite(v) for v in point):
        raise ValueError("Expected a finite WGS84 coordinate pair")
    if not 124<=point[0]<=132 or not 33<=point[1]<=39:
        raise ValueError("Coordinate outside the Korean pilot")
    return [round(point[0],7),round(point[1],7)]


def polygon_coordinates(geometry):
    if geometry["type"]=="Polygon":
        polygons=[geometry["coordinates"]]
    elif geometry["type"]=="MultiPolygon":
        polygons=geometry["coordinates"]
    else:
        raise ValueError("Building geometry must be Polygon/MultiPolygon")
    result=[]
    for polygon in polygons:
        if not polygon:
            raise ValueError("Empty building polygon")
        rings=[]
        for ring in polygon:
            points=[checked_point(point) for point in ring]
            if len(points)<4 or points[0]!=points[-1]:
                raise ValueError("Unclosed building ring")
            rings.append(points)
        result.append(rings)
    return result


def read_roads(path):
    root=ET.parse(path).getroot()
    nodes={node.attrib["id"]:checked_point([float(node.attrib["lon"]),float(node.attrib["lat"])]) for node in root.findall("node")}
    roads=[]
    for way in root.findall("way"):
        tags={tag.attrib["k"]:tag.attrib["v"] for tag in way.findall("tag")}
        if "highway" not in tags:
            continue
        refs=[node.attrib["ref"] for node in way.findall("nd")]
        if len(refs)>1 and all(ref in nodes for ref in refs):
            roads.append({"coordinates":[nodes[ref] for ref in refs],"name":tags.get("name",""),"kind":tags["highway"]})
    return roads


def build_payload(snapshot,route_path,roads_path,cache,pilot_plan=None,osm_buildings=None,reference_audit=None):
    inventory_path=snapshot/"buildings.validated.geojson"
    inventory=load_json(inventory_path)
    validation=load_json(snapshot/"buildings.validated.audit.json")
    version="buildings-v1-"+hashlib.sha256(inventory_path.read_bytes()).hexdigest()
    if version!=validation["dataVersion"]:
        raise ValueError("Validated inventory checksum mismatch")
    decisions=load_json(snapshot/"matching-audit.json")
    audit={row["sourceId"]:row for row in decisions}
    if len(audit)!=len(decisions) or len(audit)!=len(inventory["features"]):
        raise ValueError("Matching audit and inventory row counts differ")
    gis,hub=read_source_properties(cache)
    buildings=[]
    for feature in inventory["features"]:
        properties=feature["properties"]
        identifier=properties["sourceId"]
        if identifier not in audit or identifier not in gis:
            raise ValueError("Building is missing its audit or original GIS row")
        decision=audit[identifier]
        if decision["heightMeters"]!=properties["heightMeters"]:
            raise ValueError("Matching decision and normalized height differ")
        raw=gis[identifier]
        conflict=(decision["pkValidation"] in {"DUPLICATE_GIS_PK","AMBIGUOUS_HUB_PK"}
                  or "CONFLICT" in decision["pkValidation"] or decision["heightConflict"])
        candidate=hub.get(decision.get("hubSourceId") or decision.get("convertedHubPk"))
        buildings.append({"id":feature["id"],"sourceId":identifier,"pnu":decision["pnu"],
            "name":str(raw.get("buld_nm") or "").strip(),"dong":str(raw.get("dong_nm") or "").strip(),
            "objectId":str(raw.get("src_objectid") or ""),"height":properties["heightMeters"],
            "heightKind":properties["heightSource"],"heightProvider":PROVIDER_NAMES.get(properties.get("heightSourceReference"),"미확인"),
            "heightSourceId":properties.get("heightSourceId"),"conflict":conflict,"audit":decision,
            "gis":{name:raw.get(name) for name in ("buld_idntfc_no","ar","hg","ground_floor_co","use_confm_de","last_updt_dt")},
            "hub":candidate,"polygons":polygon_coordinates(feature["geometry"])})
    if {row["sourceId"] for row in buildings}!=audit.keys():
        raise ValueError("Audit identities differ from the inventory")
    route_response=load_json(route_path)
    route=route_response["routes"][0]
    if route["geometry"]["type"]!="LineString" or len(route["geometry"]["coordinates"])<2:
        raise ValueError("Expected an actual connected walking route")
    route_version=route.get("shadeEvaluation",{}).get("dataVersion")
    if route_version!=version:
        raise ValueError("Route response references a different building version")
    kinds=Counter(row["heightKind"] for row in buildings)
    payload={"schemaVersion":1,"dataVersion":version,"graphVersion":route_response.get("dataVersion"),"generatedAt":datetime.now(timezone.utc).isoformat(),
        "sourceTimestamp":inventory["metadata"]["sourceTimestamp"],"attribution":inventory["metadata"]["attribution"],
        "graphAttribution":route_response.get("attribution","© OpenStreetMap contributors, ODbL 1.0"),
        "inventoryComplete":inventory["metadata"]["inventoryComplete"],"maximumHeightMeters":inventory["metadata"]["maximumHeightMeters"],
        "counts":{"all":len(buildings),"known":sum(row["height"] is not None for row in buildings),
            "unknown":sum(row["height"] is None for row in buildings),"conflicts":sum(row["conflict"] for row in buildings),
            "pkVerified":sum(row["audit"]["pkValidation"]=="VERIFIED_OFFICIAL_PK" for row in buildings),"heightKinds":dict(kinds)},
        "route":{"coordinates":[checked_point(point) for point in route["geometry"]["coordinates"]],
            "distanceMeters":route["distanceMeters"],"durationSeconds":route["durationSeconds"],
            "evaluatedAt":route_response["evaluatedAt"],"status":route["shadeEvaluation"]["status"]},
        "roads":read_roads(roads_path),"buildings":buildings}
    if pilot_plan is not None:
        plan=load_json(pilot_plan)
        if plan["publicVersion"]!=version or plan["graphVersion"]!=payload["graphVersion"] or plan["authorizedAreaCount"]!=0:
            raise ValueError("Pilot plan versions or pending-only authorization differ")
        osm={}
        if osm_buildings is not None:
            if "buildings-v1-"+hashlib.sha256(osm_buildings.read_bytes()).hexdigest()!=plan["osmVersion"]:
                raise ValueError("OSM comparison snapshot version differs")
            osm={feature["id"]:feature for feature in load_json(osm_buildings)["features"]}
        candidates=[]
        for index,row in enumerate(plan["candidates"],1):
            if row["readyForShadeApi"] is not False:raise ValueError("Unexpected approved pilot candidate")
            actual=row["actualRouteResponse"]
            route=actual["routes"][0]
            if actual["dataVersion"]!=plan["graphVersion"] or route["shadeEvaluation"]["dataVersion"]!=version:
                raise ValueError("Pilot route versions differ")
            outlines=[]
            for item in row["osm_comparison"]:
                if item["id"] in osm:
                    feature=osm[item["id"]]
                    outlines.append({"id":item["id"],"height":item["heightMeters"],"publicCoverage":item["publicFootprintCoverage"],
                        "polygons":polygon_coordinates(feature["geometry"])})
            candidates.append({"id":row["id"],"name":f"후보 {index}","center":checked_point([row["longitude"],row["latitude"]]),
                "core":polygon_coordinates(json.loads(row["core_geometry"])),"audit":polygon_coordinates(json.loads(row["audit_geometry"])),
                "inspection":polygon_coordinates(json.loads(row["inspection_geometry"])),"blockers":row["blockers"],
                "totalAudit":row["total_audit"],"unknownAudit":row["unknown_audit"],"unknownInspection":row["unknown_inspection"],
                "osmReviewCount":row["osm_unmatched"],"readyForShadeApi":False,"osmOutlines":outlines,
                "route":{"coordinates":[checked_point(point) for point in route["geometry"]["coordinates"]],
                    "distanceMeters":route["distanceMeters"],"durationSeconds":route["durationSeconds"],"status":route["shadeEvaluation"]["status"]}})
        payload["pilotPlan"]={"coreRadius":plan["coreRadiusMeters"],"auditRadius":plan["auditRadiusMeters"],
            "inspectionRadius":plan["inspectionRadiusMeters"],"candidates":candidates}
        if reference_audit is not None:
            reference=load_json(reference_audit)
            if reference["publicVersion"]!=version or reference["coverageAuthorized"] is not False:
                raise ValueError("Address reference version or pending status differs")
            matching=[candidate for candidate in candidates if candidate["id"]==reference["candidateId"]]
            if len(matching)!=1:raise ValueError("Address reference candidate is missing")
            matching[0]["addressReference"]={"addressRows":reference["addressRowsWithinAudit"],
                "publicRows":reference["publicRowsWithinAudit"],"directHeightFieldCount":len(reference["directHeightFields"])}
    return payload


def render_html(payload):
    nonce=secrets.token_hex(16)
    html=(ASSETS/"index.html").read_text(encoding="utf-8")
    # Insert data last: untrusted source strings must never participate in template replacement.
    html=html.replace("__NONCE__",nonce).replace("__STYLE__",(ASSETS/"review.css").read_text(encoding="utf-8"))
    script=(ASSETS/"notes.js").read_text(encoding="utf-8")+"\n"+(ASSETS/"review.js").read_text(encoding="utf-8")
    html=html.replace("__SCRIPT__",script)
    return html.replace("__REVIEW_DATA__",safe_embedded_json(payload))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshot",type=Path,required=True)
    parser.add_argument("--route",type=Path,required=True)
    parser.add_argument("--roads",type=Path,required=True)
    parser.add_argument("--response-cache",type=Path,required=True)
    parser.add_argument("--pilot-plan",type=Path)
    parser.add_argument("--osm-buildings",type=Path)
    parser.add_argument("--reference-audit",type=Path)
    parser.add_argument("--output",type=Path,required=True)
    args=parser.parse_args()
    if (args.osm_buildings or args.reference_audit) and not args.pilot_plan:
        parser.error("OSM/reference overlays require --pilot-plan.")
    if args.output.exists():
        parser.error("Retaining existing review map. Choose a new output file.")
    try:
        payload=build_payload(args.snapshot,args.route,args.roads,args.response_cache,args.pilot_plan,args.osm_buildings,args.reference_audit)
        html=render_html(payload)
    except (ValueError,KeyError,IndexError,TypeError,ET.ParseError):
        parser.exit(1,"Review map inputs are inconsistent or invalid; source values omitted\n")
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(html,encoding="utf-8")
    print(json.dumps({"output":str(args.output),"dataVersion":payload["dataVersion"],"counts":payload["counts"],
        "roads":len(payload["roads"]),"bytes":len(html.encode("utf-8"))},ensure_ascii=True,indent=2))


if __name__=="__main__":
    main()
