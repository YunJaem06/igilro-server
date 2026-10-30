"""Bounded public building sources. Credentials and authenticated URLs stay in memory."""
from collections import Counter, defaultdict
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
import hashlib
import json
import math
import os
from pathlib import Path
import re
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

VWORLD_ENDPOINT = "https://api.vworld.kr/ned/wfs/getBldgisSpceWFS"
HUB_ENDPOINT = "https://apis.data.go.kr/1613000/BldRgstHubService/getBrTitleInfo"
VWORLD_ADDRESS_ENDPOINT = "https://api.vworld.kr/req/wfs"
HEIGHT_LIMIT = 500
# Official HUB notice 254, attachment 1 p.2 and attachment 2 p.1.
# Intentionally limited to the districts present in the Seoul Forest pilot.
PILOT_HUB_CITY_CODES = {"11140":"1003", "11200":"1005", "11215":"1006", "11230":"1007", "11680":"1024"}
HUB_PK_RULE = {
    "version":"hub-live-register-20241011-pilot-v1",
    "notice":"https://www.hub.go.kr/portal/bbs/ntc/idx-ntc-detail.do?selectedId=254",
    "conversionPdfSha256":"37f53760fd3d669c5229320c1610f9638e74ee5010ff9831637d511550d23c3a",
    "cityCodesPdfSha256":"cdd6b793a5f4d85fb6f58e731b0c7fbc403fcd71981a363505040779218c90f7",
}


class SourceError(RuntimeError):
    def __init__(self, provider, code):
        self.provider, self.code = provider, code
        super().__init__(f"{provider}: {code}; credentials and response details omitted")


def settings(env_file=Path(".env"), environment=None):
    environment = os.environ if environment is None else environment
    values = {}
    if env_file.is_file():
        for line in env_file.read_text(encoding="utf-8-sig").splitlines():
            if line.strip() and not line.lstrip().startswith("#") and "=" in line:
                name, value = line.split("=", 1)
                values[name.strip()] = value.strip()
    return dict(values, **environment)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


