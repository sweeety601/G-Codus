import json
import os
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

import requests
from google.auth.transport.requests import Request
from google.oauth2 import service_account

FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
FIRESTORE_SCOPE = "https://www.googleapis.com/auth/datastore"
FCM_URL = "https://fcm.googleapis.com/v1/projects/{project_id}/messages:send"
FIRESTORE_URL = "https://firestore.googleapis.com/v1/projects/{project_id}/databases/(default)/documents/devices"
SNAPSHOT_PATH = Path("data/banner_notification_snapshot.json")

GAMES = {
    "genshin": "Genshin Impact",
    "wuwa": "Wuthering Waves",
    "zzz": "Zenless Zone Zero",
    "starrail": "Honkai: Star Rail",
    "endfield": "Arknights: Endfield",
}

PROMO_SOURCES = {
    "genshin": [
        "https://hoyo-codes.seria.moe/codes?game=genshin",
        "https://api.ennead.cc/codes/genshin",
    ],
    "zzz": [
        "https://hoyo-codes.seria.moe/codes?game=nap",
        "https://api.ennead.cc/codes/zenless",
    ],
    "wuwa": [
        "https://api.ennead.cc/codes/wuwa",
        "https://game-codes.wisp.uno/codes?game=wuwa",
    ],
    "starrail": [
        "https://hoyo-codes.seria.moe/codes?game=hkrpg",
        "https://api.ennead.cc/codes/starrail",
    ],
    "endfield": [
        "https://api.ennead.cc/codes/endfield",
        "https://game-codes.wisp.uno/codes?game=endfield",
    ],
}

ALLOWED_EVENTS = {
    "promo_new",
    "phase_update",
    "phase_tomorrow",
    "character_tomorrow",
    "character_available",
    "character_confirmed",
    "character_possible",
}


def load_json(path, default):
    p = Path(path)
    if not p.exists():
        return default
    return json.loads(p.read_text(encoding="utf-8"))


def parse_dt(value):
    if not value:
        return None
    try:
        return datetime.fromisoformat(str(value).replace("Z", "+00:00")).astimezone(timezone.utc)
    except Exception:
        return None


def phase_id(phase):
    return "|".join(
        [
            str(phase.get("phase", "")),
            str(phase.get("start", "")),
            str(phase.get("end", "")),
        ]
    )


def phase_characters(phase):
    values = []
    for field in ("characters", "five_star", "four_star"):
        for value in phase.get(field, []) or []:
            clean = str(value).strip()
            if clean:
                values.append(clean)
    return set(values)


def future_phases(game):
    phases = []
    for bucket in ("next", "upcoming"):
        phases.extend(game.get(bucket, []) or [])
    return [p for p in phases if parse_dt(p.get("start")) is not None]


def game_map(feed):
    games = {}
    for key, game in (feed.get("games") or {}).items():
        game_id = str(game.get("id") or key)
        games[game_id] = game
    return games


def read_character_names():
    data = load_json("library/generated/characters.json", {})
    result = {}
    for game in data.get("games", []) or []:
        for character in game.get("characters", []) or []:
            cid = str(character.get("id", "")).strip()
            name = str(character.get("name", "")).strip()
            if cid and name:
                result[cid] = name
    return result


def normalize_code(value):
    return "".join(str(value).strip().upper().split())


def parse_promo_items(body):
    payload = json.loads(body)
    result = []

    def consume(value):
        if isinstance(value, list):
            for item in value:
                if isinstance(item, dict):
                    result.append(item)
                elif isinstance(item, str) and item.strip():
                    result.append({"code": item})
        elif isinstance(value, dict):
            if "code" in value or "key" in value:
                result.append(value)
                return
            for key in ("codes", "active", "data", "results"):
                if key in value:
                    consume(value[key])

    consume(payload)
    return result


