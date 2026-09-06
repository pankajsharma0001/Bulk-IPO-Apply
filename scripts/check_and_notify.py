import os
import json
import sys
import re
import urllib.request
import ssl



SEEN_FILE = os.path.join(os.path.dirname(__file__), "seen_ipos.json")

# SSL context for reliable HTTPS requests
SSL_CTX = ssl.create_default_context()
SSL_CTX.check_hostname = False
SSL_CTX.verify_mode = ssl.CERT_NONE

DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

# ---------------------------------------------------------------------------
# Source 1: ShareSansar Public Endpoint (No Password Required)
# ---------------------------------------------------------------------------
def fetch_from_sharesansar():
    """
    Fetches open and upcoming IPOs from ShareSansar public JSON endpoint.
    """
    url = "https://www.sharesansar.com/existing-issues?type=1"
    headers = {
        "User-Agent": DEFAULT_USER_AGENT,
        "X-Requested-With": "XMLHttpRequest",
        "Accept": "application/json, text/javascript, */*; q=0.01",
        "Referer": "https://www.sharesansar.com/existing-issues"
    }
    ipos = []
    try:
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, context=SSL_CTX, timeout=12) as response:
            raw = response.read().decode("utf-8", errors="ignore")
            data = json.loads(raw)
            items = data.get("data", [])
            for item in items:
                status = item.get("status")
                # status 0 = Open, status -1 or -2 = Coming soon / Upcoming
                if status in (0, -1, -2, "0", "-1", "-2"):
                    comp = item.get("company", {})
                    symbol = comp.get("symbol", "").strip()
                    name = comp.get("companyname", "").strip()
                    if symbol or name:
                        ipos.append({
                            "source": "ShareSansar",
                            "scrip": symbol or name,
                            "companyName": name or symbol,
                            "shareTypeName": "IPO",
                            "status": "Open" if status in (0, "0") else "Upcoming"
                        })
        print(f"[ShareSansar] Checked successfully. Found {len(ipos)} open/upcoming IPO(s).")
    except Exception as e:
        print(f"[ShareSansar] Warning: Could not fetch ({e})")
    return ipos

# ---------------------------------------------------------------------------
# Source 2: NepaliPaisa Public API (No Password Required)
# ---------------------------------------------------------------------------
def fetch_from_nepalipaisa():
    """
    Fetches open and upcoming IPOs from NepaliPaisa public API.
    """
    url = "https://nepalipaisa.com/api/GetIpos"
    headers = {
        "User-Agent": DEFAULT_USER_AGENT,
        "Accept": "application/json, text/javascript, */*; q=0.01",
        "Referer": "https://nepalipaisa.com/ipo"
    }
    ipos = []
    try:
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, context=SSL_CTX, timeout=12) as response:
            raw = response.read().decode("utf-8", errors="ignore")
            data = json.loads(raw)
            result = data.get("result", {})
            items = result.get("data", []) if isinstance(result, dict) else []
            for item in items:
                status = str(item.get("status", "")).lower()
                if status in ("open", "upcoming", "nearing", "ongoing"):
                    symbol = (item.get("stockSymbol") or "").strip()
                    name = (item.get("companyName") or "").strip()
                    share_type = (item.get("shareType") or "IPO").strip()
                    if symbol or name:
                        ipos.append({
                            "source": "NepaliPaisa",
                            "scrip": symbol or name,
                            "companyName": name or symbol,
                            "shareTypeName": share_type,
                            "status": status.capitalize()
                        })
        print(f"[NepaliPaisa] Checked successfully. Found {len(ipos)} open/upcoming IPO(s).")
    except Exception as e:
        print(f"[NepaliPaisa] Warning: Could not fetch ({e})")
    return ipos

# ---------------------------------------------------------------------------
# Source 3: Optional MeroShare Direct (Only if credentials provided)
# ---------------------------------------------------------------------------
def fetch_from_meroshare_optional():
    """
    Optional: only runs if user explicitly provided MEROSHARE_BOID and MEROSHARE_PASSWORD.
    Otherwise skips safely without error.
    """
    boid = os.environ.get("MEROSHARE_BOID", "").strip()
    password = os.environ.get("MEROSHARE_PASSWORD", "").strip()
    if not boid or not password:
        return []

    try:
        import requests
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
            "17100": "197", "13500": "200"
        }
        client_id = ids.get(code, "")
        login_res = requests.post(
            "https://webbackend.cdsc.com.np/api/meroShare/auth/",
            json={"clientId": client_id, "username": boid[-8:], "password": password},
            timeout=10
        )
        token = login_res.headers.get("Authorization") or login_res.json().get("token") or login_res.json().get("authorization")
        if not token:
            return []

        payload = {
            "filterFieldParams": [
                {"key": "companyIssue.companyISIN.script", "alias": "Scrip"},
                {"key": "companyIssue.companyISIN.company.name", "alias": "Company Name"},
                {"key": "companyIssue.assignedToClient.name", "value": "", "alias": "Issue Manager"}
            ],
            "page": 1, "size": 10, "searchRoleViewConstants": "VIEW_APPLICABLE_SHARE",
            "filterDateParams": [{"key": "minIssueOpenDate", "condition": "", "alias": "", "value": ""}, {"key": "maxIssueCloseDate", "condition": "", "alias": "", "value": ""}]
        }
        res = requests.post(
            "https://webbackend.cdsc.com.np/api/meroShare/companyShare/applicableIssue/",
            json=payload, headers={"Authorization": token}, timeout=10
        )
        items = res.json().get("object", [])
        ipos = []
        for issue in items:
            ipos.append({
                "source": "MeroShare",
                "scrip": issue.get("scrip", "IPO"),
                "companyName": issue.get("companyName", "").strip(),
                "shareTypeName": issue.get("shareTypeName", "IPO"),
                "status": "Open"
            })
        print(f"[MeroShare] Checked successfully. Found {len(ipos)} issue(s).")
        return ipos
    except Exception as e:
        print(f"[MeroShare] Optional check skipped ({e})")
        return []

