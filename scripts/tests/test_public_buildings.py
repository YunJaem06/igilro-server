import copy
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from building_sources import (HUB_ENDPOINT, VWORLD_ENDPOINT, PublicBuildingClient, SourceError, collect_gis,
                             collect_hub, hub_pnu, legacy_hub_pk, match_heights, positive_height, settings, source_id, web_mercator)


def feature(parcel="1120011400100030035", height=0):
    x,y=web_mercator(127.04,37.54)
    return {"id":"unstable-request-fid", "type":"Feature",
        "properties":{"src_signgu_code":"11200","src_objectid":1,"gis_idntfc_no":"public-gis-id",
                      "pnu":parcel,"ar":100,"dong_nm":"1동","hg":height},
        "geometry":{"type":"Polygon","coordinates":[[[x,y],[x+10,y],[x+10,y+10],[x,y+10],[x,y]]]}}


def hub(identifier="123", height=12.5):
    return {"mgmBldrgstPk":identifier,"sigunguCd":"11200","bjdongCd":"11400","platGbCd":"0",
            "bun":"0003","ji":"0035","archArea":"100.00","dongNm":"1동","heit":height,"grndFlrCnt":4}


class FakeOpener:
    def __init__(self, body):
        self.body,self.calls=body,[]
    def open(self,request,**kwargs):
        self.calls.append(request)
        return io.BytesIO(self.body)


