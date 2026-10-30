#!/usr/bin/env python3
"""Verify real local route responses for planning candidates without authorizing coverage."""
import argparse
from datetime import datetime
import json
from pathlib import Path
import time
import urllib.error
import urllib.request
import uuid


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report",type=Path,required=True)
    parser.add_argument("--output",type=Path,required=True)
    parser.add_argument("--port",type=int,default=8080)
    args=parser.parse_args()
    if args.output.exists() or not 1024<=args.port<=65535:parser.error("Expected new output and a local port.")
    report=json.loads(args.report.read_text(encoding="utf-8"))
    if report["authorizedAreaCount"]!=0:parser.error("This command verifies pending candidates only.")
    summary=[]
    for candidate in report["candidates"]:
        request_id=str(uuid.uuid4())
        request=urllib.request.Request(f"http://127.0.0.1:{args.port}/api/v1/routes/search",
            data=json.dumps(candidate["routeSearchRequest"]).encode(),
            headers={"Accept":"application/json","Content-Type":"application/json","X-Request-Id":request_id})
        started=time.monotonic()
        try:
            with urllib.request.urlopen(request,timeout=30) as response:
                result=json.load(response)
                if response.status!=200 or response.headers.get_content_type()!="application/json" or response.headers.get("X-Request-Id")!=request_id:
                    raise ValueError("Unexpected response contract")
        except (urllib.error.URLError,ValueError):
            parser.exit(1,"Local route verification failed; response details omitted\n")
        route=result["routes"][0]
        shade=route["shadeEvaluation"]
        if datetime.fromisoformat(result["evaluatedAt"])!=datetime.fromisoformat(candidate["routeSearchRequest"]["departureAt"]):
            parser.exit(1,"Evaluation time differs from the requested departure\n")
        if result["dataVersion"]!=report["graphVersion"] or shade["dataVersion"]!=report["publicVersion"] or shade["status"]!="INCOMPLETE_COVERAGE" or route["metrics"]["shadeRatio"] is not None:
            parser.exit(1,"Unexpected graph/building version or unauthorized shade ratio\n")
        candidate["actualRouteResponse"]=result
        summary.append({"id":candidate["id"],"http":200,"distanceMeters":route["distanceMeters"],
            "durationSeconds":route["durationSeconds"],"shadeStatus":shade["status"],"requestId":request_id,
            "milliseconds":round((time.monotonic()-started)*1000,1)})
    report["routeVerification"]=summary
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps(summary,indent=2))


if __name__=="__main__":
    main()