# ---------------------------------------------------------------------------
# Firebase Notification Sender
# ---------------------------------------------------------------------------
def init_firebase():
    try:
        import firebase_admin
        from firebase_admin import credentials
    except ImportError:
        raise RuntimeError("Please install firebase-admin: pip install firebase-admin")

    if firebase_admin._apps:
        return
    firebase_key = os.environ.get("FIREBASE_KEY")
    if firebase_key:
        cred = credentials.Certificate(json.loads(firebase_key))
    elif os.path.exists("firebase-key.json"):
        cred = credentials.Certificate("firebase-key.json")
    elif os.path.exists(os.path.join(os.path.dirname(__file__), "firebase-key.json")):
        cred = credentials.Certificate(os.path.join(os.path.dirname(__file__), "firebase-key.json"))
    else:
        raise RuntimeError("FIREBASE_KEY environment variable or firebase-key.json file not found.")
    firebase_admin.initialize_app(cred)

def send_ipo_notification(scrip: str, company_name: str, share_type: str = "IPO"):
    init_firebase()
    from firebase_admin import messaging
    message = messaging.Message(
        notification=messaging.Notification(
            title=f"New IPO Available: {scrip}",
            body=f"{company_name} ({share_type}) is open for application!",
        ),
        data={
            "scrip": scrip,
            "companyName": company_name,
            "shareTypeName": share_type,
        },
        topic="new_ipos",
    )
    response = messaging.send(message)
    print(f"--> [Push Notification Sent] {scrip} - {company_name} ({response})")

# ---------------------------------------------------------------------------
# Cache Management (Avoid Duplicate Notifications)
# ---------------------------------------------------------------------------
def load_seen_ipos():
    if os.path.exists(SEEN_FILE):
        try:
            with open(SEEN_FILE, "r") as f:
                return set(json.load(f))
        except Exception:
            return set()
    return set()

def save_seen_ipos(seen_set):
    with open(SEEN_FILE, "w") as f:
        json.dump(sorted(list(seen_set)), f, indent=2)

def normalize_key(scrip: str, company_name: str) -> str:
    cleaned = re.sub(r"[^a-zA-Z0-9]", "", f"{scrip}_{company_name}").lower()
    return cleaned

# ---------------------------------------------------------------------------
# Main Routine
# ---------------------------------------------------------------------------
def check_all_sources_and_notify():
    print("========================================")
    print("Checking for new IPOs across multiple sources...")
    print("========================================")

    # 1. Fetch from multiple independent portals
    all_ipos = []
    all_ipos.extend(fetch_from_sharesansar())
    all_ipos.extend(fetch_from_nepalipaisa())
    all_ipos.extend(fetch_from_meroshare_optional())

    if not all_ipos:
        print("No active IPOs found on any source at the moment.")
        return

    # 2. Deduplicate by normalized key
    unique_ipos = {}
    for ipo in all_ipos:
        key = normalize_key(ipo["scrip"], ipo["companyName"])
        if key not in unique_ipos:
            unique_ipos[key] = ipo

    seen_set = load_seen_ipos()
    new_found = False

    for key, ipo in unique_ipos.items():
        if key not in seen_set:
            print(f"\n[NEW IPO DISCOVERED from {ipo['source']}]: {ipo['scrip']} - {ipo['companyName']}")
            send_ipo_notification(ipo["scrip"], ipo["companyName"], ipo.get("shareTypeName", "IPO"))
            seen_set.add(key)
            new_found = True
        else:
            print(f"[Already notified]: {ipo['scrip']} ({ipo['source']})")

    if new_found or not os.path.exists(SEEN_FILE):
        save_seen_ipos(seen_set)
        print("\nSaved updated IPO history.")
    else:
        print("\nNo newly announced IPOs since last check.")

if __name__ == "__main__":
    check_all_sources_and_notify()