def fetch_active_codes(game_id):
    for url in PROMO_SOURCES.get(game_id, []):
        try:
            response = requests.get(
                url,
                headers={"User-Agent": "G-Codus/1.0", "Accept": "application/json,text/plain,*/*"},
                timeout=25,
            )
            response.raise_for_status()
            items = parse_promo_items(response.text)
            codes = {
                normalize_code(item.get("code") or item.get("key"))
                for item in items
                if normalize_code(item.get("code") or item.get("key"))
            }
            if codes:
                return codes
        except Exception as exc:
            print(f"Promo source failed for {game_id}: {url} -> {exc}")
    return None


def firestore_value(value):
    if isinstance(value, bool):
        return {"booleanValue": value}
    if isinstance(value, str):
        return {"stringValue": value}
    if isinstance(value, (int, float)):
        return {"doubleValue": value}
    if isinstance(value, list):
        return {"arrayValue": {"values": [firestore_value(x) for x in value]}}
    if isinstance(value, dict):
        return {
            "mapValue": {
                "fields": {
                    str(k): firestore_value(v) for k, v in value.items()
                }
            }
        }
    return {"nullValue": None}


def decode_value(value):
    if not isinstance(value, dict):
        return None
    if "stringValue" in value:
        return value["stringValue"]
    if "booleanValue" in value:
        return value["booleanValue"]
    if "integerValue" in value:
        try:
            return int(value["integerValue"])
        except Exception:
            return value["integerValue"]
    if "doubleValue" in value:
        return value["doubleValue"]
    if "timestampValue" in value:
        return value["timestampValue"]
    if "arrayValue" in value:
        return [
            decode_value(x)
            for x in value.get("arrayValue", {}).get("values", [])
        ]
    if "mapValue" in value:
        return {
            key: decode_value(item)
            for key, item in value.get("mapValue", {}).get("fields", {}).items()
        }
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
            state = decode_value(fields.get("notification_state", {}))
            if not isinstance(state, dict):
                state = {}
            devices.append(
                {
                    "name": document.get("name", ""),
                    "token": decode_value(fields.get("token", {})) or "",
                    "games": set(decode_value(fields.get("games", {})) or []),
                    "characters": set(decode_value(fields.get("characters", {})) or []),
                    "notifications_enabled": bool(
                        decode_value(fields.get("notificationsEnabled", {}))
                    ),
                    "timezone": str(
                        decode_value(fields.get("timezone", {})) or "UTC"
                    ),
                    "state": state,
                }
            )

        page_token = payload.get("nextPageToken")
        if not page_token:
            break

    return devices


def patch_notification_state(device, project_id, access_token):
    if not device["name"]:
        return False
    response = requests.patch(
        device["name"],
        headers={
            "Authorization": f"Bearer {access_token}",
            "Content-Type": "application/json",
        },
        params={"updateMask.fieldPaths": "notification_state"},
        json={
            "fields": {
                "notification_state": firestore_value(device["state"]),
            }
        },
        timeout=30,
    )
    if not response.ok:
        print(
            f"Firestore state update failed: {response.status_code} "
            f"{response.text[:500]}"
        )
        return False
    return True


