#!/bin/bash

# Configuration
TRACKER_URL="https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_best_ip.txt"
SETTINGS_FILE="/var/lib/transmission/.config/transmission-daemon/settings.json"
TEMP_TRACKER_LIST="/tmp/all_trackers.txt"
TEMP_WORKING_LIST="/tmp/working_trackers.txt"
TEMP_SETTINGS="/tmp/settings_new.json"

# Ensure dependencies exist
for cmd in curl jq python3; do
    if ! command -v $cmd &> /dev/null; then
        echo "Error: $cmd is not installed."
        exit 1
    fi
done

if [ ! -f "$SETTINGS_FILE" ]; then
    echo "Error: Settings file not found at $SETTINGS_FILE"
    exit 1
fi

# Python helper: sends a real UDP tracker connect request (BEP 15)
# Exits 0 if a valid connect response is received, 1 otherwise
udp_tracker_check() {
    local ip="$1"
    local port="$2"
    python3 - "$ip" "$port" <<'EOF'
import socket, struct, random, sys

ip   = sys.argv[1]
port = int(sys.argv[2])

# BEP 15 connect request: magic + action 0 + random transaction_id
transaction_id = random.randint(0, 0xFFFFFFFF)
packet = struct.pack(">QII", 0x41727101980, 0, transaction_id)

try:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(2)
    sock.sendto(packet, (ip, port))
    resp, _ = sock.recvfrom(16)
    sock.close()
    # Valid response: action=0, matching transaction_id, 8-byte connection_id
    if len(resp) >= 16:
        action, resp_tid = struct.unpack(">II", resp[:8])
        if action == 0 and resp_tid == transaction_id:
            sys.exit(0)
except Exception:
    pass
sys.exit(1)
EOF
}

echo "Downloading tracker list..."
curl -s "$TRACKER_URL" | sed '/^$/d' | sed 's/[[:space:]]//g' > "$TEMP_TRACKER_LIST"

echo "Testing trackers (this may take a while)..."
> "$TEMP_WORKING_LIST"

while IFS= read -r tracker || [ -n "$tracker" ]; do
    if [[ "$tracker" == udp://* ]]; then
        IP_PORT=$(echo "$tracker" | sed -e 's|udp://||' -e 's|/.*||')
        IP=$(echo "$IP_PORT" | cut -d':' -f1)
        PORT=$(echo "$IP_PORT" | cut -d':' -f2)

        if udp_tracker_check "$IP" "$PORT"; then
            echo "$tracker" >> "$TEMP_WORKING_LIST"
        fi
    else
        if curl -s -m 2 -I "$tracker" > /dev/null; then
            echo "$tracker" >> "$TEMP_WORKING_LIST"
        fi
    fi
done < "$TEMP_TRACKER_LIST"

WORKING_STRING=$(sed ':a;N;$!ba;s/\n/\n\n/g' "$TEMP_WORKING_LIST" | sed 's/\n\n$//')

echo "Updating settings.json..."
jq --arg list "$WORKING_STRING" '.["default-trackers"] = $list' "$SETTINGS_FILE" > "$TEMP_SETTINGS"

if [ $? -eq 0 ]; then
    systemctl stop transmission-daemon
    mv "$TEMP_SETTINGS" "$SETTINGS_FILE"
    chown transmission:transmission "$SETTINGS_FILE"
    systemctl start transmission-daemon
    echo "Successfully updated trackers."
else
    echo "Error: Failed to update JSON."
    rm -f "$TEMP_SETTINGS"
    exit 1
fi

rm -f "$TEMP_TRACKER_LIST" "$TEMP_WORKING_LIST"
