#!/usr/bin/env python3
"""
QNN's C headers (include/QNN of Qualcomm's AI Runtime SDK) for building the app's NPU code, read
out of the public SDK download by HTTP ranges: a few hundred kB of a 1.5 GB zip. They are
Qualcomm's (confidential and proprietary): fetched at build time, never committed.
Usage: headers.py <QAIRT version, e.g. 2.42.0.251225> <out dir>
"""
import os
import sys

from remotezip import RemoteZip

version, out = sys.argv[1], sys.argv[2]
path = f"/software/sdks/Qualcomm_AI_Runtime_Community/All/{version}/v{version}.zip"
urls = [
    "https://apigwx-aws.qualcomm.com:443/qsc/public/v1/api/download" + path,
    "https://softwarecenter.qualcomm.com/api/download" + path,
]
for url in urls:
    try:
        with RemoteZip(url) as z:
            names = [n for n in z.namelist() if "/include/QNN/" in n and not n.endswith("/")]
            for n in names:
                rel = n.split("/include/QNN/", 1)[1]
                dest = os.path.join(out, rel)
                os.makedirs(os.path.dirname(dest), exist_ok=True)
                with z.open(n) as src, open(dest, "wb") as dst:
                    dst.write(src.read())
            print(f"{len(names)} headers from {url}")
            break
    except Exception as e:  # the other address
        print(f"{url}: {e}")
else:
    sys.exit("QNN headers: no download worked")
if not os.path.exists(os.path.join(out, "QnnInterface.h")):
    sys.exit("QNN headers: QnnInterface.h missing")
