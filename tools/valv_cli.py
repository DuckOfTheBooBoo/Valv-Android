#!/usr/bin/env python3
import argparse
import getpass
import json
import logging
import mimetypes
import os
import secrets
import shutil
import struct
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Optional, Tuple

try:
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms
    from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
except ImportError as exc:
    print("Missing dependency: cryptography. Install with 'pip install cryptography'.", file=sys.stderr)
    raise SystemExit(2) from exc

try:
    from PIL import Image, ImageOps, ImageSequence
except ImportError as exc:
    print("Missing dependency: Pillow. Install with 'pip install pillow'.", file=sys.stderr)
    raise SystemExit(2) from exc

VERSION_V2 = 2
SALT_LEN = 16
IV_LEN = 12
CHECK_LEN = 12
INT_LEN = 4
KEY_LEN = 32
JSON_ORIGINAL_NAME = "originalName"

RANDOM_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

TYPE_SUFFIX = {
    "image": "-i.valv",
    "gif": "-g.valv",
    "video": "-v.valv",
    "text": "-x.valv",
    "note": "-n.valv",
    "thumb": "-t.valv",
}

DEFAULT_EXT = {
    "image": ".jpg",
    "gif": ".gif",
    "video": ".mp4",
    "text": ".txt",
    "note": ".txt",
    "thumb": ".jpg",
}

LOG = logging.getLogger("valv")


class ValvError(Exception):
    pass


class InvalidPasswordError(ValvError):
    pass


class ValvFormatError(ValvError):
    pass


@dataclass
class EncryptStats:
    total: int = 0
    encrypted: int = 0
    thumbs: int = 0
    skipped: int = 0
    failed: int = 0


@dataclass
class DecryptStats:
    total: int = 0
    decrypted: int = 0
    skipped: int = 0
    failed: int = 0


def int_to_be(value: int) -> bytes:
    return struct.pack(">I", value)


def be_to_int(data: bytes) -> int:
    if len(data) != INT_LEN:
        raise ValvFormatError("Invalid integer length")
    return struct.unpack(">I", data)[0]


def random_base_name(length: int = 32) -> str:
    return "".join(secrets.choice(RANDOM_CHARS) for _ in range(length))


def derive_key(passkey_bytes: bytes, salt: bytes, iterations: int) -> bytes:
    kdf = PBKDF2HMAC(
        algorithm=hashes.SHA512(),
        length=KEY_LEN,
        salt=salt,
        iterations=iterations,
    )
    return kdf.derive(passkey_bytes)


def build_cipher(key: bytes, iv12: bytes):
    if len(iv12) != IV_LEN:
        raise ValvFormatError("Invalid IV length")
    nonce = b"\x00\x00\x00\x00" + iv12
    return Cipher(algorithms.ChaCha20(key, nonce), mode=None)


def is_valv_v2_name(name: str) -> bool:
    return name.endswith(".valv") and not name.startswith(".valv.")


def valv_type_from_name(name: str) -> Optional[str]:
    for file_type, suffix in TYPE_SUFFIX.items():
        if name.endswith(suffix):
            return file_type
    return None


def base_name_from_valv(name: str) -> str:
    pos = name.rfind("-")
    if pos == -1:
        return name
    return name[:pos]


def classify_path(path: Path) -> Optional[str]:
    mime_type, _ = mimetypes.guess_type(str(path))
    ext = path.suffix.lower()
    if ext == ".gif" or mime_type == "image/gif":
        return "gif"
    if mime_type and mime_type.startswith("image/"):
        return "image"
    if mime_type and mime_type.startswith("text/"):
        return "text"
    if mime_type and mime_type.startswith("video/"):
        return "video"
    return "video"


def ensure_unique_path(path: Path, overwrite: bool) -> Path:
    if overwrite or not path.exists():
        return path
    stem = path.stem
    suffix = path.suffix
    parent = path.parent
    index = 1
    while True:
        candidate = parent / f"{stem} ({index}){suffix}"
        if not candidate.exists():
            return candidate
        index += 1