class PublicBuildingClient:
    def __init__(self, config, cache, opener=None, interval=0.5, offline=False):
        self._config = config
        self.cache = Path(cache)
        self.cache.mkdir(parents=True, exist_ok=True)
        self._opener = opener or urllib.request.build_opener(NoRedirect())
        self._interval = interval
        self._last = 0
        self.request_count = 0
        self.offline = offline
        self.observations = []

    def _scrub(self, body):
        for name in ("VWORLD_API_KEY", "BUILDING_HUB_API_KEY"):
            key = self._config.get(name, "")
            if key:
                for variant in {key, key.lower(), key.upper(), urllib.parse.quote(key, safe=""), urllib.parse.quote_plus(key)}:
                    body = body.replace(variant, "[REDACTED]")
        return body

    def get(self, provider, parameters):
        endpoint, key_name, query_key = {
            "vworld": (VWORLD_ENDPOINT, "VWORLD_API_KEY", "key"),
            "hub": (HUB_ENDPOINT, "BUILDING_HUB_API_KEY", "serviceKey"),
            "address": (VWORLD_ADDRESS_ENDPOINT, "VWORLD_API_KEY", "key"),
        }[provider]
        key = self._config.get(key_name, "").strip()
        if not key and not self.offline:
            raise SourceError(provider, "KEY_NOT_CONFIGURED")
        if {"key", "serviceKey", "domain"} & parameters.keys():
            raise SourceError(provider, "CREDENTIAL_PARAMETER_OVERRIDE")
        public_parameters = dict(parameters)
        domain = self._config.get("VWORLD_DOMAIN", "").strip() if provider in {"vworld","address"} else ""
        if domain:
            public_parameters["domain"] = domain
        digest = hashlib.sha256(json.dumps([endpoint, public_parameters], sort_keys=True).encode()).hexdigest()
        file = self.cache / f"{provider}-{digest}.json"
        if file.exists():
            record = json.loads(file.read_text(encoding="utf-8"))
            if record["endpoint"] != endpoint or record["parameters"] != public_parameters:
                raise SourceError(provider, "CACHE_METADATA_MISMATCH")
            encoded = json.dumps(record["body"], ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
            if hashlib.sha256(encoded).hexdigest() != record["sanitizedBodySha256"]:
                raise SourceError(provider, "CACHE_CHECKSUM_MISMATCH")
            self.observations.append(record["observedAt"])
            return record["body"]
        if self.offline:
            raise SourceError(provider, "OFFLINE_CACHE_MISS")
        query = dict(public_parameters, **{query_key: key})
        delay = self._interval - (time.monotonic() - self._last)
        if delay > 0:
            time.sleep(delay)
        self._last = time.monotonic()
        self.request_count += 1
        try:
            request = urllib.request.Request(endpoint + "?" + urllib.parse.urlencode(query), headers={"Accept": "application/json"})
            with self._opener.open(request, timeout=25) as response:
                payload = response.read(10_000_001)
        except urllib.error.HTTPError as error:
            status = error.code
            error.close()
            raise SourceError(provider, f"HTTP_{status}") from None
        except Exception:
            raise SourceError(provider, "CONNECTION_FAILED") from None
        if len(payload) > 10_000_000:
            raise SourceError(provider, "RESPONSE_TOO_LARGE")
        sanitized = self._scrub(payload.decode("utf-8-sig"))
        try:
            body = json.loads(sanitized)
        except ValueError:
            try:
                root = ET.fromstring(sanitized)
                allowed = {"INVALID_KEY", "INCORRECT_KEY", "UNAVAILABLE_KEY", "OVER_REQUEST_LIMIT", "PARAM_REQUIRED", "INVALID_RANGE", "INVALID_TYPE"}
                codes = [element.attrib.get("code") for element in root.iter() if element.attrib.get("code") in allowed]
            except ET.ParseError:
                codes = []
            raise SourceError(provider, codes[0] if codes else "NON_JSON_RESPONSE") from None
        if not isinstance(body, dict):
            raise SourceError(provider, "UNEXPECTED_JSON_RESPONSE")
        if provider == "hub":
            code = str(body.get("response", {}).get("header", {}).get("resultCode", ""))
            if code not in {"00", "0"}:
                raise SourceError(provider, "SERVICE_ERROR_" + code if re.fullmatch(r"\d{1,3}", code) else "SERVICE_ERROR")
        elif body.get("type") != "FeatureCollection":
            raise SourceError(provider, "NOT_A_FEATURE_COLLECTION")
        encoded = json.dumps(body, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
        record = {"endpoint": endpoint, "parameters": public_parameters, "observedAt": datetime.now(timezone.utc).isoformat(),
                  "sanitizedBodySha256": hashlib.sha256(encoded).hexdigest(), "body": body}
        file.write_text(json.dumps(record, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
        self.observations.append(record["observedAt"])
        return body


def web_mercator(lon, lat):
    return 6378137 * math.radians(lon), 6378137 * math.log(math.tan(math.pi / 4 + math.radians(lat) / 2))


def wgs84_point(point):
    if len(point) != 2 or not all(isinstance(n, (float, int)) and not isinstance(n, bool) and math.isfinite(n) for n in point):
        raise ValueError("Expected finite two-dimensional Web Mercator coordinates")
    lon = math.degrees(point[0] / 6378137)
    lat = math.degrees(2 * math.atan(math.exp(point[1] / 6378137)) - math.pi / 2)
    if not (124 <= lon <= 132 and 33 <= lat <= 39):
        raise ValueError("Building coordinate is outside the Korean pilot")
    return [lon, lat]


def source_id(feature):
    properties = feature["properties"]
    parts = [properties.get(name) for name in ("src_signgu_code", "src_objectid", "gis_idntfc_no")]
    if any(value is None or not str(value).strip() for value in parts):
        raise SourceError("vworld", "MISSING_STABLE_BUILDING_ID")
    # Public responses can reuse all three IDs for different parcels/versions.
    # Retain those original IDs and distinguish source rows by their full content.
    content = {"properties":properties,"geometry":feature["geometry"]}
    fingerprint = hashlib.sha256(json.dumps(content,sort_keys=True,separators=(",", ":")).encode()).hexdigest()
    return ":".join(map(str, parts)) + ":" + fingerprint


def collect_gis(client, bbox, max_requests=80):
    west, south, east, north = bbox
    if not (124 <= west < east <= 132 and 33 <= south < north <= 39 and east - west <= 0.1 and north - south <= 0.1):
        raise ValueError("Expected a Korean pilot bbox no larger than 0.1 degrees per axis")
    low, high = web_mercator(west, south), web_mercator(east, north)
    selected, requests, multiplicities = {}, [], Counter()

    def visit(bounds, depth=0):
        if len(requests) >= max_requests or depth > 10:
            raise SourceError("vworld", "COLLECTION_LIMIT_REACHED")
        data = client.get("vworld", {"typename": "dt_d010", "bbox": ",".join(map(str, bounds)) + ",EPSG:3857",
            "maxFeatures": "1000", "resultType": "results", "srsName": "EPSG:3857", "output": "application/json"})
        if data.get("crs", {}).get("properties", {}).get("name") not in {"urn:ogc:def:crs:EPSG::3857", "EPSG:3857"}:
            raise SourceError("vworld", "UNEXPECTED_CRS")
        count = int(data["totalFeatures"])
        if count > 25000:
            raise SourceError("vworld", "TOO_MANY_BUILDINGS")
        requests.append({"bbox3857": bounds, "matched": count, "returned": len(data["features"])})
        if count > 1000:
            x1, y1, x2, y2 = bounds
            mx, my = (x1 + x2) / 2, (y1 + y2) / 2
            for box in [(x1,y1,mx,my),(mx,y1,x2,my),(x1,my,mx,y2),(mx,my,x2,y2)]:
                visit(box, depth + 1)
        else:
            if len(data["features"]) != count:
                raise SourceError("vworld", "TRUNCATED_SPATIAL_RESPONSE")
            local_counts = Counter()
            for feature in data["features"]:
                identifier = source_id(feature)
                local_counts[identifier] += 1
                content = {"properties":feature["properties"],"geometry":feature["geometry"]}
                if identifier in selected and selected[identifier] != content:
                    raise SourceError("vworld", "BUILDING_CHANGED_DURING_COLLECTION")
                selected[identifier] = content
            for identifier,multiplicity in local_counts.items():
                multiplicities[identifier] = max(multiplicities[identifier],multiplicity)
        return count

    total = visit((*low, *high))
    if sum(multiplicities.values()) != total:
        raise SourceError("vworld", "SPATIAL_COLLECTION_COUNT_MISMATCH")
    return selected, requests


def hub_pnu(item):
    values = [str(item.get(name, "")).strip() for name in ("sigunguCd", "bjdongCd", "platGbCd", "bun", "ji")]
    city, dong, land, main, sub = values
    if not (re.fullmatch(r"\d{5}",city) and re.fullmatch(r"\d{5}",dong) and land in {"0","1"}
            and re.fullmatch(r"\d{1,4}",main) and re.fullmatch(r"\d{1,4}",sub)):
        return None
    return city + dong + ("1" if land == "0" else "2") + main.zfill(4) + sub.zfill(4)


def collect_hub(client, dongs, max_total=50000):
    selected, audit = {}, []
    overall = 0
    for dong in sorted(dongs):
        if not re.fullmatch(r"\d{10}",dong):
            raise ValueError("Invalid legal-dong code")
        observed = {}
        page = 1
        expected = None
        while True:
            result = client.get("hub", {"sigunguCd":dong[:5], "bjdongCd":dong[5:], "numOfRows":"1000", "pageNo":str(page), "_type":"json"})
            body = result["response"]["body"]
            count = int(body["totalCount"])
            if expected is None:
                expected = count
                overall += count
                if overall > max_total:
                    raise SourceError("hub", "TOO_MANY_RECORDS")
            if expected != count:
                raise SourceError("hub", "SOURCE_CHANGED_DURING_PAGINATION")
            items = (body.get("items") or {}).get("item", [])
            if isinstance(items, dict): items = [items]
            if not items and len(observed) < expected:
                raise SourceError("hub", "TRUNCATED_PAGINATION")
            before = len(observed)
            for item in items:
                identifier = str(item.get("mgmBldrgstPk", "")).strip()
                if not identifier:
                    raise SourceError("hub", "MISSING_BUILDING_ID")
                if identifier in observed and observed[identifier] != item:
                    raise SourceError("hub", "RECORD_CHANGED_DURING_PAGINATION")
                observed[identifier] = item
            if len(observed) == expected: break
            if len(observed) > expected or len(observed) == before or page >= 100:
                raise SourceError("hub", "INCONSISTENT_PAGINATION")
            page += 1
        for identifier,item in observed.items():
            selected[(dong,identifier)] = item
        audit.append({"legalDong":dong,"totalCount":expected,"pages":page})
        print(f"Building HUB {dong}: {expected} records, {page} pages", flush=True)
    return list(selected.values()), audit


def collect_hub_parcels(client, parcels, max_records=500):
    """Refresh a few explicitly identified parcels without a district-wide download."""
    parcels = sorted(set(parcels))
    if not 1 <= len(parcels) <= 8 or any(not re.fullmatch(r"\d{10}[12]\d{8}", p) for p in parcels):
        raise ValueError("Expected 1..8 valid parcel identifiers")
    selected, audit = {}, []
    for parcel in parcels:
        records, expected, page = {}, None, 1
        while True:
            result = client.get("hub", {"sigunguCd":parcel[:5], "bjdongCd":parcel[5:10],
                "platGbCd":"0" if parcel[10]=="1" else "1", "bun":parcel[11:15], "ji":parcel[15:19],
                "numOfRows":"100", "pageNo":str(page), "_type":"json"})
            body = result["response"]["body"]
            total = int(body["totalCount"])
            if expected is None:
                expected = total
            if total != expected or total < 0 or total > max_records:
                raise SourceError("hub", "INCONSISTENT_PARCEL_COUNT")
            items = (body.get("items") or {}).get("item", [])
            if isinstance(items, dict): items = [items]
            before = len(records)
            for item in items:
                identifier = str(item.get("mgmBldrgstPk", "")).strip()
                if not identifier or hub_pnu(item) != parcel:
                    raise SourceError("hub", "PARCEL_FILTER_MISMATCH")
                if identifier in records and records[identifier] != item:
                    raise SourceError("hub", "RECORD_CHANGED_DURING_PAGINATION")
                records[identifier] = item
            if len(records) == expected: break
            if len(records) > expected or len(records) == before or page >= 5:
                raise SourceError("hub", "TRUNCATED_PARCEL_PAGINATION")
            page += 1
        for identifier, record in records.items():
            if identifier in selected and selected[identifier] != record:
                raise SourceError("hub", "AMBIGUOUS_PARCEL_IDENTITY")
            selected[identifier] = record
        audit.append({"pnu":parcel, "totalCount":expected, "pages":page})
    return list(selected.values()), audit


def positive_height(value):
    try:
        number = Decimal(str(value).strip())
        return float(number) if number.is_finite() and 0 < number <= HEIGHT_LIMIT else None
    except (InvalidOperation, ValueError):
        return None


def normalized_area(value):
    try:
        number = Decimal(str(value).strip())
        return number.quantize(Decimal("0.01")) if number.is_finite() and number > 0 else None
    except (InvalidOperation,ValueError):
        return None


def dong_name(value):
    return "".join(str(value or "").split())


def legacy_hub_pk(properties):
    """Convert a GIS register serial using the published *live-register* rule.

    GIS regstr_se_code means general/collective register; it is NOT the
    live/deleted-register digit. This collector reads the live HUB endpoint.
    """
    serial = str(properties.get("buld_idntfc_no") or "").strip()
    city = str(properties.get("src_signgu_code") or "").strip()
    if not re.fullmatch(r"[1-9][0-9]{0,21}",serial) or city not in PILOT_HUB_CITY_CODES:
        return None
    return serial if len(serial)==22 else PILOT_HUB_CITY_CODES[city]+"1"+serial


def pk_crosscheck(properties, record):
    if hub_pnu(record) != str(properties.get("pnu", "")):
        return "PK_PNU_CONFLICT"
    if str(record.get("sigunguCd", "")) != str(properties.get("src_signgu_code", "")):
        return "PK_CITY_CONFLICT"
    area,other_area=normalized_area(properties.get("ar")),normalized_area(record.get("archArea"))
    if area is not None and other_area is not None and area!=other_area:
        return "PK_AREA_CONFLICT"
    name,other_name=dong_name(properties.get("dong_nm")),dong_name(record.get("dongNm"))
    if name and other_name and name!=other_name:
        return "PK_DONG_CONFLICT"
    def floor_count(value):
        try:
            number=Decimal(str(value))
            return number if number.is_finite() and number>0 and number==number.to_integral_value() else None
        except InvalidOperation:
            return None
    floors,other_floors=floor_count(properties.get("ground_floor_co")),floor_count(record.get("grndFlrCnt"))
    if floors is not None and other_floors is not None and floors!=other_floors:
        return "PK_FLOOR_CONFLICT"
    date=str(properties.get("use_confm_de") or "").replace("-", "")
    other_date=str(record.get("useAprDay") or "")
    if re.fullmatch(r"[0-9]{8}",date) and re.fullmatch(r"[0-9]{8}",other_date) and date!=other_date:
        return "PK_APPROVAL_DATE_CONFLICT"
    return "VERIFIED_OFFICIAL_PK"


def match_heights(gis, hub):
    by_parcel = defaultdict(list)
    by_pk = defaultdict(list)
    gis_by_parcel = defaultdict(list)
    for item in hub:
        if hub_pnu(item): by_parcel[hub_pnu(item)].append(item)
        by_pk[str(item.get("mgmBldrgstPk", ""))].append(item)
    pk_uses=Counter(legacy_hub_pk(item["properties"]) for item in gis.values())
    for identifier,item in gis.items():
        gis_by_parcel[str(item["properties"].get("pnu", ""))].append(identifier)
    features, decisions = [], []
    for identifier,item in sorted(gis.items()):
        properties = item["properties"]
        pnu = str(properties.get("pnu", ""))
        area = normalized_area(properties.get("ar"))
        name = dong_name(properties.get("dong_nm"))
        candidates = [record for record in by_parcel[pnu] if area is not None and area == normalized_area(record.get("archArea"))
                      and (not name or name == dong_name(record.get("dongNm")))]
        method = None
        matched = None
        pk=legacy_hub_pk(properties)
        pk_record=None
        if not str(properties.get("buld_idntfc_no") or "").strip():
            pk_status="NO_LEGACY_PK"
        elif pk is None:
            pk_status="INVALID_OR_UNSUPPORTED_PK"
        elif pk_uses[pk]>1:
            pk_status="DUPLICATE_GIS_PK"
        elif not by_pk[pk]:
            pk_status="PK_NOT_FOUND"
        elif len(by_pk[pk])!=1:
            pk_status="AMBIGUOUS_HUB_PK"
        else:
            pk_record=by_pk[pk][0]
            pk_status=pk_crosscheck(properties,pk_record)
            if pk_status=="VERIFIED_OFFICIAL_PK":
                method,matched="OFFICIAL_PK_PNU_ATTRIBUTES",pk_record
        # A contradictory or missing converted PK must never fall back to a
        # different register merely because its parcel/name/area looks similar.
        if pk_status=="NO_LEGACY_PK" and len(candidates) == 1:
            if name and sum(dong_name(gis[other]["properties"].get("dong_nm")) == name for other in gis_by_parcel[pnu]) == 1:
                method = "UNIQUE_PNU_DONG_AREA"
            elif len(by_parcel[pnu]) == 1 and len(gis_by_parcel[pnu]) == 1:
                method = "UNIQUE_PNU_AREA"
            if method: matched = candidates[0]
        height = positive_height(matched.get("heit")) if matched else None
        gis_height=positive_height(properties.get("hg"))
        hub_height=height
        height_conflict=gis_height is not None and hub_height is not None and abs(gis_height-hub_height)>0.01
        if height_conflict:
            # Retain the geometry provider's own value; queue the disagreement.
            height=None
        height_endpoint = HUB_ENDPOINT if height is not None else None
        height_identifier = str(matched["mgmBldrgstPk"]) if height is not None else None
        if height is None:
            height = gis_height
            if height is not None:
                height_endpoint, height_identifier = VWORLD_ENDPOINT, identifier
        geometry = item["geometry"]
        if geometry["type"] == "Polygon":
            coordinates = [[wgs84_point(point) for point in ring] for ring in geometry["coordinates"]]
        elif geometry["type"] == "MultiPolygon":
            coordinates = [[[wgs84_point(point) for point in ring] for ring in polygon] for polygon in geometry["coordinates"]]
        else:
            raise SourceError("vworld", "UNSUPPORTED_BUILDING_GEOMETRY")
        features.append({"type":"Feature","id":"molit-gis:"+identifier,"geometry":{"type":geometry["type"],"coordinates":coordinates},
            "properties":{"heightMeters":height,"heightSource":"ACTUAL" if height is not None else "UNKNOWN",
                "source":VWORLD_ENDPOINT,"sourceId":identifier,"heightSourceReference":height_endpoint,"heightSourceId":height_identifier,
                "pnu":pnu}})
        gap_reason=None
        if height is None:
            if matched:gap_reason="MATCHED_WITHOUT_DIRECT_HEIGHT"
            elif pk_status not in {"NO_LEGACY_PK","PK_NOT_FOUND"}:gap_reason=pk_status
            elif not by_parcel[pnu]:gap_reason="NO_HUB_PARCEL"
            elif not any(positive_height(record.get("heit")) for record in by_parcel[pnu]):gap_reason="NO_DIRECT_HEIGHT_ON_PARCEL"
            else:gap_reason="UNRESOLVED_BUILDING_IDENTITY"
        decisions.append({"sourceId":identifier,"pnu":pnu,"method":method,"hubSourceId":str(matched["mgmBldrgstPk"]) if matched else None,
            "heightSourceReference":height_endpoint,"heightMeters":height,"hubCandidateCount":len(candidates),
            "convertedHubPk":pk,"pkValidation":pk_status,"heightConflict":height_conflict,"heightGapReason":gap_reason,
            "pkCandidatePnu":hub_pnu(pk_record) if pk_record else None,
            "pkCandidateDirectHeightMeters":positive_height(pk_record.get("heit")) if pk_record else None,
            "originalGisIds":{name:properties.get(name) for name in ("src_signgu_code","src_objectid","gis_idntfc_no","buld_idntfc_no")},
            "gisDirectHeightMeters":gis_height,"hubDirectHeightMeters":hub_height})
    return features, decisions
