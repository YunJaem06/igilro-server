import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest

SCRIPTS=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(SCRIPTS))
spec=importlib.util.spec_from_file_location("shade_pilot_planner",SCRIPTS/"plan-shade-pilot.py")
planner=importlib.util.module_from_spec(spec)
spec.loader.exec_module(planner)


class ShadePilotTest(unittest.TestCase):
    def row(self,**extra):
        return {"unknown_audit":0,"unknown_inspection":0,"conflicts_audit":0,"inspection_inside_source":True,
                "total_audit":0,"edge_id":1,"x":0,"y":0,**extra}

    def test_empty_or_known_height_only_window_does_not_prove_inventory_or_height_bound(self):
        for row in [self.row(),self.row(total_audit=3)]:
            blockers=planner.coverage_blockers(row)
            self.assertIn("INVENTORY_NOT_VERIFIED",blockers)
            self.assertIn("HEIGHT_BOUND_NOT_VERIFIED",blockers)

    def test_unknown_surroundings_remain_a_blocker_when_inner_window_has_no_missing_heights(self):
        blockers=planner.coverage_blockers(self.row(unknown_inspection=13),True,100)
        self.assertEqual(["UNKNOWN_HEIGHTS_IN_SURROUNDINGS"],blockers)

    def test_bad_height_bounds_cannot_authorize_a_window(self):
        for bound in [None,True,0,-1,501,float("nan"),"100"]:
            self.assertIn("HEIGHT_BOUND_NOT_VERIFIED",planner.coverage_blockers(self.row(),True,bound))

    def test_known_blockers_include_cross_source_gaps_structure_conflicts_and_source_boundary(self):
        blockers=planner.coverage_blockers(self.row(unknown_audit=1,unknown_inspection=1,conflicts_audit=1,
            inspection_inside_source=False,osm_unmatched=1),True,100)
        self.assertEqual({"UNKNOWN_HEIGHTS_IN_AUDIT_AREA","MATCHING_CONFLICTS_IN_AUDIT_AREA",
            "INSPECTION_EXTENDS_BEYOND_SOURCE","OSM_FOOTPRINTS_REQUIRE_REVIEW"},set(blockers))

    def test_bridges_tunnels_roofed_indoor_and_nonzero_levels_are_excluded(self):
        tags=["",'<tag k="bridge" v="yes"/>','<tag k="tunnel" v="building_passage"/>',
              '<tag k="covered" v="yes"/>','<tag k="indoor" v="yes"/>','<tag k="layer" v="2"/>',
              '<tag k="level" v="1"/>','<tag k="location" v="rooftop"/>','<tag k="bridge" v="no"/>']
        with tempfile.TemporaryDirectory() as directory:
            file=Path(directory)/"roads.osm"
            file.write_text('<osm>'+''.join(f'<way id="{index+1}"><tag k="highway" v="footway"/>{tag}</way>' for index,tag in enumerate(tags))+'</osm>',encoding="utf-8")
            accepted,count=planner.ground_way_ids(file)
            self.assertEqual([1,9],accepted)
            self.assertEqual(len(tags),count)

    def test_selection_prefers_building_review_over_empty_controls_and_keeps_candidates_apart(self):
        rows=[self.row(edge_id=1),self.row(edge_id=2,total_audit=3,x=10),self.row(edge_id=3,total_audit=1,x=500)]
        selected=planner.select_distinct(rows,2,100)
        self.assertEqual([2,3],[row["edge_id"] for row in selected])


if __name__=="__main__":
    unittest.main()