def send_to_token(access_token, project_id, token, title, body, event_key, event_type):
    if event_type not in ALLOWED_EVENTS:
        raise ValueError(f"Attempted to send unsupported event type: {event_type}")

    payload = {
        "message": {
            "token": token,
            "notification": {
                "title": title,
                "body": body,
            },
            "data": {
                "event": event_type,
                "event_key": event_key[:900],
                "source": "g-codus-firebase",
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
        FCM_URL.format(project_id=project_id),
        headers={
            "Authorization": f"Bearer {access_token}",
            "Content-Type": "application/json",
        },
        json=payload,
        timeout=30,
    )
    if not response.ok:
        print(
            f"FCM send failed ({event_type}) {response.status_code}: "
            f"{response.text[:500]}"
        )
        return False
    return True


def device_timezone(device):
    try:
        return ZoneInfo(device["timezone"])
    except Exception:
        return timezone.utc


def event_messages(game_name, event_type, character_name=None):
    if event_type == "promo_new":
        return game_name, "Доступны новые промокоды!"
    if event_type == "phase_update":
        return game_name, "Обновление фазы баннера!"
    if event_type == "phase_tomorrow":
        return game_name, "Смена фазы уже завтра!"
    if event_type == "character_tomorrow":
        return game_name, f"{character_name} будет доступен для призыва уже завтра!"
    if event_type == "character_available":
        return game_name, f"{character_name} доступен для призыва!"
    if event_type == "character_confirmed":
        return game_name, f"{character_name}: подтвердилось появление в предстоящей фазе!"
    if event_type == "character_possible":
        return game_name, f"{character_name}: возможно появление в предстоящей фазе!"
    raise ValueError(event_type)


def tracked_targets(device):
    result = set()
    for value in device["characters"]:
        raw = str(value)
        if "|" not in raw:
            continue
        game_id, character = raw.split("|", 1)
        result.add((game_id, character))
    return result


def main():
    secret = os.environ.get("FIREBASE_SERVICE_ACCOUNT", "").strip()
    if not secret:
        print("FIREBASE_SERVICE_ACCOUNT is not configured.")
        return 0

    current_feed = load_json("data/banner_feed.json", {"games": {}})
    snapshot = load_json(SNAPSHOT_PATH, {"games": {}})
    old_games = game_map(snapshot)
    current_games = game_map(current_feed)
    character_names = read_character_names()

    info = json.loads(secret)
    project_id = info["project_id"]
    credentials = service_account.Credentials.from_service_account_info(
        info, scopes=[FCM_SCOPE, FIRESTORE_SCOPE]
    )
    credentials.refresh(Request())
    access_token = credentials.token

    devices = read_devices(project_id, access_token)
    print(f"Registered Firebase devices: {len(devices)}")

    all_sends_ok = True
    total_sent = 0
    state_updates = 0

    for device in devices:
        if not device["token"] or not device["notifications_enabled"]:
            continue

        state = device["state"]
        events = set(state.get("events", []) or [])
        current_state = state.get("current_phases", {}) or {}
        promo_initialized = set(state.get("promo_initialized_games", []) or [])
        subscribed_games = {str(x) for x in device["games"]}
        tracked = tracked_targets(device)
        tz = device_timezone(device)
        local_now = datetime.now(timezone.utc).astimezone(tz)
        tomorrow = local_now.date() + timedelta(days=1)
        device_state_changed = False

        def send_event(event_key, event_type, game_name, character_name=None):
            nonlocal total_sent, all_sends_ok, device_state_changed
            if event_key in events:
                return True
            title, body = event_messages(game_name, event_type, character_name)
            ok = send_to_token(
                access_token,
                project_id,
                device["token"],
                title,
                body,
                event_key,
                event_type,
            )
            if ok:
                events.add(event_key)
                device_state_changed = True
                total_sent += 1
            else:
                all_sends_ok = False
            return ok

        for game_id, game_name in GAMES.items():
            game = current_games.get(game_id)
            if not game:
                continue

            if game_id in subscribed_games:
                current_ids = sorted(phase_id(p) for p in game.get("current", []) or [])
                current_signature = "||".join(current_ids)
                previous_signature = str(current_state.get(game_id, "") or "")
                if previous_signature:
                    if current_signature != previous_signature:
                        send_event(
                            f"phase_update|{game_id}|{current_signature}",
                            "phase_update",
                            game_name,
                        )
                current_state[game_id] = current_signature
                device_state_changed = True

                active_codes = fetch_active_codes(game_id)
                if active_codes is not None:
                    if game_id not in promo_initialized:
                        for code in active_codes:
                            events.add(f"promo|{game_id}|{code}")
                        promo_initialized.add(game_id)
                        device_state_changed = True
                    else:
                        new_codes = [
                            code
                            for code in sorted(active_codes)
                            if f"promo|{game_id}|{code}" not in events
                        ]
                        if new_codes:
                            if send_event(
                                f"promo_new|{game_id}|" + ",".join(new_codes),
                                "promo_new",
                                game_name,
                            ):
                                for code in new_codes:
                                    events.add(f"promo|{game_id}|{code}")
                                device_state_changed = True

                future = sorted(
                    future_phases(game),
                    key=lambda p: parse_dt(p.get("start")) or datetime.max.replace(tzinfo=timezone.utc),
                )
                if future:
                    next_phase = future[0]
                    start = parse_dt(next_phase.get("start"))
                    if start and start.astimezone(tz).date() == tomorrow:
                        send_event(
                            f"phase_tomorrow|{game_id}|{phase_id(next_phase)}",
                            "phase_tomorrow",
                            game_name,
                        )

            for tracked_game, tracked_character in tracked:
                if tracked_game != game_id:
                    continue
                character_id = tracked_character.strip()
                character_name = character_names.get(character_id, character_id)

                for phase in game.get("current", []) or []:
                    if phase.get("unconfirmed"):
                        continue
                    if character_id not in phase_characters(phase):
                        continue
                    send_event(
                        f"char_available|{game_id}|{character_id}|{phase_id(phase)}",
                        "character_available",
                        game_name,
                        character_name,
                    )

                for phase in future_phases(game):
                    if character_id not in phase_characters(phase):
                        continue
                    start = parse_dt(phase.get("start"))
                    if start and start.astimezone(tz).date() == tomorrow:
                        send_event(
                            f"char_tomorrow|{game_id}|{character_id}|{phase_id(phase)}",
                            "character_tomorrow",
                            game_name,
                            character_name,
                        )

                old_game = old_games.get(game_id, {})
                old_future = {
                    phase_id(p): p
                    for p in future_phases(old_game)
                }
                new_future = {
                    phase_id(p): p
                    for p in future_phases(game)
                }

                for pid, new_phase in new_future.items():
                    new_chars = phase_characters(new_phase)
                    old_phase = old_future.get(pid)
                    old_chars = phase_characters(old_phase) if old_phase else set()
                    new_unconfirmed = bool(new_phase.get("unconfirmed"))
                    old_unconfirmed = bool(old_phase.get("unconfirmed")) if old_phase else False

                    if new_unconfirmed:
                        if not old_phase:
                            # A brand-new leak phase: report newly appearing tracked characters.
                            candidate_chars = new_chars
                        elif old_unconfirmed:
                            candidate_chars = new_chars - old_chars
                        else:
                            candidate_chars = set()
                        if character_id in candidate_chars:
                            send_event(
                                f"char_possible|{game_id}|{character_id}|{pid}",
                                "character_possible",
                                game_name,
                                character_name,
                            )

                    else:
                        if not old_phase:
                            candidate_chars = new_chars
                        elif old_unconfirmed:
                            candidate_chars = new_chars
                        else:
                            candidate_chars = new_chars - old_chars
                        if character_id in candidate_chars:
                            send_event(
                                f"char_confirmed|{game_id}|{character_id}|{pid}",
                                "character_confirmed",
                                game_name,
                                character_name,
                            )

        # Keep state bounded without touching the client's subscription fields.
        state["events"] = list(dict.fromkeys(events))[-500:]
        state["current_phases"] = current_state
        state["promo_initialized_games"] = sorted(promo_initialized)

        if device_state_changed:
            if patch_notification_state(device, project_id, access_token):
                state_updates += 1
            else:
                all_sends_ok = False

    if not SNAPSHOT_PATH.exists() or json.dumps(snapshot, sort_keys=True, ensure_ascii=False) != json.dumps(current_feed, sort_keys=True, ensure_ascii=False):
        if all_sends_ok:
            SNAPSHOT_PATH.parent.mkdir(parents=True, exist_ok=True)
            SNAPSHOT_PATH.write_text(
                json.dumps(current_feed, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
            print("Notification snapshot updated.")
        else:
            print("Notification snapshot NOT advanced because at least one Firebase/Firestore operation failed.")
            return 1

    print(
        f"Firebase notifications: sent={total_sent}, "
        f"state_updates={state_updates}, all_ok={all_sends_ok}"
    )
    return 0 if all_sends_ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
