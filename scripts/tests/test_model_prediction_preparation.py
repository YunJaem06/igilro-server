import importlib.util
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET
import sys

sys.path.insert(0, str(Path(__file__).parents[1]))
spec = importlib.util.spec_from_file_location('prepare_model_prediction', Path(__file__).parents[1]/'prepare-model-prediction.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PredictionPreparationTest(unittest.TestCase):
    def test_extraction_preserves_source_topology_tags_and_access_restrictions(self):
        xml = b'''<osm><meta osm_base="2026-10-25T00:00:00Z"/>
        <node id="1" lon="127.037" lat="37.544"/><node id="2" lon="127.038" lat="37.544"/>
        <node id="3" lon="127.039" lat="37.544"/>
        <way id="10"><nd ref="1"/><nd ref="2"/><tag k="highway" v="footway"/><tag k="access" v="private"/></way>
        <way id="11"><nd ref="2"/><nd ref="3"/><tag k="highway" v="footway"/><tag k="bridge" v="yes"/></way></osm>'''
        result, excluded = module.extract_surface_roads(xml, [127.036, 37.543, 127.040, 37.545])
        root = ET.fromstring(result)
        self.assertEqual(['11'], excluded)
        self.assertEqual(['1','2'], [n.attrib['id'] for n in root.findall('node')])
        self.assertEqual(['10'], [w.attrib['id'] for w in root.findall('way')])
        self.assertEqual('private', root.find("way/tag[@k='access']").attrib['v'])
        self.assertEqual(['1','2'], [n.attrib['ref'] for n in root.findall('way/nd')])

    def test_unsupported_elevated_and_underground_structures_are_excluded(self):
        for tag in [{'highway':'steps'}, {'layer':'1'}, {'tunnel':'yes'}, {'covered':'yes'}, {'indoor':'yes'}, {'level':'-1'}]:
            self.assertFalse(module.surface_way(tag))
        self.assertTrue(module.surface_way({'highway':'footway', 'layer':'0', 'bridge':'no'}))

    def test_park_projection_is_independently_accurate(self):
        projection = module.projection_fit([127.0288,37.5371,127.0482,37.5529])
        self.assertEqual(6, len(projection['coefficients']))
        self.assertLess(projection['checkedMaxErrorMeters'], .001)