def sanitize_name(name: str) -> str:
    name = name.replace("\x00", "").strip()
    return os.path.basename(name)


def output_name_for_type(file_type: str, original_name: str, base_name: str) -> str:
    safe_original = sanitize_name(original_name) if original_name else ""
    if file_type == "thumb":
        base = safe_original or base_name or "thumb"
        base = os.path.splitext(base)[0]
        return f"{base}.thumb.jpg"
    if file_type == "note":
        base = safe_original or base_name or "note"
        base = os.path.splitext(base)[0]
        return f"{base}.note.txt"
    if safe_original:
        return safe_original
    return f"{base_name or 'file'}{DEFAULT_EXT.get(file_type, '')}"


def write_header(out_f, salt: bytes, iv: bytes, iterations: int, check_bytes: bytes) -> None:
    out_f.write(int_to_be(VERSION_V2))
    out_f.write(salt)
    out_f.write(iv)
    out_f.write(int_to_be(iterations))
    out_f.write(check_bytes)


def read_header(in_f) -> Tuple[int, bytes, bytes, int, bytes]:
    version_bytes = in_f.read(INT_LEN)
    if len(version_bytes) != INT_LEN:
        raise ValvFormatError("Missing version")
    version = be_to_int(version_bytes)
    salt = in_f.read(SALT_LEN)
    iv = in_f.read(IV_LEN)
    iteration_bytes = in_f.read(INT_LEN)
    check_bytes = in_f.read(CHECK_LEN)
    if len(salt) != SALT_LEN or len(iv) != IV_LEN or len(iteration_bytes) != INT_LEN or len(check_bytes) != CHECK_LEN:
        raise ValvFormatError("Incomplete header")
    iterations = be_to_int(iteration_bytes)
    return version, salt, iv, iterations, check_bytes


def encrypt_stream(
    in_f,
    out_f,
    passkey_bytes: bytes,
    iterations: int,
    original_name: str,
) -> None:
    salt = os.urandom(SALT_LEN)
    iv = os.urandom(IV_LEN)
    check_bytes = os.urandom(CHECK_LEN)
    key = derive_key(passkey_bytes, salt, iterations)
    cipher = build_cipher(key, iv)
    encryptor = cipher.encryptor()

    write_header(out_f, salt, iv, iterations, check_bytes)

    out_f.write(encryptor.update(check_bytes))
    out_f.write(encryptor.update(b"\n"))
    meta = json.dumps({JSON_ORIGINAL_NAME: original_name}, ensure_ascii=False)
    out_f.write(encryptor.update(meta.encode("utf-8")))
    out_f.write(encryptor.update(b"\n"))

    while True:
        chunk = in_f.read(1024 * 64)
        if not chunk:
            break
        out_f.write(encryptor.update(chunk))
    out_f.write(encryptor.finalize())


def encrypt_bytes(
    data: bytes,
    out_f,
    passkey_bytes: bytes,
    iterations: int,
    original_name: str,
) -> None:
    from io import BytesIO

    with BytesIO(data) as in_f:
        encrypt_stream(in_f, out_f, passkey_bytes, iterations, original_name)


class DecryptReader:
    def __init__(self, in_f, decryptor):
        self.in_f = in_f
        self.decryptor = decryptor
        self.buf = bytearray()

    def _fill(self, min_len: int) -> None:
        while len(self.buf) < min_len:
            chunk = self.in_f.read(1024 * 64)
            if not chunk:
                break
            self.buf.extend(self.decryptor.update(chunk))

    def read_exact(self, size: int) -> bytes:
        self._fill(size)
        if len(self.buf) < size:
            raise ValvFormatError("Unexpected end of file")
        data = bytes(self.buf[:size])
        del self.buf[:size]
        return data

    def read_until_newline(self, max_len: int = 300) -> bytes:
        while True:
            idx = self.buf.find(b"\n")
            if idx != -1:
                data = bytes(self.buf[:idx])
                del self.buf[: idx + 1]
                return data
            if len(self.buf) > max_len:
                raise ValvFormatError("Metadata line too long")
            chunk = self.in_f.read(1024 * 64)
            if not chunk:
                raise ValvFormatError("Unexpected end of file")
            self.buf.extend(self.decryptor.update(chunk))

    def drain(self) -> bytes:
        data = bytes(self.buf)
        self.buf.clear()
        return data


