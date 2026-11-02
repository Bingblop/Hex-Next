#!/usr/bin/env python3
"""Fill Google Play Console's Data safety CSV for Hex Next.

Usage: make_data_safety.py SAMPLE.csv OUT_DIR

SAMPLE.csv is the blank export from Play Console (App content > Data safety >
Export). Only the "Response value" column is filled in; every row, id and
label is kept exactly as exported so the Console import accepts the file.
Two files are written:
  data-safety.csv                the v1.0 answers (Play Billing + licence server)
  data-safety-no-collection.csv  for test builds that ship before billing lands
"""
import csv
import sys
from pathlib import Path

POLICY = "https://bingblop.github.io/hexnext-site/privacy-policy.html"
DELETE_URL = POLICY + "#delete-data"

# Rows that are the same in both variants.
COMMON = {
    ("PSL_DATA_COLLECTION_ENCRYPTED_IN_TRANSIT", ""): "true",
    ("PSL_SUPPORTED_ACCOUNT_CREATION_METHODS", "PSL_ACM_NONE"): "true",
    ("PSL_HAS_OUTSIDE_APP_ACCOUNTS", ""): "false",
}

# v1.0: the only data that leaves the phone is the Play purchase token (kept by the
# licence server as the proof of entitlement) and the Play Integrity token (verified and
# discarded). AI prompts go straight from the phone to the provider the user picked with
# the user's own key, which Play's policy treats as a user-initiated transfer, not sharing.
V1 = {
    ("PSL_DATA_COLLECTION_COLLECTS_PERSONAL_DATA", ""): "true",
    ("PSL_SUPPORT_DATA_DELETION_BY_USER", "DATA_DELETION_YES"): "true",
    ("PSL_DATA_DELETION_URL", ""): DELETE_URL,
    ("PSL_DATA_TYPES_FINANCIAL", "PSL_PURCHASE_HISTORY"): "true",
    ("PSL_DATA_TYPES_IDENTIFIERS", "PSL_DEVICE_ID"): "true",
}
def usage(data_type, ephemeral, purposes):
    base = f"PSL_DATA_USAGE_RESPONSES:{data_type}:"
    rows = {
        (base + "PSL_DATA_USAGE_COLLECTION_AND_SHARING", "PSL_DATA_USAGE_ONLY_COLLECTED"): "true",
        (base + "PSL_DATA_USAGE_EPHEMERAL", ""): "true" if ephemeral else "false",
        (base + "DATA_USAGE_USER_CONTROL", "PSL_DATA_USAGE_USER_CONTROL_OPTIONAL"): "true",
    }
    for purpose in purposes:
        rows[(base + "DATA_USAGE_COLLECTION_PURPOSE", purpose)] = "true"
    return rows
V1.update(usage("PSL_PURCHASE_HISTORY", ephemeral=False,
                purposes=["PSL_APP_FUNCTIONALITY", "PSL_FRAUD_PREVENTION_SECURITY"]))
V1.update(usage("PSL_DEVICE_ID", ephemeral=True, purposes=["PSL_FRAUD_PREVENTION_SECURITY"]))

NO_COLLECTION = {
    ("PSL_DATA_COLLECTION_COLLECTS_PERSONAL_DATA", ""): "false",
}

def fill(sample_rows, answers):
    out = []
    used = set()
    for row in sample_rows:
        qid, rid = row[0], row[1]
        key = (qid, rid)
        value = answers.get(key, "")
        if key in answers:
            used.add(key)
        out.append([qid, rid, value, row[3], row[4]])
    missing = set(answers) - used
    if missing:
        raise SystemExit(f"answers not present in sample: {sorted(missing)}")
    return out

def main():
    sample, out_dir = Path(sys.argv[1]), Path(sys.argv[2])
    with sample.open(newline="", encoding="utf-8") as f:
        rows = list(csv.reader(f))
    header, body = rows[0], rows[1:]
    for name, answers in (("data-safety.csv", {**COMMON, **V1}),
                          ("data-safety-no-collection.csv", {**COMMON, **NO_COLLECTION})):
        filled = fill(body, answers)
        with (out_dir / name).open("w", newline="", encoding="utf-8") as f:
            w = csv.writer(f, lineterminator="\n")
            w.writerow(header)
            w.writerows(filled)
        print(name, len(filled), "rows,", sum(1 for r in filled if r[2]), "answered")

if __name__ == "__main__":
    main()
