import json
import os
import sys
from pathlib import Path

import requests
from google.oauth2 import service_account
from google.auth.transport.requests import Request

FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
FCM_URL = "https://fcm.googleapis.com/v1/projects/{project_id}/messages:send"

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

def send(access_token, project_id, topic, title, body, event_key):
    url = FCM_URL.format(project_id=project_id)
    payload = {
        "message": {
            "topic": topic,
            "notification": {"title": title, "body": body},
            "data": {
                "event": event_key[:900],
                "source": "g-codus-github"
            },
            "android": {
                "priority": "high",
                "notification": {"channel_id": "gcodus_firebase_updates"}
            }
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
    response.raise_for_status()

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
        info, scopes=[FCM_SCOPE]
    )
    credentials.refresh(Request())
    token = credentials.token

    sent = set()
    for key, game_id, game_name, bucket, phase in changed:
        phase_name = phase.get("phase", "новая фаза")
        chars = list(dict.fromkeys(
            [str(x) for x in phase.get("characters", []) if str(x).strip()] +
            [str(x) for x in phase.get("four_star", []) if str(x).strip()]
        ))

        if bucket == "current":
            body = f"{phase_name}: баннер сейчас активен."
        elif bucket == "next":
            status = "Неподтверждённый" if phase.get("unconfirmed") else "Подтверждённый"
            body = f"{phase_name}: {status.lower()} следующий баннер."
        else:
            body = f"{phase_name}: данные баннера обновлены."

        game_topic = f"gcodus_game_{game_id}"
        if game_topic not in sent:
            send(token, project_id, game_topic, game_name, body, key)
            sent.add(game_topic)

        for character_id in chars:
            topic = f"gcodus_char_{character_id}"
            if topic in sent:
                continue
            send(token, project_id, topic, game_name, body, key)
            sent.add(topic)

    print(f"Sent Firebase pushes for {len(changed)} changed banner phases.")

if __name__ == "__main__":
    main()
