import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

import osmium

spec = importlib.util.spec_from_file_location("prepare_osm", Path(__file__).parents[1] / "prepare-osm.py")
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


class PrepareOsmTest(unittest.TestCase):
    def make_pbf(self, directory, with_timestamp=True):
        file = directory / "source.osm.pbf"
        header = osmium.io.Header()
        if with_timestamp:
            header.set("osmosis_replication_timestamp", "2026-10-01T00:00:00Z")
        with osmium.SimpleWriter(str(file), header=header) as writer:
            writer.add_node(osmium.osm.mutable.Node(id=1, location=(127.03,37.54), tags={"barrier":"bollard"}))
            writer.add_node(osmium.osm.mutable.Node(id=2, location=(127.04,37.54)))
            writer.add_node(osmium.osm.mutable.Node(id=3, location=(127.06,37.54), tags={"access":"private"}))
            writer.add_node(osmium.osm.mutable.Node(id=4, location=(128.0,38.0)))
            writer.add_node(osmium.osm.mutable.Node(id=5, location=(128.1,38.0)))
            writer.add_way(osmium.osm.mutable.Way(id=101, nodes=[1,2,3], tags={"highway":"footway"}))
            writer.add_way(osmium.osm.mutable.Way(id=102, nodes=[4,5], tags={"highway":"footway"}))
        return file

    def extract(self, file, output, bbox=None):
        with contextlib.redirect_stdout(io.StringIO()):
            prepare.extract(file, output, bbox or [127.02,37.53,127.05,37.56], "synthetic-fixture")

    def test_complete_way_references_and_node_access_survive_bbox_selection(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            output = directory / "extract.osm"
            self.extract(self.make_pbf(directory), output)
            root = ET.parse(output).getroot()
            self.assertEqual({"1","2","3"}, {n.attrib["id"] for n in root.findall("node")})
            self.assertEqual(["101"], [w.attrib["id"] for w in root.findall("way")])
            self.assertEqual("private", root.find("node[@id='3']/tag").attrib["v"])
            self.assertEqual("2026-10-01T00:00:00Z", root.find("meta").attrib["osm_base"])
            metadata = json.loads(output.with_suffix(".osm.metadata.json").read_text())
            self.assertEqual(64,len(metadata["pbfSha256"]))

    def test_preparation_is_reproducible_and_does_not_overwrite_an_existing_extract(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            file = self.make_pbf(directory)
            one, two = directory / "one.osm", directory / "two.osm"
            self.extract(file,one)
            self.extract(file,two)
            self.assertEqual(one.read_bytes(),two.read_bytes())
            with self.assertRaises(FileExistsError):
                self.extract(file,one)

    def test_missing_snapshot_timestamp_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            with self.assertRaises(ValueError):
                self.extract(self.make_pbf(directory,False), directory / "out.osm")

    def test_unbounded_or_invalid_bbox_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            for bbox in ([127,37,129,39],[127.1,37,127,38]):
                with self.assertRaises(ValueError):
                    self.extract(directory / "unused.pbf", directory / "out.osm", bbox)


if __name__ == "__main__":
    unittest.main()
