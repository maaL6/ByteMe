"""Tạo khóa RSA local mới nếu chưa có; không ghi đè hoặc in nội dung khóa."""
import os
from pathlib import Path
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

folder = Path(__file__).resolve().parents[1] / ".local"
folder.mkdir(mode=0o700, exist_ok=True)
private_path, public_path = folder / "private.pem", folder / "public.pem"
if private_path.exists() != public_path.exists():
    raise SystemExit("Thiếu một file của cặp khóa; kiểm tra .local trước khi tạo lại.")
if private_path.exists():
    print("Đã có cặp khóa local, giữ nguyên.")
else:
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    with private_path.open("xb") as f:
        os.chmod(private_path, 0o600)
        f.write(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                  serialization.NoEncryption()))
    public_path.write_bytes(key.public_key().public_bytes(serialization.Encoding.PEM,
                                                          serialization.PublicFormat.SubjectPublicKeyInfo))
    print("Đã tạo cặp khóa RSA local; private key nằm trong .local bị gitignore.")
