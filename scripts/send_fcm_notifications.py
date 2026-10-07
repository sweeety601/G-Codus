import json
import os
import sys
from pathlib import Path

import requests
from google.oauth2 import service_account
from google.auth.transport.requests import Request

FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
FIRESTORE_SCOPE = "https://www.googleapis.com/auth/datastore"
FCM_URL = "https://fcm.googleapis.com/v1/projects/{project_id}/messages:send"
FIRESTORE_URL = "https://firestore.googleapis.com/v1/projects/{project_id}/databases/(default)/documents/devices"

def load_json(path, default):
    p = Path(path)
    if not p.exists():
        return default
    return json.loads(p.read_text(encoding="utf-8"))

def phase_map(feed):
    result = {}
    for game_name, game in feed.get("games", {}).items():
        game_id = game.get("id", game_name)
        for bucket in ("current", "next", "upcoming", "history"):
            for phase in game.get(bucket, []):
                key = "|".join([
                    game_id,
                    bucket,
                    str(phase.get("phase", "")),
                    str(phase.get("start", "")),
                    str(phase.get("end", "")),
                ])
                normalized = dict(phase)
                normalized.pop("generated_at", None)
                result[key] = (game_id, game.get("name", game_id), bucket, normalized)
    return result

def firestore_value(value):
    if isinstance(value, bool):
        return {"booleanValue": value}
    if isinstance(value, str):
        return {"stringValue": value}
    if isinstance(value, list):
        return {"arrayValue": {"values": [firestore_value(x) for x in value]}}
    return {"nullValue": None}

def decode_value(value):
    if "stringValue" in value:
        return value["stringValue"]
    if "booleanValue" in value:
        return value["booleanValue"]
    if "arrayValue" in value:
        return [decode_value(x) for x in value.get("arrayValue", {}).get("values", [])]
    if "timestampValue" in value:
        return value["timestampValue"]
    return None

def read_devices(project_id, access_token):
    headers = {"Authorization": f"Bearer {access_token}"}
    devices = []
    page_token = None

    while True:
        params = {"pageSize": "1000"}
        if page_token:
            params["pageToken"] = page_token

        response = requests.get(
            FIRESTORE_URL.format(project_id=project_id),
            headers=headers,
            params=params,
            timeout=30,
        )
        response.raise_for_status()
        payload = response.json()

        for document in payload.get("documents", []):
            fields = document.get("fields", {})
            devices.append({
                "name": document.get("name", ""),
                "token": decode_value(fields.get("token", {})) or "",
                "games": set(decode_value(fields.get("games", {})) or []),
                "characters": set(decode_value(fields.get("characters", {})) or []),
            })

        page_token = payload.get("nextPageToken")
        if not page_token:
            break

    return devices

def send_to_token(access_token, project_id, token, title, body, event_key):
    url = FCM_URL.format(project_id=project_id)
    payload = {
        "message": {
            "token": token,
            "notification": {
                "title": title,
                "body": body,
            },
            "data": {
                "event": event_key[:900],
                "source": "g-codus-github",
                "title": title,
                "body": body,
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
    response = requests.post(
        url,
        headers={
            "Authorization": f"Bearer {access_token}",
            "Content-Type": "application/json",
        },
        json=payload,
        timeout=30,
    )
    return response

def normalize_identity(value):
    return "".join(ch for ch in str(value).lower() if ch.isalnum())

def notification_text(game_name, bucket, phase):
    phase_name = phase.get("phase", "Новая фаза")
    if bucket == "current":
        return phase_name + ": баннер сейчас активен."
    if bucket == "next":
        status = "Неподтверждённый" if phase.get("unconfirmed") else "Подтверждённый"
        return phase_name + ": " + status.lower() + " следующий баннер."
    return phase_name + ": данные баннера обновлены."

def main():
    secret = os.environ.get("FIREBASE_SERVICE_ACCOUNT", "").strip()
    if not secret:
        print("FIREBASE_SERVICE_ACCOUNT is not configured; Firebase notification step skipped.")
        return

    old = load_json(sys.argv[1], {"games": {}})
    new = load_json(sys.argv[2], {"games": {}})

    old_map = phase_map(old)
    new_map = phase_map(new)

    changed = []
    for key, (game_id, game_name, bucket, phase) in new_map.items():
        old_item = old_map.get(key)
        if old_item is None or old_item[3] != phase:
            changed.append((key, game_id, game_name, bucket, phase))

    if not changed:
        print("No banner feed changes requiring push notifications.")
        return

    info = json.loads(secret)
    project_id = info["project_id"]
    credentials = service_account.Credentials.from_service_account_info(
        info, scopes=[FCM_SCOPE, FIRESTORE_SCOPE]
    )
    credentials.refresh(Request())
    access_token = credentials.token

    devices = read_devices(project_id, access_token)
    print(f"Registered Firebase devices: {len(devices)}")

    sent_tokens = set()
    failed_tokens = set()

    for key, game_id, game_name, bucket, phase in changed:
        chars = set(
            str(x) for x in (
                list(phase.get("characters", [])) +
                list(phase.get("five_star", [])) +
                list(phase.get("four_star", []))
            ) if str(x).strip()
        )
        title = game_name
        body = notification_text(game_name, bucket, phase)

        for device in devices:
            token = device["token"]
            if not token or token in sent_tokens:
                continue

            character_targets = {
                game_id + "|" + normalize_identity(str(char))
                for char in chars
            }
            device_targets = set()
            for value in device["characters"]:
                raw = str(value)
                if "|" in raw:
                    target_game, target_character = raw.split("|", 1)
                    device_targets.add(
                        target_game + "|" + normalize_identity(target_character)
                    )
                else:
                    device_targets.add(normalize_identity(raw))
            subscribed = game_id in device["games"] or bool(character_targets & device_targets)
            if not subscribed:
                continue

            response = send_to_token(access_token, project_id, token, title, body, key)
            if response.ok:
                sent_tokens.add(token)
            else:
                failed_tokens.add(token)
                print(
                    f"FCM send failed for {game_id} -> {response.status_code}: "
                    f"{response.text[:500]}"
                )

    print(
        f"Firebase direct pushes: sent={len(sent_tokens)}, "
        f"failed={len(failed_tokens)}, changed_phases={len(changed)}"
    )

if __name__ == "__main__":
    main()
