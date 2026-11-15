"""Download pinned official HEV binary; validate before using it in the APK."""
from pathlib import Path
import hashlib
import urllib.request

DEST = Path(__file__).resolve().parents[1] / 'android/app/libs/hev-socks5-tunnel-2.20.0.aar'
SHA = 'ae8a2e96b80b4eccccd037805a4bb4a278090c89dbcae8bc6fcb7634e9a255b9'
if not DEST.exists():
    DEST.parent.mkdir(parents=True, exist_ok=True)
    urllib.request.urlretrieve('https://github.com/heiher/hev-socks5-tunnel/releases/download/2.20.0/hev-socks5-tunnel.aar', DEST)
if hashlib.sha256(DEST.read_bytes()).hexdigest() != SHA:
    raise SystemExit('HEV AAR hash mismatch: refusing to build')
print('HEV 2.20.0 SHA256 OK')
