import json
import os
import requests
from google.oauth2 import service_account
from google.auth.transport.requests import Request

FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
FS_SCOPE = "https://www.googleapis.com/auth/datastore"

def fv(v):
    if "stringValue" in v:
        return v["stringValue"]
    if "arrayValue" in v:
        return [fv(x) for x in v.get("arrayValue", {}).get("values", [])]
    return None

def main():
    raw = os.environ["FIREBASE_SERVICE_ACCOUNT"]
    info = json.loads(raw)
    creds = service_account.Credentials.from_service_account_info(info, scopes=[FCM_SCOPE, FS_SCOPE])
    creds.refresh(Request())
    access_token = creds.token

    url = f"https://firestore.googleapis.com/v1/projects/{info['project_id']}/databases/(default)/documents/devices"
    r = requests.get(url, headers={"Authorization": f"Bearer {access_token}"}, params={"pageSize": 1000}, timeout=30)
    r.raise_for_status()

    devices = []
    for doc in r.json().get("documents", []):
        fcm = fv(doc.get("fields", {}).get("token", {}))
        if fcm:
            devices.append(fcm)

    print(f"Devices registered in Firestore: {len(devices)}")
    if not devices:
        print("No devices registered yet.")
        return

    fcm_url = f"https://fcm.googleapis.com/v1/projects/{info['project_id']}/messages:send"
    sent = 0
    for fcm_token in devices:
        payload = {
            "message": {
                "token": fcm_token,
                "notification": {
                    "title": "G-Codus — тест",
                    "body": "Firebase Push работает. Приложение может быть полностью закрыто.",
                },
                "data": {
                    "source": "g-codus-fcm-test",
                    "title": "G-Codus — тест",
                    "body": "Firebase Push работает. Приложение может быть полностью закрыто.",
                },
                "android": {
                    "priority": "high",
                    "notification": {
                        "channel_id": "gcodus_firebase_updates",
                        "sound": "default",
                    },
                },
            }
        }
        resp = requests.post(
            fcm_url,
            headers={"Authorization": f"Bearer {access_token}", "Content-Type": "application/json"},
            json=payload,
            timeout=30,
        )
        if resp.ok:
            sent += 1
        else:
            print(f"FCM failure {resp.status_code}: {resp.text[:500]}")
    print(f"Test pushes sent: {sent}/{len(devices)}")

if __name__ == "__main__":
    main()
