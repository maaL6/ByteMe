"""JWT local RS256: mock chỉ có public key, không giữ private key của Java."""
import os
import time
from pathlib import Path

import jwt

MODE = os.getenv("MOCK_AUTH_MODE", "jwt")
if MODE not in {"jwt", "fixture"}:
    raise RuntimeError("MOCK_AUTH_MODE chỉ nhận jwt hoặc fixture")
PUBLIC_KEY = None
if MODE == "jwt":
    path = os.getenv("MOCK_JWT_PUBLIC_KEY_FILE")
    if not path:
        raise RuntimeError("JWT mode cần MOCK_JWT_PUBLIC_KEY_FILE trỏ tới public key của issuer")
    PUBLIC_KEY = Path(path).read_text()


def verify(token):
    if MODE != "jwt":
        return None
    try:
        claims = jwt.decode(token, PUBLIC_KEY, algorithms=["RS256"],
                            issuer="sa-local-dev", audience="sa-mock-api", leeway=0,
                            options={"require": ["sub", "role", "iat", "exp", "iss", "aud"]})
        sub, role = claims["sub"], claims["role"]
        issued, expiry = claims["iat"], claims["exp"]
        if (not isinstance(sub, str) or not sub.strip()
                or role not in {"CUSTOMER", "EMPLOYEE", "ADMIN"}
                or type(issued) is not int or type(expiry) is not int
                or expiry <= issued or issued > time.time()):
            return None
        return {"userId": sub, "role": role}
    except (jwt.InvalidTokenError, ValueError, TypeError):
        return None
