#!/usr/bin/env python3
"""Compare two local matching snapshots and write a prioritized review queue."""
import argparse
from collections import Counter
import json
from pathlib import Path


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline",type=Path,required=True)
    parser.add_argument("--reviewed",type=Path,required=True)
    args=parser.parse_args()
    before={row["sourceId"]:row for row in json.loads((args.baseline/"matching-audit.json").read_text(encoding="utf-8"))}
    after={row["sourceId"]:row for row in json.loads((args.reviewed/"matching-audit.json").read_text(encoding="utf-8"))}
    if before.keys()!=after.keys():
        parser.error("Source row identities differ; compare the same geometry snapshot only.")
    target=args.reviewed/"matching-review.json"
    if target.exists():
        parser.error("Retaining existing audit output.")
    changes=[]
    queue=[]
    for identifier,row in after.items():
        old=before[identifier]
        if old["heightMeters"]!=row["heightMeters"]:
            changes.append({"sourceId":identifier,"beforeHeight":old["heightMeters"],"afterHeight":row["heightMeters"],
                "beforeHeightProvider":old["heightSourceReference"],"afterHeightProvider":row["heightSourceReference"],
                "pkValidation":row["pkValidation"]})
        conflict=row["pkValidation"] in {"DUPLICATE_GIS_PK","AMBIGUOUS_HUB_PK"} or "CONFLICT" in row["pkValidation"] or row["heightConflict"]
        if conflict or row["heightMeters"] is None:
            queue.append({"priority":1 if conflict else 2 if row["heightGapReason"]=="UNRESOLVED_BUILDING_IDENTITY" else 3,
                "reason":"SOURCE_CONFLICT" if conflict else row["heightGapReason"],"decision":row})
    report={"beforeKnownCount":sum(row["heightMeters"] is not None for row in before.values()),
        "afterKnownCount":sum(row["heightMeters"] is not None for row in after.values()),
        "newHeightCount":sum(before[key]["heightMeters"] is None and row["heightMeters"] is not None for key,row in after.items()),
        "lostHeightCount":sum(before[key]["heightMeters"] is not None and row["heightMeters"] is None for key,row in after.items()),
        "heightValueChanges":changes,"pkValidation":dict(Counter(row["pkValidation"] for row in after.values())),
        "remainingHeightGapReasons":dict(Counter(row["heightGapReason"] for row in after.values() if row["heightMeters"] is None)),
        "reviewQueueCount":len(queue),"reviewPriorityCounts":dict(Counter(row["priority"] for row in queue)),
        "provenanceChangedCount":sum(before[key]["heightSourceReference"]!=row["heightSourceReference"] or
            before[key].get("hubSourceId")!=row.get("hubSourceId") for key,row in after.items())}
    target.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
    (args.reviewed/"review-queue.json").write_text(json.dumps(sorted(queue,key=lambda row:(row["priority"],row["reason"],row["decision"]["sourceId"])),ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps(report,ensure_ascii=True,indent=2))


if __name__=="__main__":
    main()
