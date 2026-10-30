import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

SCRIPTS=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(SCRIPTS))
from building_sources import VWORLD_ENDPOINT,match_heights,source_id,web_mercator

spec=importlib.util.spec_from_file_location("building_review_builder",SCRIPTS/"build-building-review.py")
builder=importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


class BuildingReviewTest(unittest.TestCase):
    def fixture(self,root):
        cache=root/"responses";cache.mkdir()
        x,y=web_mercator(127.04,37.54)
        raw={"type":"Feature","properties":{"src_signgu_code":"11200","src_objectid":1,"gis_idntfc_no":"gis-id",
            "pnu":"1120011400100030035","ar":0,"hg":0,"buld_nm":"</script><script>fixture()</script>"},
            "geometry":{"type":"Polygon","coordinates":[[[x,y],[x+10,y],[x+10,y+10],[x,y+10],[x,y]]]}}
        body={"type":"FeatureCollection","features":[raw]}
        encoded=json.dumps(body,ensure_ascii=False,sort_keys=True,separators=(",",":")).encode()
        (cache/"vworld-fixture.json").write_text(json.dumps({"endpoint":VWORLD_ENDPOINT,"body":body,
            "sanitizedBodySha256":hashlib.sha256(encoded).hexdigest()}),encoding="utf-8")
        features,audit=match_heights({source_id(raw):raw},[])
        inventory={"features":features,"metadata":{"sourceTimestamp":"2026-10-26T07:00:00Z","attribution":"fixture attribution",
            "inventoryComplete":False,"maximumHeightMeters":None}}
        payload=json.dumps(inventory).encode()
        version="buildings-v1-"+hashlib.sha256(payload).hexdigest()
        (root/"buildings.validated.geojson").write_bytes(payload)
        (root/"buildings.validated.audit.json").write_text(json.dumps({"dataVersion":version}),encoding="utf-8")
        (root/"matching-audit.json").write_text(json.dumps(audit),encoding="utf-8")
        route={"geometry":{"type":"LineString","coordinates":[[127.04,37.54],[127.041,37.541]]},
            "distanceMeters":140,"durationSeconds":100,"shadeEvaluation":{"dataVersion":version,"status":"INCOMPLETE_COVERAGE"}}
        (root/"route.json").write_text(json.dumps({"routes":[route],"evaluatedAt":"2026-10-26T03:00:00Z"}),encoding="utf-8")
        (root/"roads.osm").write_text('<osm><node id="1" lat="37.54" lon="127.04"/><node id="2" lat="37.541" lon="127.041"/><way id="1"><nd ref="1"/><nd ref="2"/><tag k="highway" v="footway"/></way></osm>',encoding="utf-8")
        return cache

    def test_untrusted_strings_cannot_close_embedded_json_or_add_executable_markup(self):
        raw={"name":"</script><script>fixture()</script>&\u2028\u2029"}
        escaped=builder.safe_embedded_json(raw)
        self.assertNotIn("<",escaped)
        self.assertNotIn("&",escaped)
        self.assertEqual(raw,json.loads(escaped))
        page=builder.render_html(raw)
        self.assertEqual(2,page.count("<script "))
        self.assertNotIn("<script>fixture()",page)
        self.assertIn("connect-src 'none'",page)

    def test_payload_preserves_unknown_height_and_source_fields_without_loading_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);cache=self.fixture(root)
            payload=builder.build_payload(root,root/"route.json",root/"roads.osm",cache)
            self.assertIsNone(payload["buildings"][0]["height"])
            self.assertEqual(1,payload["counts"]["unknown"])
            self.assertEqual(0,payload["counts"]["known"])
            self.assertEqual(1,len(payload["roads"]))
            self.assertEqual("NO_LEGACY_PK",payload["buildings"][0]["audit"]["pkValidation"])
            self.assertAlmostEqual(127.04,payload["buildings"][0]["polygons"][0][0][0][0],places=7)

    def test_mismatched_route_version_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);cache=self.fixture(root)
            response=builder.load_json(root/"route.json")
            response["routes"][0]["shadeEvaluation"]["dataVersion"]="different-version"
            (root/"route.json").write_text(json.dumps(response),encoding="utf-8")
            with self.assertRaisesRegex(ValueError,"different building version"):
                builder.build_payload(root,root/"route.json",root/"roads.osm",cache)

    def test_tampered_inventory_and_source_cache_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);cache=self.fixture(root)
            path=cache/"vworld-fixture.json";record=builder.load_json(path)
            record["body"]["features"][0]["properties"]["hg"]=90
            path.write_text(json.dumps(record),encoding="utf-8")
            with self.assertRaisesRegex(ValueError,"cache checksum"):
                builder.build_payload(root,root/"route.json",root/"roads.osm",cache)
            (root/"buildings.validated.geojson").write_text("{}",encoding="utf-8")
            with self.assertRaisesRegex(ValueError,"inventory checksum"):
                builder.build_payload(root,root/"route.json",root/"roads.osm",cache)

    def test_audit_height_disagreement_and_non_wgs84_geometry_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);cache=self.fixture(root)
            audit=builder.load_json(root/"matching-audit.json");audit[0]["heightMeters"]=12
            (root/"matching-audit.json").write_text(json.dumps(audit),encoding="utf-8")
            with self.assertRaisesRegex(ValueError,"height differ"):
                builder.build_payload(root,root/"route.json",root/"roads.osm",cache)
            with self.assertRaisesRegex(ValueError,"outside the Korean pilot"):
                builder.checked_point([14140000,4513000])

    def test_pilot_overlay_cannot_import_another_snapshot_or_claim_approval(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);cache=self.fixture(root)
            plan=root/"plan.json"
            plan.write_text(json.dumps({"publicVersion":"another-version","graphVersion":None,"authorizedAreaCount":0}),encoding="utf-8")
            with self.assertRaisesRegex(ValueError,"versions"):
                builder.build_payload(root,root/"route.json",root/"roads.osm",cache,plan)
            version=builder.load_json(root/"buildings.validated.audit.json")["dataVersion"]
            plan.write_text(json.dumps({"publicVersion":version,"graphVersion":None,"authorizedAreaCount":0,
                "candidates":[{"readyForShadeApi":True}]}),encoding="utf-8")
            with self.assertRaisesRegex(ValueError,"approved"):
                builder.build_payload(root,root/"route.json",root/"roads.osm",cache,plan)


if __name__=="__main__":
    unittest.main()
