"""
check_and_notify.py
-------------------
Multi-source IPO checker for Nepal stock market.
Fetches open/upcoming IPOs from ShareSansar, NepaliPaisa, and MeroLagani,
sends Firebase push notifications for newly discovered IPOs, and persists
a deduplication cache in seen_ipos.json.

Requires: firebase-admin (pip install firebase-admin)
Firebase credentials: FIREBASE_KEY env var (JSON string) or firebase-key.json file
"""

import os
import json
import sys
import re
import datetime
import urllib.request
import urllib.parse
import ssl


# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
SEEN_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "seen_ipos.json")

SSL_CTX = ssl.create_default_context()
SSL_CTX.check_hostname = False
SSL_CTX.verify_mode = ssl.CERT_NONE

DEFAULT_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/125.0.0.0 Safari/537.36"
)


def log(msg: str):
    """Timestamped log helper for GitHub Actions visibility."""
    ts = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")
    print(f"[{ts}] {msg}", flush=True)


# ---------------------------------------------------------------------------
# HTTP Helper
# ---------------------------------------------------------------------------
def _http_get(url: str, extra_headers: dict = None, timeout: int = 15) -> str:
    headers = {
        "User-Agent": DEFAULT_USER_AGENT,
        "Accept": "application/json, text/html, */*; q=0.9",
    }
    if extra_headers:
        headers.update(extra_headers)
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, context=SSL_CTX, timeout=timeout) as r:
        return r.read().decode("utf-8", errors="ignore")


# ---------------------------------------------------------------------------
# Source 1: ShareSansar — Public JSON endpoint
# ---------------------------------------------------------------------------
def fetch_from_sharesansar() -> list:
    """
    Fetches open/upcoming IPOs from ShareSansar's DataTable API.
    Returns list of normalized IPO dicts.
    """
    # type=1 → IPO, type=2 → Right Shares, type=3 → FPO etc.
    # We fetch all types and filter by status.
    url = "https://www.sharesansar.com/existing-issues?type=1"
    ipos = []
    try:
        raw = _http_get(url, {
            "X-Requested-With": "XMLHttpRequest",
            "Referer": "https://www.sharesansar.com/existing-issues"
        })
        data = json.loads(raw)
        items = data.get("data", [])
        for item in items:
            status = item.get("status")
            # 0 = Open, -1 / -2 = Upcoming / Coming Soon
            if status in (0, -1, -2, "0", "-1", "-2"):
                comp = item.get("company", {})
                symbol = (comp.get("symbol") or "").strip()
                name = (comp.get("companyname") or "").strip()
                if symbol or name:
                    ipos.append({
                        "source": "ShareSansar",
                        "scrip": symbol or name,
                        "companyName": name or symbol,
                        "shareTypeName": item.get("typeName", "IPO"),
                        "status": "Open" if status in (0, "0") else "Upcoming",
                        "openDate": item.get("opendate", ""),
                        "closeDate": item.get("closedate", ""),
                    })
        log(f"[ShareSansar] OK — {len(ipos)} open/upcoming IPO(s) found.")
    except Exception as e:
        log(f"[ShareSansar] WARNING — Could not fetch: {e}")
    return ipos


# ---------------------------------------------------------------------------
# Source 2: NepaliPaisa — Public REST API
# ---------------------------------------------------------------------------
def fetch_from_nepalipaisa() -> list:
    """
    Fetches IPOs from NepaliPaisa public API.
    Returns list of normalized IPO dicts.
    """
    url = "https://nepalipaisa.com/api/GetIpos"
    ipos = []
    try:
        raw = _http_get(url, {"Referer": "https://nepalipaisa.com/ipo"})
        data = json.loads(raw)
        result = data.get("result", {})
        items = result.get("data", []) if isinstance(result, dict) else []
        for item in items:
            status = str(item.get("status", "")).lower()
            if status in ("open", "upcoming", "nearing", "ongoing", "current"):
                symbol = (item.get("stockSymbol") or "").strip()
                name = (item.get("companyName") or "").strip()
                share_type = (item.get("shareType") or "IPO").strip()
                if symbol or name:
                    ipos.append({
                        "source": "NepaliPaisa",
                        "scrip": symbol or name,
                        "companyName": name or symbol,
                        "shareTypeName": share_type,
                        "status": status.capitalize(),
                        "openDate": str(item.get("openDate", "")),
                        "closeDate": str(item.get("closeDate", "")),
                    })
        log(f"[NepaliPaisa] OK — {len(ipos)} open/upcoming IPO(s) found.")
    except Exception as e:
        log(f"[NepaliPaisa] WARNING — Could not fetch: {e}")
    return ipos


