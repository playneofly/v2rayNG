#!/usr/bin/env python3
"""
FILTERNET - bakes every piece of data the app needs into the APK.

Run from the repository root:

    python3 tools/filternet-bundle.py                 # bundle what is on disk
    python3 tools/filternet-bundle.py --refresh       # also pull fresh IRCF + CF data
    python3 tools/filternet-bundle.py --refresh-only  # only update data/, no assets

What it produces in V2rayNG/app/src/main/assets/
------------------------------------------------
  filternet-pool.txt.gz   the public server pool, gzipped
  ircf-seed.json          clean addresses per Iranian operator, resolved over DoH
  cloudflare-ipv4.txt     Cloudflare's published IPv4 CIDRs
  internal.bin            AES-GCM bundle of the private Worker configs
  fn-manifest.json        counts, sizes and hashes of everything above

Nothing here needs the network at run time. The app reads these assets on a
phone that has never once reached GitHub.

Sources, in order of preference
-------------------------------
  pool        filternet-servers.txt (repo root)
  ircf        live DoH lookup with --refresh, else data/ircf-seed.json
  cloudflare  https://www.cloudflare.com/ips-v4 with --refresh, else
              data/cloudflare-ipv4.txt
  internal    $FN_INTERNAL_CONFIGS (the text itself) or data/internal-configs.txt
              encrypted under $FN_INTERNAL_PASSWORD

The private configs are never written to the repository: in CI they arrive as
secrets, locally they live in data/internal-configs.txt which is gitignored.
Only the encrypted result is committed, and that is safe to host publicly -
it is the password that keeps it shut.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
import re
import ssl
import sys
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "V2rayNG" / "app" / "src" / "main" / "assets"
DATA = ROOT / "data"

POOL_SRC = ROOT / "filternet-servers.txt"
IRCF_SNAPSHOT = DATA / "ircf-seed.json"
CF_SNAPSHOT = DATA / "cloudflare-ipv4.txt"
INTERNAL_SRC = DATA / "internal-configs.txt"

SCHEMA = 1

# ── IRCF ────────────────────────────────────────────────────────────────────
# ircf.space publishes one hostname per Iranian operator whose A records are
# edge addresses that currently work on that operator. These are the codes the
# app knows about; keep in sync with IrcfSource.kt.
IRCF_CODES = [
    "cname",                                        # works everywhere
    "mtn", "mci", "rtl", "mkh",                     # mobile
    "sht", "ast", "hwb", "prs", "mbt", "shm", "rsp", "sbn",  # fixed line
]

# Addresses kept per operator. The snapshot is only a floor under the live
# lookup, so a few dozen recent ones per operator is plenty - and it keeps the
# scheduled refresh from growing the file for ever.
MAX_PER_OPERATOR = 48

DOH_ENDPOINTS = [
    "https://cloudflare-dns.com/dns-query?type=A&name=",
    "https://dns.google/resolve?type=A&name=",
]

CF_IPS_URL = "https://www.cloudflare.com/ips-v4"

IPV4 = re.compile(r"^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$")
CIDR = re.compile(r"^(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})/(\d{1,2})$")


def log(msg: str) -> None:
    print(f"[filternet-bundle] {msg}", flush=True)


def is_ipv4(s: str) -> bool:
    m = IPV4.match(s.strip())
    return bool(m) and all(0 <= int(g) <= 255 for g in m.groups())


def sha256(blob: bytes) -> str:
    return hashlib.sha256(blob).hexdigest()


# ── network helpers ─────────────────────────────────────────────────────────

def _get(url: str, accept: str | None = None, timeout: int = 15) -> str | None:
    req = urllib.request.Request(url, headers={"User-Agent": "filternet-bundle/1"})
    if accept:
        req.add_header("accept", accept)
    try:
        ctx = ssl.create_default_context()
        with urllib.request.urlopen(req, timeout=timeout, context=ctx) as r:
            return r.read().decode("utf-8", "replace")
    except Exception as e:  # offline builds must not fail
        log(f"  fetch failed {url.split('?')[0]}: {type(e).__name__}")
        return None


def resolve(host: str) -> list[str]:
    """Every A record for host, over DoH so the answer cannot be forged."""
    for endpoint in DOH_ENDPOINTS:
        body = _get(endpoint + urllib.parse.quote(host), accept="application/dns-json")
        if not body:
            continue
        try:
            answers = json.loads(body).get("Answer") or []
        except json.JSONDecodeError:
            continue
        out = [a.get("data", "").strip() for a in answers if a.get("type") == 1]
        out = [a for a in out if is_ipv4(a)]
        if out:
            return sorted(set(out))
    return []


def refresh_ircf() -> dict[str, list[str]]:
    """Resolves both flavours of every operator hostname, pooled per code."""
    hosts = [(c, f"{c}c.ircf.space") for c in IRCF_CODES]
    hosts += [(c, f"{c}.ircf.space") for c in IRCF_CODES]

    found: dict[str, set[str]] = {c: set() for c in IRCF_CODES}
    with ThreadPoolExecutor(max_workers=8) as pool:
        for (code, host), addrs in zip(hosts, pool.map(lambda h: resolve(h[1]), hosts)):
            if addrs:
                found[code].update(addrs)
                log(f"  {host:24s} -> {len(addrs)}")

    return {c: sorted(v) for c, v in found.items() if v}


def refresh_cloudflare() -> list[str]:
    body = _get(CF_IPS_URL)
    if not body:
        return []
    out = [l.strip() for l in body.splitlines() if CIDR.match(l.strip())]
    return out if len(out) >= 10 else []


# ── counting ────────────────────────────────────────────────────────────────

def cidr_size(cidr: str) -> int:
    m = CIDR.match(cidr)
    if not m:
        return 0
    bits = int(m.group(2))
    return 1 << (32 - bits) if 0 <= bits <= 32 else 0


def count_configs(text: str) -> int:
    return sum(
        1 for l in text.splitlines()
        if (s := l.strip()) and not s.startswith("#") and "://" in s
    )


# ── crypto ──────────────────────────────────────────────────────────────────
# Layout is [salt 16][iv 12][ciphertext + tag], matching InternalVault.kt.

ITERATIONS = 200_000
SALT_LEN = 16
IV_LEN = 12


def encrypt(plain: str, password: str) -> bytes:
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    except ImportError:
        sys.exit("internal configs supplied but 'cryptography' is missing: pip install cryptography")
    salt = os.urandom(SALT_LEN)
    iv = os.urandom(IV_LEN)
    key = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, ITERATIONS, 32)
    return salt + iv + AESGCM(key).encrypt(iv, plain.encode("utf-8"), None)


# ── the bundle ──────────────────────────────────────────────────────────────

def write_asset(name: str, blob: bytes) -> dict:
    ASSETS.mkdir(parents=True, exist_ok=True)
    path = ASSETS / name
    path.write_bytes(blob)
    log(f"  assets/{name}: {len(blob):,} bytes")
    return {"file": name, "bytes": len(blob), "sha256": sha256(blob)}


def main() -> int:
    ap = argparse.ArgumentParser(description="Bake FILTERNET data into the APK assets.")
    ap.add_argument("--refresh", action="store_true",
                    help="pull fresh IRCF and Cloudflare data before bundling")
    ap.add_argument("--refresh-only", action="store_true",
                    help="update data/ snapshots and stop, touching no assets")
    args = ap.parse_args()

    DATA.mkdir(parents=True, exist_ok=True)
    manifest: dict = {
        "schema": SCHEMA,
        "builtAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }

    # ── 1. IRCF addresses ───────────────────────────────────────────────────
    log("IRCF addresses")
    ircf: dict[str, list[str]] = {}
    if IRCF_SNAPSHOT.exists():
        ircf = json.loads(IRCF_SNAPSHOT.read_text()).get("operators", {})
        log(f"  snapshot: {sum(len(v) for v in ircf.values())} addresses")

    if args.refresh or args.refresh_only:
        fresh = refresh_ircf()
        if fresh:
            # Union with the snapshot rather than replacing it: an address that
            # worked yesterday is still worth one handshake today, and operators
            # drop in and out of the feed between runs.
            #
            # Newest first, and capped - the file is refreshed on a schedule, so
            # an uncapped union would grow without bound and the oldest entries
            # would be the least likely to still answer.
            merged = {}
            for code in set(ircf) | set(fresh):
                ordered = list(dict.fromkeys(fresh.get(code, []) + ircf.get(code, [])))
                merged[code] = ordered[:MAX_PER_OPERATOR]
            ircf = {c: v for c, v in merged.items() if v}
            IRCF_SNAPSHOT.write_text(json.dumps(
                {"updatedAt": manifest["builtAt"], "operators": ircf},
                indent=1, sort_keys=True,
            ) + "\n")
            log(f"  refreshed -> {sum(len(v) for v in ircf.values())} addresses "
                f"across {len(ircf)} operators")
        else:
            log("  refresh produced nothing, keeping the snapshot")

    # ── 2. Cloudflare ranges ────────────────────────────────────────────────
    log("Cloudflare ranges")
    cf: list[str] = []
    if CF_SNAPSHOT.exists():
        cf = [l.strip() for l in CF_SNAPSHOT.read_text().splitlines() if CIDR.match(l.strip())]
    elif (ASSETS / "cloudflare-ipv4.txt").exists():
        cf = [l.strip() for l in (ASSETS / "cloudflare-ipv4.txt").read_text().splitlines()
              if CIDR.match(l.strip())]

    if args.refresh or args.refresh_only:
        fresh_cf = refresh_cloudflare()
        if fresh_cf:
            cf = fresh_cf
            CF_SNAPSHOT.write_text("\n".join(cf) + "\n")
            log(f"  refreshed -> {len(cf)} CIDRs")
        else:
            log("  refresh produced nothing, keeping the snapshot")

    if not cf:
        sys.exit("no Cloudflare ranges available and none could be fetched")
    cf_addresses = sum(cidr_size(c) for c in cf)
    log(f"  {len(cf)} CIDRs = {cf_addresses:,} addresses")

    if args.refresh_only:
        log("refresh-only: data/ updated, assets untouched")
        return 0

    # ── 3. the public pool ──────────────────────────────────────────────────
    log("Server pool")
    if not POOL_SRC.exists():
        sys.exit(f"missing {POOL_SRC.relative_to(ROOT)}")
    pool_text = POOL_SRC.read_text(encoding="utf-8", errors="replace")
    pool_count = count_configs(pool_text)
    # mtime=0 so an unchanged pool produces a byte-identical asset and git stays quiet
    pool_gz = gzip.compress(pool_text.encode("utf-8"), compresslevel=9, mtime=0)
    log(f"  {pool_count:,} configs, {len(pool_text):,} -> {len(pool_gz):,} bytes")
    manifest["pool"] = write_asset("filternet-pool.txt.gz", pool_gz) | {"configs": pool_count}

    # ── 4. assets for IRCF and Cloudflare ───────────────────────────────────
    ircf_blob = json.dumps({"operators": ircf}, separators=(",", ":"), sort_keys=True).encode()
    manifest["ircf"] = write_asset("ircf-seed.json", ircf_blob) | {
        "addresses": sum(len(v) for v in ircf.values()),
        "operators": len(ircf),
    }

    cf_blob = ("\n".join(cf) + "\n").encode()
    manifest["cloudflare"] = write_asset("cloudflare-ipv4.txt", cf_blob) | {
        "cidrs": len(cf),
        "addresses": cf_addresses,
    }

    # ── 5. the private vault ────────────────────────────────────────────────
    log("Internal vault")
    secret_text = os.environ.get("FN_INTERNAL_CONFIGS")
    if not secret_text and INTERNAL_SRC.exists():
        secret_text = INTERNAL_SRC.read_text(encoding="utf-8", errors="replace")
    password = os.environ.get("FN_INTERNAL_PASSWORD")

    if secret_text and password:
        n = count_configs(secret_text)
        blob = encrypt(secret_text, password)
        manifest["internal"] = write_asset("internal.bin", blob) | {
            "configs": n, "encrypted": True,
        }
        log(f"  {n} private configs sealed")
    else:
        existing = ASSETS / "internal.bin"
        if existing.exists():
            blob = existing.read_bytes()
            manifest["internal"] = {
                "file": "internal.bin", "bytes": len(blob),
                "sha256": sha256(blob), "configs": -1, "encrypted": True,
            }
            log("  no source given, keeping the bundle already in assets/")
        else:
            manifest["internal"] = {"file": None, "bytes": 0, "configs": 0, "encrypted": True}
            missing = "FN_INTERNAL_CONFIGS" if not secret_text else "FN_INTERNAL_PASSWORD"
            log(f"  skipped: {missing} not set (the internal tab will have no configs)")

    # ── 6. manifest ─────────────────────────────────────────────────────────
    blob = json.dumps(manifest, indent=1, sort_keys=True).encode()
    (ASSETS / "fn-manifest.json").write_bytes(blob)

    log("done")
    log(f"  pool       {manifest['pool']['configs']:>9,} configs")
    log(f"  internal   {manifest['internal']['configs']:>9,} configs")
    log(f"  ircf       {manifest['ircf']['addresses']:>9,} addresses "
        f"({manifest['ircf']['operators']} operators)")
    log(f"  cloudflare {manifest['cloudflare']['addresses']:>9,} addresses "
        f"({manifest['cloudflare']['cidrs']} CIDRs)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