def decrypt_stream(in_f, out_f, passkey_bytes: bytes) -> Tuple[str, str]:
    version, salt, iv, iterations, check_bytes = read_header(in_f)
    if version != VERSION_V2:
        raise ValvFormatError(f"Unsupported version: {version}")

    key = derive_key(passkey_bytes, salt, iterations)
    cipher = build_cipher(key, iv)
    decryptor = cipher.decryptor()
    reader = DecryptReader(in_f, decryptor)

    check_bytes2 = reader.read_exact(CHECK_LEN)
    if check_bytes2 != check_bytes:
        raise InvalidPasswordError("Invalid password")

    if reader.read_exact(1) != b"\n":
        raise ValvFormatError("Missing metadata newline")

    json_line = reader.read_until_newline(max_len=300)
    original_name = ""
    try:
        meta = json.loads(json_line.decode("utf-8"))
        original_name = meta.get(JSON_ORIGINAL_NAME, "") if isinstance(meta, dict) else ""
    except json.JSONDecodeError:
        original_name = ""

    out_f.write(reader.drain())
    while True:
        chunk = in_f.read(1024 * 64)
        if not chunk:
            break
        out_f.write(decryptor.update(chunk))
    out_f.write(decryptor.finalize())
    return original_name, ""


def generate_thumbnail_bytes(path: Path, file_type: str, ffmpeg_path: Optional[str]) -> Optional[bytes]:
    if file_type not in ("image", "gif", "video"):
        return None

    if file_type in ("image", "gif"):
        with Image.open(path) as img:
            if file_type == "gif" and getattr(img, "is_animated", False):
                frame_index = img.n_frames // 2
                img.seek(frame_index)
            img = img.convert("RGB")
            thumb = ImageOps.fit(img, (512, 512), method=Image.LANCZOS, centering=(0.5, 0.5))
            with tempfile.NamedTemporaryFile(suffix=".jpg", delete=False) as tmp:
                tmp_path = tmp.name
            try:
                thumb.save(tmp_path, format="JPEG", quality=75)
                with open(tmp_path, "rb") as f:
                    return f.read()
            finally:
                try:
                    os.unlink(tmp_path)
                except OSError:
                    pass

    if file_type == "video":
        if not ffmpeg_path:
            return None
        with tempfile.NamedTemporaryFile(suffix=".jpg", delete=False) as tmp:
            tmp_path = tmp.name
        cmd = [
            ffmpeg_path,
            "-y",
            "-i",
            str(path),
            "-frames:v",
            "1",
            tmp_path,
        ]
        result = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if result.returncode != 0:
            try:
                os.unlink(tmp_path)
            except OSError:
                pass
            raise ValvError("ffmpeg failed to extract a frame")
        try:
            with Image.open(tmp_path) as img:
                img = img.convert("RGB")
                thumb = ImageOps.fit(img, (512, 512), method=Image.LANCZOS, centering=(0.5, 0.5))
                with tempfile.NamedTemporaryFile(suffix=".jpg", delete=False) as out_tmp:
                    out_path = out_tmp.name
            try:
                thumb.save(out_path, format="JPEG", quality=75)
                with open(out_path, "rb") as f:
                    return f.read()
            finally:
                try:
                    os.unlink(out_path)
                except OSError:
                    pass
        finally:
            try:
                os.unlink(tmp_path)
            except OSError:
                pass

    return None


def encrypt_file(
    input_path: Path,
    output_dir: Path,
    passkey_bytes: bytes,
    iterations: int,
    file_type: str,
    base_name: str,
    overwrite: bool,
) -> Path:
    suffix = TYPE_SUFFIX[file_type]
    output_path = ensure_unique_path(output_dir / f"{base_name}{suffix}", overwrite)
    original_name = input_path.name
    output_path.parent.mkdir(parents=True, exist_ok=True)

    with open(input_path, "rb") as in_f, open(output_path, "wb") as out_f:
        encrypt_stream(in_f, out_f, passkey_bytes, iterations, original_name)

    return output_path