# ---------------------------------------------------------------------------
# Source 3: MeroLagani — Parses public HTML page for IPO table
# ---------------------------------------------------------------------------
def fetch_from_merolagani() -> list:
    """
    Parses MeroLagani's IPO listing page for open/upcoming IPOs.
    Falls back gracefully if HTML structure changes.
    """
    url = "https://www.merolagani.com/ipo.aspx"
    ipos = []
    try:
        raw = _http_get(url, {"Referer": "https://www.merolagani.com/"})
        # Extract table rows with IPO data using regex (no external parser needed)
        # MeroLagani IPO table has class "table" with symbol/company/status columns
        # Pattern matches rows like: <td>SYMBOL</td><td>Company Name</td>...<td>Open/Upcoming</td>
        rows = re.findall(
            r'<tr[^>]*>.*?<td[^>]*>([A-Z]{2,10})</td>\s*<td[^>]*>(.*?)</td>.*?<td[^>]*>(Open|Upcoming|Closed)</td>.*?</tr>',
            raw,
            re.IGNORECASE | re.DOTALL
        )
        for symbol, company_raw, status in rows:
            symbol = symbol.strip()
            company = re.sub(r'<[^>]+>', '', company_raw).strip()
            if status.lower() in ("open", "upcoming") and (symbol or company):
                ipos.append({
                    "source": "MeroLagani",
                    "scrip": symbol,
                    "companyName": company,
                    "shareTypeName": "IPO",
                    "status": status.capitalize(),
                    "openDate": "",
                    "closeDate": "",
                })
        log(f"[MeroLagani] OK — {len(ipos)} open/upcoming IPO(s) found.")
    except Exception as e:
        log(f"[MeroLagani] WARNING — Could not parse: {e}")
    return ipos


