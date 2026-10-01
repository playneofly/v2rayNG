#!/usr/bin/env python3
"""
FILTERNET - builds the encrypted bundle for the "internal" tab.

    python3 make-internal-bundle.py configs.txt "your-password" internal.bin

Put the result at  V2rayNG/app/src/main/assets/internal.bin
Works entirely offline on the phone: the password IS the key, so nothing has
to be checked against a server.
"""
import sys, os, hashlib
try:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
except ImportError:
    sys.exit("pip install cryptography")

if len(sys.argv) != 4:
    sys.exit(__doc__)

src, password, out = sys.argv[1], sys.argv[2], sys.argv[3]
plain = open(src, "rb").read()

salt = os.urandom(16)
iv = os.urandom(12)
key = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, 200_000, 32)
blob = salt + iv + AESGCM(key).encrypt(iv, plain, None)
open(out, "wb").write(blob)
print(f"{out}: {len(blob)} bytes written")
