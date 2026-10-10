#!/usr/bin/env python3
"""Extract a reproducible highway/node XML snapshot from a local Geofabrik PBF.

This prepares input only. JVM ingestion owns foot permissions and graph construction.
"""
import argparse
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET

import osmium


def extract(pbf, output, bbox, source):
    west, south, east, north = bbox
    if not (-180 <= west < east <= 180 and -90 <= south < north <= 90):
        raise ValueError("Invalid WGS84 bbox")
    if east - west > 0.1 or north - south > 0.1:
        raise ValueError("Pilot bbox must be <= 0.1 degrees per axis")
    if output.exists():
        raise FileExistsError(f"Retaining existing extract: {output}. Choose a new output path.")
    reader = osmium.io.Reader(str(pbf))
    timestamp = reader.header().get("osmosis_replication_timestamp")
    reader.close()
    if not timestamp:
        raise ValueError("PBF replication timestamp is required")
    nodes = {}
    ways = {}

    class Select(osmium.SimpleHandler):
        def node(self, node):
            if west <= node.lon <= east and south <= node.lat <= north:
                nodes[node.id] = (node.lon, node.lat, dict(node.tags))

        def way(self, way):
            if "highway" not in way.tags:
                return
            refs = [node.ref for node in way.nodes]
            if any(ref in nodes for ref in refs):
                ways[way.id] = (refs, dict(way.tags))

    print("Selecting highways in pilot bbox...", flush=True)
    Select().apply_file(str(pbf))
    required = {ref for refs, _ in ways.values() for ref in refs}
    missing = required - nodes.keys()
    if len(required) > 150_000 or len(ways) > 50_000:
        raise ValueError("Extract exceeds JVM input limits")

    class Complete(osmium.SimpleHandler):
        def node(self, node):
            if node.id in missing:
                nodes[node.id] = (node.lon, node.lat, dict(node.tags))

    if missing:
        print(f"Completing {len(missing)} referenced boundary nodes...", flush=True)
        Complete().apply_file(str(pbf))
    if required - nodes.keys():
        raise ValueError("Incomplete way references")
    root = ET.Element("osm", version="0.6", generator="igilro-geofabrik-extract-v1")
    ET.SubElement(root, "note").text = "© OpenStreetMap contributors; ODbL 1.0; " + source
    ET.SubElement(root, "meta", osm_base=timestamp)
    for node_id in sorted(required):
        lon, lat, tags = nodes[node_id]
        element = ET.SubElement(root, "node", id=str(node_id), lon=f"{lon:.7f}", lat=f"{lat:.7f}")
        for key, value in sorted(tags.items()):
            ET.SubElement(element, "tag", k=key, v=value)
    for way_id, (refs, tags) in sorted(ways.items()):
        element = ET.SubElement(root, "way", id=str(way_id))
        for ref in refs:
            ET.SubElement(element, "nd", ref=str(ref))
        for key, value in sorted(tags.items()):
            ET.SubElement(element, "tag", k=key, v=value)
    xml = ET.tostring(root, encoding="utf-8", xml_declaration=True)
    if len(xml) > 20_000_000:
        raise ValueError("Extract exceeds 20 MB")
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + ".tmp")
    temporary.write_bytes(xml)
    temporary.replace(output)
    metadata = {
        "source": source, "sourceTimestamp": timestamp, "bbox": bbox,
        "xmlSha256": hashlib.sha256(xml).hexdigest(),
        "nodeCount": len(required), "wayCount": len(ways),
    }
    with pbf.open("rb") as stream:
        digest = hashlib.sha256()
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
        metadata["pbfSha256"] = digest.hexdigest()
    output.with_suffix(output.suffix + ".metadata.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(metadata, indent=2), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pbf", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path("data/osm/seoul-forest.osm"))
    parser.add_argument("--bbox", type=float, nargs=4, metavar=("WEST", "SOUTH", "EAST", "NORTH"), default=[127.027,37.533,127.055,37.558])
    parser.add_argument("--source", default="https://download.geofabrik.de/asia/south-korea-latest.osm.pbf")
    args = parser.parse_args()
    extract(args.pbf, args.output, args.bbox, args.source)


if __name__ == "__main__":
    main()