# ---------------------------------------------------------------------------
# Source 4: Optional MeroShare (only if credentials provided)
# ---------------------------------------------------------------------------
def fetch_from_meroshare_optional() -> list:
    """
    Optional login-based check. Only runs if MEROSHARE_BOID and
    MEROSHARE_PASSWORD env vars are explicitly set.
    """
    boid = os.environ.get("MEROSHARE_BOID", "").strip()
    password = os.environ.get("MEROSHARE_PASSWORD", "").strip()
    if not boid or not password:
        return []

    try:
        import requests  # only needed here
        code = boid[3:8]
        ids = {
            "19000": "1287", "20600": "1315", "13200": "128", "12300": "129", "17200": "130",
            "22300": "2155", "21800": "2136", "11900": "131", "17500": "201", "14700": "133",
            "23200": "2170", "19100": "1298", "15000": "135", "20700": "1314", "15600": "132",
            "20900": "1318", "19500": "1292", "11700": "137", "13300": "139", "13400": "140",
            "12000": "141", "14500": "142", "11300": "143", "14900": "144", "20300": "1311",
            "19800": "1305", "10800": "145", "17600": "153", "21900": "2137", "11100": "134",
            "12200": "151", "11200": "146", "16200": "147", "18000": "681", "20500": "1317",
            "22900": "2164", "19600": "1297", "10100": "138", "17700": "148", "22800": "2162",
            "17400": "149", "13100": "150", "20000": "1308", "20800": "1316", "19900": "1306",
            "23100": "2167", "23300": "2169", "17900": "402", "22000": "2140", "20100": "1309",
            "18700": "1271", "18200": "1182", "14300": "154", "15200": "156", "16300": "168",
            "12400": "195", "10700": "157", "13800": "158", "16100": "159", "14100": "155",
            "21400": "1327", "22200": "2156", "16700": "160", "18900": "1281", "13600": "161",
            "21600": "1329", "19700": "1295", "21100": "1325", "12500": "199", "15900": "163",
            "16800": "198", "15100": "166", "10400": "164", "20400": "1320", "23400": "2171",
            "15700": "167", "15500": "169", "23500": "2182", "16400": "165", "15300": "170",
            "11500": "171", "13700": "174", "10600": "173", "10200": "172", "17300": "162",
            "11000": "175", "11800": "176", "21200": "1324", "17000": "177", "21300": "1328",
            "13900": "178", "16000": "136", "12600": "179", "22600": "2161", "14800": "180",
            "15400": "152", "16900": "181", "12800": "182", "18600": "1270", "19400": "1293",
            "16600": "183", "23000": "2165", "16500": "184", "22100": "2142", "21500": "1326",
            "21700": "2134", "18100": "1080", "14400": "185", "15800": "186", "22400": "2157",
            "11600": "187", "12700": "188", "18400": "1189", "19200": "1294", "18500": "1196",
            "18800": "1274", "12900": "189", "20200": "1310", "10900": "190", "14600": "191",
            "13000": "192", "14000": "193", "21000": "1319", "14200": "194", "19300": "1296",
            "17800": "370", "22500": "2158", "18300": "1186", "22700": "2163", "11400": "196",
            "17100": "197", "13500": "200",
        }
        client_id = ids.get(code, "")
        login_res = requests.post(
            "https://webbackend.cdsc.com.np/api/meroShare/auth/",
            json={"clientId": client_id, "username": boid[-8:], "password": password},
            timeout=10,
        )
        token = (
            login_res.headers.get("Authorization")
            or login_res.json().get("token")
            or login_res.json().get("authorization")
        )
        if not token:
            return []

        payload = {
            "filterFieldParams": [
                {"key": "companyIssue.companyISIN.script", "alias": "Scrip"},
                {"key": "companyIssue.companyISIN.company.name", "alias": "Company Name"},
                {"key": "companyIssue.assignedToClient.name", "value": "", "alias": "Issue Manager"},
            ],
            "page": 1,
            "size": 10,
            "searchRoleViewConstants": "VIEW_APPLICABLE_SHARE",
            "filterDateParams": [
                {"key": "minIssueOpenDate", "condition": "", "alias": "", "value": ""},
                {"key": "maxIssueCloseDate", "condition": "", "alias": "", "value": ""},
            ],
        }
        res = requests.post(
            "https://webbackend.cdsc.com.np/api/meroShare/companyShare/applicableIssue/",
            json=payload,
            headers={"Authorization": token},
            timeout=10,
        )
        items = res.json().get("object", [])
        ipos = []
        for issue in items:
            ipos.append({
                "source": "MeroShare",
                "scrip": issue.get("scrip", "IPO"),
                "companyName": (issue.get("companyName") or "").strip(),
                "shareTypeName": issue.get("shareTypeName", "IPO"),
                "status": "Open",
                "openDate": issue.get("openDate", ""),
                "closeDate": issue.get("closeDate", ""),
            })
        log(f"[MeroShare] OK — {len(ipos)} applicable issue(s) found.")
        return ipos
    except Exception as e:
        log(f"[MeroShare] Optional check skipped: {e}")
        return []


# ---------------------------------------------------------------------------
# Firebase Notification
# ---------------------------------------------------------------------------
def init_firebase():
    try:
        import firebase_admin
        from firebase_admin import credentials
    except ImportError:
        raise RuntimeError(
            "firebase-admin is not installed. "
            "Run: pip install firebase-admin"
        )

    if firebase_admin._apps:
        return  # Already initialized

    firebase_key_env = os.environ.get("FIREBASE_KEY", "").strip()
    if firebase_key_env:
        try:
            key_data = json.loads(firebase_key_env)
            cred = credentials.Certificate(key_data)
            log("[Firebase] Initialized from FIREBASE_KEY environment variable.")
        except json.JSONDecodeError as e:
            raise RuntimeError(
                f"FIREBASE_KEY env var is not valid JSON: {e}\n"
                "Please ensure you copied the entire service account JSON content."
            )
    else:
        # Look for the key file in common locations
        search_paths = [
            "firebase-key.json",
            os.path.join(os.path.dirname(os.path.abspath(__file__)), "firebase-key.json"),
        ]
        key_path = next((p for p in search_paths if os.path.exists(p)), None)
        if not key_path:
            raise RuntimeError(
                "Firebase credentials not found!\n"
                "Set FIREBASE_KEY env var (GitHub secret) or place firebase-key.json in project root."
            )
        cred = credentials.Certificate(key_path)
        log(f"[Firebase] Initialized from file: {key_path}")

    firebase_admin.initialize_app(cred)


