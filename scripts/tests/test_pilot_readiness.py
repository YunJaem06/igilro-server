import importlib.util
from pathlib import Path
import sys
import unittest
import xml.etree.ElementTree as ET

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from building_sources import collect_hub_parcels, SourceError

spec=importlib.util.spec_from_file_location("pilot_audit",Path(__file__).resolve().parents[1]/"audit-shade-pilot.py")
pilot=importlib.util.module_from_spec(spec)
spec.loader.exec_module(pilot)


class Client:
    def __init__(self, replies): self.replies,self.calls=list(replies),[]
    def get(self, provider, query):
        self.calls.append((provider,query))
        return self.replies.pop(0)


def reply(count,items):
    return {"response":{"body":{"totalCount":count,"items":{"item":items}}}}


def record(identifier=1):
    return {"mgmBldrgstPk":identifier,"sigunguCd":"11200","bjdongCd":"11400","platGbCd":"0","bun":"0685","ji":"0143","heit":0}


class PilotReadinessTest(unittest.TestCase):
    def test_targeted_title_query_preserves_unknown_height_and_paginates(self):
        client=Client([reply(2,[record(1)]),reply(2,[record(2)])])
        records,audit=collect_hub_parcels(client,["1120011400106850143"])
        self.assertEqual([0,0],[item['heit'] for item in records])
        self.assertEqual(2,audit[0]['pages'])
        self.assertEqual('0685',client.calls[0][1]['bun'])
        self.assertEqual('0143',client.calls[0][1]['ji'])
        self.assertEqual('2',client.calls[1][1]['pageNo'])

    def test_an_empty_parcel_response_is_not_a_height_or_physical_inventory_claim(self):
        records,audit=collect_hub_parcels(Client([reply(0,[])]),["1120011400106850143"])
        self.assertEqual([],records)
        self.assertEqual(0,audit[0]['totalCount'])
        self.assertNotIn('inventoryComplete',audit[0])

    def test_ignored_parcel_filters_duplicate_pages_and_changed_counts_are_rejected(self):
        wrong=record();wrong['ji']='0144'
        for replies in [[reply(1,[wrong])],[reply(2,[record()]),reply(2,[record()])],
                        [reply(2,[record()]),reply(3,[record(2)])]]:
            with self.assertRaises(SourceError): collect_hub_parcels(Client(replies),["1120011400106850143"])

    def test_invalid_and_oversized_parcel_queries_fail_before_network(self):
        for parcels in [[],['bad'],['1120011400306850143'],[f'112001140010685{i:04d}' for i in range(9)]]:
            client=Client([])
            with self.assertRaises(ValueError):collect_hub_parcels(client,parcels)
            self.assertEqual([],client.calls)

    def test_structure_screening_does_not_treat_missing_tags_as_verified_ground(self):
        self.assertEqual([],pilot.structure_blockers({'highway':'footway','footway':'sidewalk'}))
        for tags in [{'bridge':'yes'},{'tunnel':'building_passage'},{'covered':'yes'},{'indoor':'yes'},
                     {'layer':'1'},{'level':'-1'},{'location':'elevated'},{'highway':'steps'}]:
            self.assertTrue(pilot.structure_blockers(tags))

    def source(self):
        return ET.fromstring('''<osm><meta osm_base="2026-10-25T21:21:21Z"/>
          <node id="1" lon="127.03" lat="37.54"><tag k="ele" v="10"/></node>
          <node id="2" lon="127.031" lat="37.54"/><node id="3" lon="127.032" lat="37.54"/>
          <way id="10"><nd ref="1"/><nd ref="2"/><nd ref="3"/>
          <tag k="highway" v="footway"/><tag k="surface" v="paving_stones"/></way></osm>''')

    def test_subset_preserves_references_structure_surface_elevation_and_snapshot_time(self):
        source=self.source()
        subset=ET.fromstring(pilot.subset_roads(source,{10}))
        self.assertEqual(3,len(subset.findall('node')))
        self.assertEqual(ET.tostring(source.find('way')),ET.tostring(subset.find('way')))
        self.assertEqual('10',subset.find('node/tag').attrib['v'])
        self.assertEqual(source.find('meta').attrib,subset.find('meta').attrib)

    def test_subset_rejects_missing_nodes_ways_and_unsupported_structures(self):
        for kind in ['node','way','bridge','timestamp']:
            source=self.source()
            if kind=='node':source.remove(source.find('node'))
            elif kind=='way':source.remove(source.find('way'))
            elif kind=='bridge':ET.SubElement(source.find('way'),'tag',k='bridge',v='yes')
            else:source.remove(source.find('meta'))
            with self.assertRaises(ValueError):pilot.subset_roads(source,{10})

    def test_field_sheet_escapes_source_text_and_labels_all_results_as_pending(self):
        report={'groundStations':[{'geometry':{'coordinates':[127.03,37.54]},'properties':{'station':0}}],
            'roads':[],'excludedRoads':[],'osmWithin100m':[],
            'freshBuildingsWithin200m':[{'pnu':'1120011400106850143','sourceId':'fixture','dongName':None,
                'name':'<script>untrusted()</script>','match':{'heightMeters':None}}]}
        page=pilot.field_html(report,{'features':[]},{'features':[]})
        self.assertIn('&lt;script&gt;untrusted()&lt;/script&gt;',page)
        self.assertNotIn('<script>',page)
        self.assertIn('default-src',page)
        self.assertIn('실제 그늘 경로 검색 미개통',page)


if __name__=='__main__':unittest.main()
