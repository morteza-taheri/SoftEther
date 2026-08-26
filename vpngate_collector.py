#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
VPN Gate Intelligent Server Collector
=====================================

Sources:
    1. VPN Gate main HTML
    2. VPN Gate API / CSV
    3. VPN Gate official mirrors

Features:
    - Multi-source collection
    - Automatic mirror discovery
    - HTML parsing
    - VPN Gate API CSV parsing
    - Smart duplicate detection
    - Intelligent record merging
    - IP / port validation
    - Protocol normalization
    - Server quality scoring
    - JSON / CSV export
    - SoftEther-only export
    - Source tracking

Designed for:
    VpnM / VpnM_Pro / SoftEther Android projects

Requirements:
    pip install requests beautifulsoup4
"""

import csv
import io
import json
import re
import socket
import time
from copy import deepcopy
from typing import Dict, List, Optional, Tuple
from urllib.parse import urljoin, urlparse

import requests
from bs4 import BeautifulSoup


# ============================================================
# Configuration
# ============================================================

MAIN_URL = "https://www.vpngate.net/en/"
API_URL = "https://www.vpngate.net/api/iphone/"
MIRROR_LIST_URL = "https://www.vpngate.net/en/sites.aspx"

OUTPUT_ALL_JSON = "servers_all.json"
OUTPUT_SOFTETHER_JSON = "servers_softether.json"
OUTPUT_CSV = "servers.csv"

REQUEST_TIMEOUT = 30

HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/139.0 Safari/537.36"
    ),
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "en-US,en;q=0.8",
    "Connection": "keep-alive",
}

# We don't want to hammer VPN Gate or mirrors.
REQUEST_DELAY = 0.5

session = requests.Session()
session.headers.update(HEADERS)


# ============================================================
# Utility
# ============================================================

def clean(value) -> str:
    if value is None:
        return ""

    return str(value).strip()


def safe_int(value, default=0) -> int:
    try:
        if value is None or value == "":
            return default

        value = str(value).replace(",", "").strip()

        # Handle values such as "93 days"
        match = re.search(r"-?\d+", value)

        if not match:
            return default

        return int(match.group(0))

    except Exception:
        return default


def safe_float(value, default=0.0) -> float:
    try:
        if value is None or value == "":
            return default

        value = str(value).replace(",", "").strip()

        match = re.search(r"-?\d+(?:\.\d+)?", value)

        if not match:
            return default

        return float(match.group(0))

    except Exception:
        return default


def valid_ip(ip: str) -> bool:
    try:
        socket.inet_aton(ip)
        parts = ip.split(".")

        return (
            len(parts) == 4
            and all(0 <= int(x) <= 255 for x in parts)
        )

    except Exception:
        return False


def valid_port(port) -> bool:
    try:
        p = int(port)
        return 1 <= p <= 65535
    except Exception:
        return False


def normalize_hostname(hostname: str) -> str:
    hostname = clean(hostname).lower()

    if ":" in hostname:
        hostname = hostname.split(":")[0]

    return hostname


def normalize_ip(ip: str) -> str:
    return clean(ip)


def request_text(url: str) -> Optional[str]:

    try:

        print(f"🌐 GET {url}")

        response = session.get(
            url,
            timeout=REQUEST_TIMEOUT,
        )

        response.raise_for_status()

        time.sleep(REQUEST_DELAY)

        return response.text

    except requests.RequestException as e:

        print(f"❌ Request failed: {url}")
        print(f"   {e}")

        return None


# ============================================================
# Empty Server Structure
# ============================================================

def new_server() -> Dict:

    return {
        "hostname": "",
        "ip": "",

        "country": "",
        "countryLong": "",

        "sessions": 0,
        "uptime": 0,
        "totalUsers": 0,

        "score": 0,
        "ping": 0,
        "speed": 0,

        "softEther": {
            "tcp": 0,
            "udp": False
        },

        "openVPN": {
            "tcp": 0,
            "udp": 0
        },

        "l2tp": False,

        "sstp": {
            "host": "",
            "port": 0
        },

        "sources": [],

        "sourceCount": 0,

        "valid": True,

        "qualityScore": 0
    }


# ============================================================
# VPN Gate HTML
# ============================================================

def parse_vpngate_html(html: str, source_name="html") -> List[Dict]:

    servers = []

    # --------------------------------------------------------
    # Method 1:
    # JavaScript vg_hosts_list
    # --------------------------------------------------------

    pattern = r"var\s+vg_hosts_list\s*=\s*(\[[\s\S]*?\]);"

    match = re.search(pattern, html)

    if match:

        js_data = match.group(1)

        # Convert simple JS syntax to JSON
        js_data = re.sub(
            r"\bundefined\b",
            "null",
            js_data
        )

        js_data = re.sub(
            r"\bnull\b",
            "null",
            js_data
        )

        js_data = re.sub(
            r",\s*([}\]])",
            r"\1",
            js_data
        )

        try:

            raw = json.loads(js_data)

            print(
                f"   ✅ vg_hosts_list: {len(raw)} rows"
            )

            for row in raw:

                if not isinstance(row, list):
                    continue

                if len(row) < 21:
                    continue

                s = new_server()

                s["hostname"] = clean(row[0])
                s["ip"] = clean(row[1])

                s["country"] = clean(row[2])
                s["countryLong"] = clean(row[3])

                s["uptime"] = safe_int(row[4])
                s["totalUsers"] = safe_int(row[5])

                s["softEther"]["tcp"] = (
                    safe_int(row[14])
                    if safe_int(row[14]) > 0
                    else 0
                )

                s["softEther"]["udp"] = (
                    clean(row[15]) not in (
                        "",
                        "0",
                        "None",
                        "null"
                    )
                )

                s["openVPN"]["tcp"] = safe_int(row[16])
                s["openVPN"]["udp"] = safe_int(row[17])

                s["sstp"]["host"] = clean(row[18])
                s["sstp"]["port"] = safe_int(row[19])

                s["l2tp"] = (
                    clean(row[20]) not in (
                        "",
                        "0",
                        "None",
                        "null"
                    )
                )

                s["sources"].append(source_name)

                servers.append(s)

        except Exception as e:

            print(
                f"   ⚠️ vg_hosts_list parsing failed: {e}"
            )

    # --------------------------------------------------------
    # Method 2:
    # HTML table fallback
    # --------------------------------------------------------

    if not servers:

        print("   🔄 Trying HTML table parser...")

        soup = BeautifulSoup(
            html,
            "html.parser"
        )

        tables = soup.find_all("table")

        for table in tables:

            rows = table.find_all("tr")

            for row in rows:

                cells = row.find_all(
                    ["td", "th"]
                )

                text = [
                    clean(c.get_text(" ", strip=True))
                    for c in cells
                ]

                if not text:
                    continue

                # Look for an IP
                ip = ""

                for value in text:

                    candidate = value.split()[0]

                    if valid_ip(candidate):

                        ip = candidate
                        break

                if not ip:
                    continue

                s = new_server()

                s["ip"] = ip

                # Find hostname
                for value in text:

                    if (
                        "opengw.net" in value.lower()
                        or "vpngate" in value.lower()
                    ):

                        host = value.split()[0]

                        if host:
                            s["hostname"] = host
                            break

                s["sources"].append(source_name)

                servers.append(s)

    print(
        f"   📦 Parsed HTML servers: {len(servers)}"
    )

    return servers


# ============================================================
# VPN Gate API CSV
# ============================================================

def parse_vpngate_api(csv_text: str) -> List[Dict]:

    servers = []

    lines = csv_text.splitlines()

    # Remove comments
    data_lines = [
        line
        for line in lines
        if not line.startswith("#")
    ]

    if not data_lines:
        return []

    try:

        reader = csv.DictReader(
            io.StringIO(
                "\n".join(data_lines)
            )
        )

        for row in reader:

            if not row:
                continue

            s = new_server()

            s["hostname"] = clean(
                row.get("HostName")
            )

            s["ip"] = clean(
                row.get("IP")
            )

            s["countryLong"] = clean(
                row.get("CountryLong")
            )

            s["country"] = clean(
                row.get("CountryShort")
            )

            s["score"] = safe_int(
                row.get("Score")
            )

            s["ping"] = safe_int(
                row.get("Ping")
            )

            s["speed"] = safe_int(
                row.get("Speed")
            )

            s["sessions"] = safe_int(
                row.get("NumVpnSessions")
            )

            s["uptime"] = safe_int(
                row.get("Uptime")
            )

            s["totalUsers"] = safe_int(
                row.get("TotalUsers")
            )

            s["sources"].append("api")

            # API normally provides OpenVPN configuration.
            if row.get("OpenVPN_ConfigData_Base64"):
                s["openVPN"]["tcp"] = 443

            servers.append(s)

    except Exception as e:

        print(
            f"❌ API CSV parsing error: {e}"
        )

    print(
        f"   📦 API servers: {len(servers)}"
    )

    return servers


# ============================================================
# Mirror discovery
# ============================================================

def discover_mirrors() -> List[str]:

    print("\n🔎 Discovering VPN Gate mirrors...")

    html = request_text(
        MIRROR_LIST_URL
    )

    if not html:
        return []

    soup = BeautifulSoup(
        html,
        "html.parser"
    )

    mirrors = set()

    for a in soup.find_all("a"):

        href = a.get("href")

        if not href:
            continue

        href = href.strip()

        if (
            "vpngate" in href.lower()
            or "/en/" in href.lower()
        ):

            if href.startswith(
                ("http://", "https://")
            ):

                parsed = urlparse(href)

                # Don't add official site itself
                if (
                    "vpngate.net" not in
                    parsed.netloc.lower()
                ):

                    base = href.rstrip("/")

                    if not base.endswith("/en"):
                        base += "/en"

                    mirrors.add(base + "/")

    mirrors_list = sorted(mirrors)

    print(
        f"   🌐 Found {len(mirrors_list)} mirrors"
    )

    for mirror in mirrors_list:
        print(
            f"      • {mirror}"
        )

    return mirrors_list


# ============================================================
# Record Merge
# ============================================================

def merge_value(
    old,
    new,
    prefer_new=False
):

    if prefer_new and new not in (
        "",
        None,
        0,
        False
    ):
        return new

    if old in (
        "",
        None,
        0,
        False
    ):
        return new

    return old


def merge_server(
    base: Dict,
    incoming: Dict
) -> Dict:

    result = deepcopy(base)

    # Strings
    for field in [
        "hostname",
        "ip",
        "country",
        "countryLong"
    ]:

        result[field] = merge_value(
            result.get(field),
            incoming.get(field)
        )

    # Numeric
    for field in [
        "sessions",
        "uptime",
        "totalUsers",
        "score",
        "ping",
        "speed"
    ]:

        old = safe_int(
            result.get(field)
        )

        new = safe_int(
            incoming.get(field)
        )

        # For metrics where larger is generally better
        if field in (
            "uptime",
            "totalUsers",
            "score",
            "speed"
        ):

            result[field] = max(
                old,
                new
            )

        else:

            result[field] = (
                old if old else new
            )

    # SoftEther
    result["softEther"]["tcp"] = merge_value(
        result["softEther"].get("tcp"),
        incoming["softEther"].get("tcp")
    )

    result["softEther"]["udp"] = (
        result["softEther"].get("udp", False)
        or
        incoming["softEther"].get("udp", False)
    )

    # OpenVPN
    result["openVPN"]["tcp"] = merge_value(
        result["openVPN"].get("tcp"),
        incoming["openVPN"].get("tcp")
    )

    result["openVPN"]["udp"] = merge_value(
        result["openVPN"].get("udp"),
        incoming["openVPN"].get("udp")
    )

    # L2TP
    result["l2tp"] = (
        result.get("l2tp", False)
        or
        incoming.get("l2tp", False)
    )

    # SSTP
    result["sstp"]["host"] = merge_value(
        result["sstp"].get("host"),
        incoming["sstp"].get("host")
    )

    result["sstp"]["port"] = merge_value(
        result["sstp"].get("port"),
        incoming["sstp"].get("port")
    )

    # Sources
    sources = set(
        result.get("sources", [])
    )

    sources.update(
        incoming.get("sources", [])
    )

    result["sources"] = sorted(
        sources
    )

    result["sourceCount"] = len(
        result["sources"]
    )

    return result


# ============================================================
# Duplicate Detection
# ============================================================

def server_key(server: Dict) -> str:

    ip = normalize_ip(
        server.get("ip", "")
    )

    hostname = normalize_hostname(
        server.get("hostname", "")
    )

    if valid_ip(ip):
        return "ip:" + ip

    if hostname:
        return "host:" + hostname

    return ""


def merge_all(
    server_lists: List[List[Dict]]
) -> List[Dict]:

    database = {}

    total_input = 0

    for servers in server_lists:

        total_input += len(servers)

        for server in servers:

            key = server_key(server)

            if not key:
                continue

            if key not in database:

                database[key] = deepcopy(
                    server
                )

            else:

                database[key] = merge_server(
                    database[key],
                    server
                )

    result = list(
        database.values()
    )

    print("\n" + "=" * 60)

    print(
        f"📥 Input records : {total_input}"
    )

    print(
        f"🧹 Unique servers: {len(result)}"
    )

    print(
        f"♻️ Removed duplicates: "
        f"{total_input - len(result)}"
    )

    print("=" * 60)

    return result


# ============================================================
# Validation
# ============================================================

def validate_server(server: Dict) -> bool:

    ip = server.get("ip", "")

    if not valid_ip(ip):
        return False

    hostname = server.get(
        "hostname",
        ""
    )

    if not hostname:
        server["hostname"] = ip

    # Validate SoftEther TCP
    tcp = safe_int(
        server["softEther"].get("tcp")
    )

    if not valid_port(tcp):
        server["softEther"]["tcp"] = 0

    # OpenVPN
    tcp = safe_int(
        server["openVPN"].get("tcp")
    )

    if not valid_port(tcp):
        server["openVPN"]["tcp"] = 0

    udp = safe_int(
        server["openVPN"].get("udp")
    )

    if not valid_port(udp):
        server["openVPN"]["udp"] = 0

    # SSTP
    port = safe_int(
        server["sstp"].get("port")
    )

    if not valid_port(port):
        server["sstp"]["port"] = 0

    return True


def validate_all(
    servers: List[Dict]
) -> List[Dict]:

    result = []

    for server in servers:

        if validate_server(server):
            result.append(server)

    print(
        f"✅ Valid servers: {len(result)}"
    )

    return result


# ============================================================
# Quality Score
# ============================================================

def calculate_quality_score(
    server: Dict
) -> int:

    score = 0

    # --------------------------------------------------------
    # VPN Gate official Score
    # --------------------------------------------------------

    official_score = safe_int(
        server.get("score")
    )

    if official_score:

        score += min(
            40,
            int(
                official_score /
                100000
            )
        )

    # --------------------------------------------------------
    # Speed
    # --------------------------------------------------------

    speed = safe_int(
        server.get("speed")
    )

    if speed >= 100_000_000:
        score += 20

    elif speed >= 50_000_000:
        score += 15

    elif speed >= 10_000_000:
        score += 10

    elif speed > 0:
        score += 5

    # --------------------------------------------------------
    # Ping
    # --------------------------------------------------------

    ping = safe_int(
        server.get("ping")
    )

    if ping > 0:

        if ping <= 30:
            score += 20

        elif ping <= 60:
            score += 15

        elif ping <= 100:
            score += 10

        elif ping <= 200:
            score += 5

    # --------------------------------------------------------
    # Uptime
    # --------------------------------------------------------

    uptime = safe_int(
        server.get("uptime")
    )

    if uptime >= 30:
        score += 10

    elif uptime >= 7:
        score += 7

    elif uptime >= 1:
        score += 4

    # --------------------------------------------------------
    # SoftEther availability
    # --------------------------------------------------------

    if server["softEther"]["tcp"]:
        score += 10

    if server["softEther"]["udp"]:
        score += 5

    # --------------------------------------------------------
    # Multiple sources
    # --------------------------------------------------------

    source_count = len(
        server.get("sources", [])
    )

    if source_count >= 3:
        score += 10

    elif source_count == 2:
        score += 7

    elif source_count == 1:
        score += 3

    return min(
        100,
        score
    )


def score_all(
    servers: List[Dict]
) -> List[Dict]:

    for server in servers:

        server["qualityScore"] = (
            calculate_quality_score(
                server
            )
        )

    servers.sort(
        key=lambda x: (
            x["qualityScore"],
            x["speed"],
            x["uptime"]
        ),
        reverse=True
    )

    return servers


# ============================================================
# SoftEther filter
# ============================================================

def is_softether_server(
    server: Dict
) -> bool:

    return (
        bool(
            server["softEther"].get("tcp")
        )
        or
        bool(
            server["softEther"].get("udp")
        )
    )


# ============================================================
# Export JSON
# ============================================================

def export_json(
    servers: List[Dict],
    filename: str
):

    output = {
        "generatedAt": time.strftime(
            "%Y-%m-%dT%H:%M:%SZ",
            time.gmtime()
        ),

        "source": "VPN Gate multi-source collector",

        "count": len(servers),

        "servers": servers
    }

    with open(
        filename,
        "w",
        encoding="utf-8"
    ) as f:

        json.dump(
            output,
            f,
            ensure_ascii=False,
            indent=2
        )

    print(
        f"💾 JSON saved: {filename}"
    )


# ============================================================
# Export CSV
# ============================================================

def export_csv(
    servers: List[Dict],
    filename: str
):

    fields = [
        "hostname",
        "ip",
        "country",
        "countryLong",

        "sessions",
        "uptime",
        "totalUsers",

        "score",
        "ping",
        "speed",

        "softEtherTcp",
        "softEtherUdp",

        "openVpnTcp",
        "openVpnUdp",

        "l2tp",

        "sstpHost",
        "sstpPort",

        "qualityScore",
        "sourceCount",
        "sources"
    ]

    with open(
        filename,
        "w",
        encoding="utf-8-sig",
        newline=""
    ) as f:

        writer = csv.DictWriter(
            f,
            fieldnames=fields
        )

        writer.writeheader()

        for s in servers:

            writer.writerow({

                "hostname":
                    s["hostname"],

                "ip":
                    s["ip"],

                "country":
                    s["country"],

                "countryLong":
                    s["countryLong"],

                "sessions":
                    s["sessions"],

                "uptime":
                    s["uptime"],

                "totalUsers":
                    s["totalUsers"],

                "score":
                    s["score"],

                "ping":
                    s["ping"],

                "speed":
                    s["speed"],

                "softEtherTcp":
                    s["softEther"]["tcp"],

                "softEtherUdp":
                    s["softEther"]["udp"],

                "openVpnTcp":
                    s["openVPN"]["tcp"],

                "openVpnUdp":
                    s["openVPN"]["udp"],

                "l2tp":
                    s["l2tp"],

                "sstpHost":
                    s["sstp"]["host"],

                "sstpPort":
                    s["sstp"]["port"],

                "qualityScore":
                    s["qualityScore"],

                "sourceCount":
                    s["sourceCount"],

                "sources":
                    "|".join(
                        s["sources"]
                    )
            })

    print(
        f"💾 CSV saved: {filename}"
    )


# ============================================================
# Main
# ============================================================

def main():

    print()
    print("=" * 70)
    print("       VPN GATE INTELLIGENT SERVER COLLECTOR")
    print("=" * 70)

    all_sources = []

    # --------------------------------------------------------
    # 1. Main HTML
    # --------------------------------------------------------

    print("\n[1/4] VPN Gate main HTML")

    html = request_text(
        MAIN_URL
    )

    if html:

        servers = parse_vpngate_html(
            html,
            "html"
        )

        all_sources.append(
            servers
        )

    # --------------------------------------------------------
    # 2. API
    # --------------------------------------------------------

    print("\n[2/4] VPN Gate API")

    api = request_text(
        API_URL
    )

    if api:

        servers = parse_vpngate_api(
            api
        )

        all_sources.append(
            servers
        )

    # --------------------------------------------------------
    # 3. Mirrors
    # --------------------------------------------------------

    print("\n[3/4] VPN Gate mirrors")

    mirrors = discover_mirrors()

    # Limit mirror count if necessary
    # You can increase this later.
    mirrors_to_test = mirrors[:10]

    for index, mirror in enumerate(
        mirrors_to_test,
        1
    ):

        print(
            f"\n   Mirror {index}/"
            f"{len(mirrors_to_test)}"
        )

        html = request_text(
            mirror
        )

        if not html:
            continue

        servers = parse_vpngate_html(
            html,
            f"mirror_{index}"
        )

        all_sources.append(
            servers
        )

    # --------------------------------------------------------
    # 4. Merge
    # --------------------------------------------------------

    print("\n[4/4] Intelligent merge")

    servers = merge_all(
        all_sources
    )

    # --------------------------------------------------------
    # Validation
    # --------------------------------------------------------

    servers = validate_all(
        servers
    )

    # --------------------------------------------------------
    # Score
    # --------------------------------------------------------

    servers = score_all(
        servers
    )

    # --------------------------------------------------------
    # Export all
    # --------------------------------------------------------

    export_json(
        servers,
        OUTPUT_ALL_JSON
    )

    export_csv(
        servers,
        OUTPUT_CSV
    )

    # --------------------------------------------------------
    # SoftEther only
    # --------------------------------------------------------

    softether_servers = [
        s
        for s in servers
        if is_softether_server(s)
    ]

    export_json(
        softether_servers,
        OUTPUT_SOFTETHER_JSON
    )

    # --------------------------------------------------------
    # Summary
    # --------------------------------------------------------

    print()
    print("=" * 70)

    print(
        f"🌍 Total unique servers : "
        f"{len(servers)}"
    )

    print(
        f"🔐 SoftEther servers    : "
        f"{len(softether_servers)}"
    )

    print("=" * 70)

    print("\n🏆 TOP 20 SERVERS\n")

    for index, server in enumerate(
        servers[:20],
        1
    ):

        print(
            f"{index:02d}. "
            f"{server['hostname']} "
            f"({server['ip']})"
        )

        print(
            f"    🌍 "
            f"{server['countryLong']} "
            f"({server['country']})"
        )

        print(
            f"    ⚡ Speed: "
            f"{server['speed']}"
        )

        print(
            f"    📶 Ping: "
            f"{server['ping']} ms"
        )

        print(
            f"    🟢 SoftEther TCP: "
            f"{server['softEther']['tcp']}"
        )

        print(
            f"    🟢 SoftEther UDP: "
            f"{server['softEther']['udp']}"
        )

        print(
            f"    ⭐ Quality: "
            f"{server['qualityScore']}/100"
        )

        print(
            f"    🔗 Sources: "
            f"{', '.join(server['sources'])}"
        )

        print()

    print("✅ Collection completed.")


if __name__ == "__main__":
    main()