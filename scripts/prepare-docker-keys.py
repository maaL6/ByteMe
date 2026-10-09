"""Copy the local RSA key pair to a private Docker-only directory."""
import os
from pathlib import Path
from tempfile import NamedTemporaryFile

root = Path(__file__).resolve().parents[1] / ".local"
destination = root / "docker-keys"
sources = {
    "private.pem": root / "private.pem",
    "public.pem": root / "public.pem",
}

missing = [name for name, path in sources.items() if not path.is_file()]
if missing:
    raise SystemExit("Thiếu .local/" + ", .local/".join(missing) + "; hãy chạy generate-keys.py trước.")

destination.mkdir(mode=0o700, parents=True, exist_ok=True)
for name, source in sources.items():
    mode = 0o600 if name == "private.pem" else 0o644
    with NamedTemporaryFile(dir=destination, prefix=f".{name}.", delete=False) as output:
        temporary = Path(output.name)
        os.chmod(temporary, mode)
        output.write(source.read_bytes())
        output.flush()
        os.fsync(output.fileno())
    os.replace(temporary, destination / name)

print("Đã cập nhật bản sao khóa Docker trong .local/docker-keys; khóa gốc không thay đổi.")