def send_ipo_notification(scrip: str, company_name: str, share_type: str = "IPO",
                          open_date: str = "", close_date: str = ""):
    init_firebase()
    from firebase_admin import messaging

    # Build a rich notification body
    body = f"{company_name} ({share_type}) is now open for application!"
    if open_date and close_date:
        body += f" Open: {open_date} — Close: {close_date}"
    elif close_date:
        body += f" Closes: {close_date}"

    message = messaging.Message(
        notification=messaging.Notification(
            title=f"🔔 New IPO: {scrip}",
            body=body,
        ),
        data={
            "scrip": scrip,
            "companyName": company_name,
            "shareTypeName": share_type,
            "openDate": open_date,
            "closeDate": close_date,
            "type": "NEW_IPO",
        },
        android=messaging.AndroidConfig(
            priority="high",
            notification=messaging.AndroidNotification(
                channel_id="ipo_channel",
                sound="default",
                priority="high",
            ),
        ),
        topic="new_ipos",
    )
    response = messaging.send(message)
    log(f"  --> [NOTIFICATION SENT] {scrip} — {company_name} | Message ID: {response}")


# ---------------------------------------------------------------------------
# Deduplication Cache
# ---------------------------------------------------------------------------
def load_seen_ipos() -> set:
    if os.path.exists(SEEN_FILE):
        try:
            with open(SEEN_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
                if isinstance(data, list):
                    return set(data)
                return set()
        except Exception as e:
            log(f"[Cache] WARNING — Could not read {SEEN_FILE}: {e}. Starting fresh.")
    return set()


def save_seen_ipos(seen_set: set):
    with open(SEEN_FILE, "w", encoding="utf-8") as f:
        json.dump(sorted(list(seen_set)), f, indent=2)
    log(f"[Cache] Saved {len(seen_set)} seen IPO(s) to {SEEN_FILE}")


def normalize_key(scrip: str, company_name: str) -> str:
    """Normalize scrip+company into a stable lowercase key for deduplication."""
    combined = f"{scrip}_{company_name}".lower()
    return re.sub(r"[^a-z0-9]", "", combined)


# ---------------------------------------------------------------------------
# Main Routine
# ---------------------------------------------------------------------------
def check_all_sources_and_notify():
    log("=" * 60)
    log("Bulk IPO Apply — Automated Notification Check")
    log("=" * 60)

    # Print environment summary for debugging in GitHub Actions
    log(f"[Config] seen_ipos.json path: {SEEN_FILE}")
    log(f"[Config] FIREBASE_KEY set: {'YES' if os.environ.get('FIREBASE_KEY') else 'NO — will look for firebase-key.json'}")

    # 1. Fetch from all sources
    all_ipos = []
    all_ipos.extend(fetch_from_sharesansar())
    all_ipos.extend(fetch_from_nepalipaisa())
    all_ipos.extend(fetch_from_merolagani())
    all_ipos.extend(fetch_from_meroshare_optional())

    log(f"\n[Aggregate] Total IPOs found (with duplicates): {len(all_ipos)}")

    if not all_ipos:
        log("[Result] No active or upcoming IPOs found on any source today.")
        log("=" * 60)
        return

    # 2. Deduplicate by normalized key (same IPO from multiple sources = 1 entry)
    unique_ipos: dict = {}
    for ipo in all_ipos:
        key = normalize_key(ipo["scrip"], ipo["companyName"])
        if key not in unique_ipos:
            unique_ipos[key] = ipo

    log(f"[Aggregate] Unique IPOs after deduplication: {len(unique_ipos)}")

    # 3. Check against seen cache, notify for new ones
    seen_set = load_seen_ipos()
    log(f"[Cache] Previously seen IPOs: {len(seen_set)}")
    new_found = False

    for key, ipo in unique_ipos.items():
        if key not in seen_set:
            log(f"\n[NEW IPO] {ipo['scrip']} — {ipo['companyName']} ({ipo['source']})")
            try:
                send_ipo_notification(
                    ipo["scrip"],
                    ipo["companyName"],
                    ipo.get("shareTypeName", "IPO"),
                    ipo.get("openDate", ""),
                    ipo.get("closeDate", ""),
                )
                seen_set.add(key)
                new_found = True
            except Exception as e:
                log(f"  --> [ERROR] Failed to send notification for {ipo['scrip']}: {e}")
                # Still mark as seen to avoid spamming retries
                seen_set.add(key)
                new_found = True
        else:
            log(f"[Already notified] {ipo['scrip']} ({ipo['source']})")

    # 4. Save updated cache if anything changed
    if new_found or not os.path.exists(SEEN_FILE):
        save_seen_ipos(seen_set)
    else:
        log("\n[Result] No new IPOs discovered. Cache unchanged.")

    log("=" * 60)


if __name__ == "__main__":
    check_all_sources_and_notify()