def encrypt_thumbnail(
    thumb_bytes: bytes,
    output_dir: Path,
    passkey_bytes: bytes,
    iterations: int,
    base_name: str,
    original_name: str,
    overwrite: bool,
) -> Optional[Path]:
    output_path = ensure_unique_path(output_dir / f"{base_name}{TYPE_SUFFIX['thumb']}", overwrite)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with open(output_path, "wb") as out_f:
        encrypt_bytes(thumb_bytes, out_f, passkey_bytes, iterations, original_name)
    return output_path


def encrypt_folder(
    input_root: Path,
    output_root: Path,
    passkey_bytes: bytes,
    iterations: int,
    generate_thumbs: bool,
    ffmpeg_path: Optional[str],
    overwrite: bool,
) -> EncryptStats:
    stats = EncryptStats()

    input_root = input_root.resolve()
    output_root = output_root.resolve()

    for dirpath, dirnames, filenames in os.walk(input_root):
        dir_path = Path(dirpath)
        if output_root.is_relative_to(dir_path):
            rel = output_root.relative_to(dir_path)
            if rel.parts:
                skip_dir = rel.parts[0]
                dirnames[:] = [d for d in dirnames if d != skip_dir]

        for filename in filenames:
            stats.total += 1
            file_path = dir_path / filename
            if is_valv_v2_name(filename):
                stats.skipped += 1
                continue

            file_type = classify_path(file_path)
            if not file_type:
                stats.skipped += 1
                continue

            rel_dir = file_path.parent.relative_to(input_root)
            out_dir = output_root / rel_dir
            base_name = random_base_name()
            try:
                encrypt_file(file_path, out_dir, passkey_bytes, iterations, file_type, base_name, overwrite)
                stats.encrypted += 1
            except Exception as exc:
                stats.failed += 1
                LOG.error("Failed to encrypt %s: %s", file_path, exc)
                continue

            if generate_thumbs and file_type in ("image", "gif", "video"):
                try:
                    thumb_bytes = generate_thumbnail_bytes(file_path, file_type, ffmpeg_path)
                    if thumb_bytes:
                        encrypt_thumbnail(thumb_bytes, out_dir, passkey_bytes, iterations, base_name, file_path.name, overwrite)
                        stats.thumbs += 1
                except Exception as exc:
                    LOG.warning("Thumbnail failed for %s: %s", file_path, exc)

    return stats


def decrypt_file(
    input_path: Path,
    output_dir: Path,
    passkey_bytes: bytes,
    file_type: str,
    include_thumbs: bool,
    overwrite: bool,
) -> Optional[Path]:
    if file_type == "thumb" and not include_thumbs:
        return None

    base_name = base_name_from_valv(input_path.name)
    output_dir.mkdir(parents=True, exist_ok=True)
    tmp_output_name = output_name_for_type(file_type, "", base_name)
    output_path = ensure_unique_path(output_dir / tmp_output_name, overwrite)

    with open(input_path, "rb") as in_f:
        with tempfile.NamedTemporaryFile(delete=False) as tmp_out:
            tmp_path = Path(tmp_out.name)
        try:
            with open(tmp_path, "wb") as out_f:
                original_name, _ = decrypt_stream(in_f, out_f, passkey_bytes)
            final_name = output_name_for_type(file_type, original_name, base_name)
            final_path = ensure_unique_path(output_dir / final_name, overwrite)
            tmp_path.replace(final_path)
            return final_path
        except Exception:
            try:
                tmp_path.unlink()
            except OSError:
                pass
            raise