class PublicBuildingsTest(unittest.TestCase):
    def test_small_non_subdivided_bbox_retains_the_provider_total_count(self):
        first,second=feature(),feature()
        second['properties']['src_objectid']=2
        payload={"type":"FeatureCollection","totalFeatures":2,
            "crs":{"properties":{"name":"EPSG:3857"}},"features":[first,second]}
        with tempfile.TemporaryDirectory() as directory:
            client=PublicBuildingClient({'VWORLD_API_KEY':'fixture-key'},directory,FakeOpener(json.dumps(payload).encode()),interval=0)
            buildings,audit=collect_gis(client,[127.03,37.54,127.04,37.55])
            self.assertEqual(2,len(buildings))
            self.assertEqual(2,audit[0]['matched'])
            self.assertEqual(1,client.request_count)

    def test_credentials_are_not_sent_without_configuration(self):
        with tempfile.TemporaryDirectory() as directory:
            opener=FakeOpener(b"{}")
            client=PublicBuildingClient({},directory,opener,interval=0)
            with self.assertRaises(SourceError) as error: client.get("vworld",{})
            self.assertEqual("KEY_NOT_CONFIGURED",error.exception.code)
            self.assertEqual([],opener.calls)

    def test_address_reference_reuses_vworld_credentials_without_persisting_them(self):
        with tempfile.TemporaryDirectory() as directory:
            opener=FakeOpener(b'{"type":"FeatureCollection","features":[]}')
            client=PublicBuildingClient({"VWORLD_API_KEY":"fixture-address-key","VWORLD_DOMAIN":"http://localhost"},directory,opener,interval=0)
            client.get("address",{"typename":"lt_c_spbd"})
            self.assertIn("https://api.vworld.kr/req/wfs?",opener.calls[0].full_url)
            cached=next(Path(directory).glob("address-*.json")).read_text(encoding="utf-8")
            self.assertNotIn("fixture-address-key",cached)
            self.assertEqual("http://localhost",json.loads(cached)["parameters"]["domain"])

    def test_authenticated_url_is_not_saved_and_provider_echoes_are_redacted(self):
        secret="fixture-secret-credential"
        payload=json.dumps({"type":"FeatureCollection","features":[],"echo":secret}).encode()
        opener=FakeOpener(payload)
        with tempfile.TemporaryDirectory() as directory:
            client=PublicBuildingClient({"VWORLD_API_KEY":secret},directory,opener,interval=0)
            first=client.get("vworld",{"typename":"dt_d010"})
            self.assertEqual("[REDACTED]",first["echo"])
            self.assertEqual(first,client.get("vworld",{"typename":"dt_d010"}))
            self.assertEqual(1,len(opener.calls))
            cached=next(Path(directory).glob("*.json"))
            text=cached.read_text(encoding="utf-8")
            self.assertNotIn(secret,text)
            self.assertNotIn("key=",text)
            record=json.loads(text)
            self.assertEqual(VWORLD_ENDPOINT,record["endpoint"])
            record["body"]["features"]=[{"tampered":True}]
            cached.write_text(json.dumps(record),encoding="utf-8")
            with self.assertRaises(SourceError) as error:client.get("vworld",{"typename":"dt_d010"})
            self.assertEqual("CACHE_CHECKSUM_MISMATCH",error.exception.code)

    def test_error_messages_omit_credentials_and_authenticated_urls(self):
        key="fixture-private-key"
        payload=('<ServiceExceptionReport><ServiceException code="INCORRECT_KEY">'+key+'</ServiceException></ServiceExceptionReport>').encode()
        with tempfile.TemporaryDirectory() as directory:
            client=PublicBuildingClient({"VWORLD_API_KEY":key},directory,FakeOpener(payload),interval=0)
            with self.assertRaises(SourceError) as error:client.get("vworld",{})
            self.assertEqual("INCORRECT_KEY",error.exception.code)
            self.assertNotIn(key,str(error.exception))
            self.assertEqual([],list(Path(directory).glob("*.json")))

    def test_offline_reprocessing_needs_no_key_and_never_calls_the_network(self):
        with tempfile.TemporaryDirectory() as directory:
            opener=FakeOpener(b'{"type":"FeatureCollection","features":[]}')
            client=PublicBuildingClient({"VWORLD_API_KEY":"fixture-key"},directory,opener,interval=0)
            expected=client.get("vworld",{"typename":"dt_d010"})
            offline_opener=FakeOpener(b"should never be read")
            offline=PublicBuildingClient({},directory,offline_opener,offline=True)
            self.assertEqual(expected,offline.get("vworld",{"typename":"dt_d010"}))
            self.assertEqual(client.observations,offline.observations)
            with self.assertRaises(SourceError) as error:offline.get("hub",{})
            self.assertEqual("OFFLINE_CACHE_MISS",error.exception.code)
            self.assertEqual([],offline_opener.calls)

    def test_reused_public_identifiers_do_not_collapse_different_source_rows(self):
        one=feature()
        two=feature(parcel="1120011400100030036")
        self.assertNotEqual(source_id(one),source_id(two))
        other_fid=copy.deepcopy(one)
        other_fid["id"]="another-unstable-request-fid"
        self.assertEqual(source_id(one),source_id(other_fid))

    def test_zero_or_missing_height_is_never_derived_from_floors(self):
        for value in (None,0,"0","",-1,"NaN",501):self.assertIsNone(positive_height(value))
        item=feature()
        features,decisions=match_heights({source_id(item):item},[hub(height=0)])
        self.assertIsNone(features[0]["properties"]["heightMeters"])
        self.assertEqual("UNKNOWN",features[0]["properties"]["heightSource"])
        self.assertIsNone(decisions[0]["heightSourceReference"])

    def test_unique_parcel_name_and_area_preserve_independent_geometry_and_height_sources(self):
        item=feature(height=0)
        features,audit=match_heights({source_id(item):item},[hub()])
        properties=features[0]["properties"]
        self.assertEqual(12.5,properties["heightMeters"])
        self.assertEqual("ACTUAL",properties["heightSource"])
        self.assertEqual(VWORLD_ENDPOINT,properties["source"])
        self.assertEqual(HUB_ENDPOINT,properties["heightSourceReference"])
        self.assertEqual("123",properties["heightSourceId"])
        self.assertEqual("UNIQUE_PNU_DONG_AREA",audit[0]["method"])
        point=features[0]["geometry"]["coordinates"][0][0]
        self.assertAlmostEqual(127.04,point[0],places=9)
        self.assertAlmostEqual(37.54,point[1],places=9)

    def test_published_pk_rule_uses_live_register_digit_and_preserves_22_digit_serial(self):
        props=feature()["properties"]|{"buld_idntfc_no":"123","regstr_se_code":"2"}
        self.assertEqual("10051123",legacy_hub_pk(props))
        props["buld_idntfc_no"]="1000000000000001047965"
        self.assertEqual("1000000000000001047965",legacy_hub_pk(props))
        for value in ("0","00123","1e5","123-45","-1","12345678901234567890123"):
            self.assertIsNone(legacy_hub_pk(props|{"buld_idntfc_no":value}))
        self.assertIsNone(legacy_hub_pk(props|{"src_signgu_code":"99999"}))

    def test_official_pk_matches_even_when_both_building_areas_are_missing(self):
        item=feature();item["properties"].update({"buld_idntfc_no":"123","ar":0})
        record=hub("10051123");record["archArea"]=0
        features,audit=match_heights({source_id(item):item},[record])
        self.assertEqual(12.5,features[0]["properties"]["heightMeters"])
        self.assertEqual("OFFICIAL_PK_PNU_ATTRIBUTES",audit[0]["method"])
        self.assertEqual("VERIFIED_OFFICIAL_PK",audit[0]["pkValidation"])

    def test_pk_conflicts_and_duplicate_source_assignments_cannot_fall_back_to_area_match(self):
        for change,reason in [({"bun":"0004"},"PK_PNU_CONFLICT"),({"archArea":200},"PK_AREA_CONFLICT"),
            ({"dongNm":"2동"},"PK_DONG_CONFLICT"),({"grndFlrCnt":5},"PK_FLOOR_CONFLICT"),
            ({"useAprDay":"20010102"},"PK_APPROVAL_DATE_CONFLICT")]:
            item=feature();item["properties"].update({"buld_idntfc_no":"123","ground_floor_co":4,"use_confm_de":"2001-01-01"})
            correct=hub("10051123")|change
            features,audit=match_heights({source_id(item):item},[correct,hub("another-area-match")])
            self.assertIsNone(features[0]["properties"]["heightMeters"])
            self.assertEqual(reason,audit[0]["pkValidation"])
        item=feature();item["properties"]["buld_idntfc_no"]="123"
        other=copy.deepcopy(item);other["properties"]["src_objectid"]=2
        _,audit=match_heights({source_id(x):x for x in [item,other]},[hub("10051123")])
        self.assertTrue(all(x["pkValidation"]=="DUPLICATE_GIS_PK" and x["heightMeters"] is None for x in audit))

    def test_missing_converted_pk_does_not_select_a_different_register(self):
        item=feature();item["properties"]["buld_idntfc_no"]="123"
        features,audit=match_heights({source_id(item):item},[hub("different-pk")])
        self.assertIsNone(features[0]["properties"]["heightMeters"])
        self.assertEqual("PK_NOT_FOUND",audit[0]["pkValidation"])

    def test_conflicting_direct_heights_retain_original_gis_value_and_create_review_flag(self):
        item=feature(height=9)
        features,audit=match_heights({source_id(item):item},[hub(height=12.5)])
        self.assertEqual(9,features[0]["properties"]["heightMeters"])
        self.assertTrue(audit[0]["heightConflict"])
        self.assertEqual(12.5,audit[0]["hubDirectHeightMeters"])

    def test_ambiguous_hub_records_do_not_replace_direct_gis_height(self):
        item=feature(height=9)
        features,audit=match_heights({source_id(item):item},[hub("123"),hub("124")])
        self.assertEqual(9,features[0]["properties"]["heightMeters"])
        self.assertEqual(VWORLD_ENDPOINT,features[0]["properties"]["heightSourceReference"])
        self.assertIsNone(audit[0]["method"])
        self.assertEqual(2,audit[0]["hubCandidateCount"])

    def test_same_area_or_nearby_address_without_same_parcel_is_not_a_match(self):
        item=feature(parcel="1120011400100030036")
        features,audit=match_heights({source_id(item):item},[hub()])
        self.assertIsNone(features[0]["properties"]["heightMeters"])
        self.assertIsNone(audit[0]["method"])
        self.assertEqual("1120011400100030035",hub_pnu(hub()))

    def test_truncated_or_nonadvancing_hub_pages_are_rejected(self):
        class Client:
            def get(self,provider,query):
                items=[hub()] if query["pageNo"]=="1" else []
                return {"response":{"body":{"totalCount":"2","items":{"item":items}}}}
        with self.assertRaises(SourceError) as error:collect_hub(Client(),{"1120011400"})
        self.assertEqual("TRUNCATED_PAGINATION",error.exception.code)

    def test_spatial_response_truncation_is_rejected_before_accepting_an_inventory(self):
        class Client:
            def get(self,provider,query):
                return {"type":"FeatureCollection","totalFeatures":2,"features":[feature()],
                        "crs":{"properties":{"name":"urn:ogc:def:crs:EPSG::3857"}}}
        with self.assertRaises(SourceError) as error:collect_gis(Client(),[127.03,37.53,127.04,37.54])
        self.assertEqual("TRUNCATED_SPATIAL_RESPONSE",error.exception.code)

    def test_spatial_partition_keeps_boundary_rows_once_and_verifies_the_root_count(self):
        records=[]
        for index in range(1001):
            record=feature()
            record["properties"]["src_objectid"]=index
            records.append(record)
        class Client:
            def __init__(self):self.calls=0
            def get(self,provider,query):
                pages=[records[:1000],records[:400],records[399:800],records[800:],[]]
                features=pages[self.calls]
                count=1001 if self.calls==0 else len(features)
                self.calls+=1
                return {"totalFeatures":count,"features":features,"crs":{"properties":{"name":"EPSG:3857"}}}
        selected,audit=collect_gis(Client(),[127.03,37.53,127.04,37.54])
        self.assertEqual(1001,len(selected))
        self.assertEqual(5,len(audit))

    def test_hub_accepts_smaller_actual_pages_and_rejects_a_changed_record(self):
        class Client:
            def __init__(self,change=False):self.change=change
            def get(self,provider,query):
                page=int(query["pageNo"])
                items=[hub("1")] if page==1 else [hub("1",height=99),hub("2")] if self.change else [hub("2")]
                return {"response":{"body":{"totalCount":2,"items":{"item":items}}}}
        records,audit=collect_hub(Client(),{"1120011400"})
        self.assertEqual(2,len(records))
        self.assertEqual(2,audit[0]["pages"])
        with self.assertRaises(SourceError) as error:collect_hub(Client(change=True),{"1120011400"})
        self.assertEqual("RECORD_CHANGED_DURING_PAGINATION",error.exception.code)

    def test_environment_overrides_local_file_without_executing_shell_contents(self):
        with tempfile.TemporaryDirectory() as directory:
            file=Path(directory)/".env"
            file.write_text("VWORLD_API_KEY=fixture-file-key\nBUILDING_HUB_API_KEY=literal-$(command)\n",encoding="utf-8")
            loaded=settings(file,{"VWORLD_API_KEY":"fixture-env-key"})
            self.assertEqual("fixture-env-key",loaded["VWORLD_API_KEY"])
            self.assertEqual("literal-$(command)",loaded["BUILDING_HUB_API_KEY"])