def decrypt_folder(
    input_root: Path,
    output_root: Path,
    passkey_bytes: bytes,
    include_thumbs: bool,
    overwrite: bool,
) -> DecryptStats:
    stats = DecryptStats()

    for dirpath, _, filenames in os.walk(input_root):
        dir_path = Path(dirpath)
        for filename in filenames:
            if not is_valv_v2_name(filename):
                continue
            stats.total += 1
            file_type = valv_type_from_name(filename)
            if not file_type:
                stats.skipped += 1
                continue

            rel_dir = dir_path.relative_to(input_root)
            out_dir = output_root / rel_dir
            file_path = dir_path / filename
            try:
                result = decrypt_file(file_path, out_dir, passkey_bytes, file_type, include_thumbs, overwrite)
                if result is None:
                    stats.skipped += 1
                else:
                    stats.decrypted += 1
            except InvalidPasswordError:
                stats.failed += 1
                LOG.error("Invalid password for %s", file_path)
            except Exception as exc:
                stats.failed += 1
                LOG.error("Failed to decrypt %s: %s", file_path, exc)

    return stats


def resolve_ffmpeg_path(ffmpeg_path: Optional[str]) -> Optional[str]:
    if ffmpeg_path:
        return ffmpeg_path
    return shutil.which("ffmpeg")


def get_passkey(args) -> str:
    if args.passkey:
        return args.passkey
    return getpass.getpass("Passkey: ")


def configure_logging(verbose: bool) -> None:
    logging.basicConfig(
        level=logging.DEBUG if verbose else logging.INFO,
        format="%(levelname)s: %(message)s",
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Valv v2 encrypt/decrypt tool (compatible with Valv-Android)."
    )
    parser.add_argument("--verbose", action="store_true", help="Enable debug logging")

    subparsers = parser.add_subparsers(dest="command", required=True)

    enc = subparsers.add_parser("encrypt", help="Encrypt a folder into Valv v2 files")
    enc.add_argument("input", type=Path, help="Input folder")
    enc.add_argument("output", type=Path, help="Output folder")
    enc.add_argument("--passkey", help="Vault passkey (discouraged; use prompt)")
    enc.add_argument("--iterations", type=int, default=50000, help="PBKDF2 iterations (default: 50000)")
    enc.add_argument("--no-thumbs", action="store_true", help="Skip thumbnail generation")
    enc.add_argument("--ffmpeg-path", help="Path to ffmpeg binary")
    enc.add_argument("--overwrite", action="store_true", help="Overwrite existing output files")

    dec = subparsers.add_parser("decrypt", help="Decrypt Valv v2 files into original files")
    dec.add_argument("input", type=Path, help="Input folder containing .valv files")
    dec.add_argument("output", type=Path, help="Output folder")
    dec.add_argument("--passkey", help="Vault passkey (discouraged; use prompt)")
    dec.add_argument("--include-thumbs", action="store_true", help="Decrypt thumbnails too")
    dec.add_argument("--overwrite", action="store_true", help="Overwrite existing output files")

    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    configure_logging(args.verbose)

    passkey = get_passkey(args)
    if not passkey:
        LOG.error("Passkey is required")
        return 2
    passkey_bytes = passkey.encode("utf-8")

    if args.command == "encrypt":
        ffmpeg_path = resolve_ffmpeg_path(args.ffmpeg_path)
        if not args.no_thumbs and not ffmpeg_path:
            LOG.warning("ffmpeg not found; video thumbnails will be skipped")
        stats = encrypt_folder(
            args.input,
            args.output,
            passkey_bytes,
            args.iterations,
            generate_thumbs=not args.no_thumbs,
            ffmpeg_path=ffmpeg_path,
            overwrite=args.overwrite,
        )
        LOG.info(
            "Encrypted: %d, Thumbs: %d, Skipped: %d, Failed: %d",
            stats.encrypted,
            stats.thumbs,
            stats.skipped,
            stats.failed,
        )
        return 1 if stats.failed > 0 else 0

    if args.command == "decrypt":
        stats = decrypt_folder(
            args.input,
            args.output,
            passkey_bytes,
            include_thumbs=args.include_thumbs,
            overwrite=args.overwrite,
        )
        LOG.info(
            "Decrypted: %d, Skipped: %d, Failed: %d",
            stats.decrypted,
            stats.skipped,
            stats.failed,
        )
        return 1 if stats.failed > 0 else 0

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
