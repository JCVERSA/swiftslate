"""Windows user-scoped protection for SwiftSlate configuration secrets.

The app keeps ordinary preferences in JSON, but API keys are stored as
CryptProtectData blobs. DPAPI binds those blobs to the current Windows user
profile, so another user or machine cannot decrypt them without an explicit
export/import step.
"""

import base64
import json
import os
import tempfile


class SecureConfigError(RuntimeError):
    """Raised when protected configuration cannot be read or written."""


def _crypt32():
    if os.name != "nt":
        raise SecureConfigError("Windows DPAPI is available only on Windows")
    import ctypes
    import ctypes.wintypes as wt

    class DataBlob(ctypes.Structure):
        _fields_ = [("cbData", wt.DWORD), ("pbData", ctypes.POINTER(ctypes.c_ubyte))]

    crypt32 = ctypes.WinDLL("crypt32", use_last_error=True)
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    crypt32.CryptProtectData.argtypes = [
        ctypes.POINTER(DataBlob), wt.LPCWSTR, ctypes.POINTER(DataBlob), wt.LPVOID,
        wt.LPVOID, wt.DWORD, ctypes.POINTER(DataBlob),
    ]
    crypt32.CryptProtectData.restype = wt.BOOL
    crypt32.CryptUnprotectData.argtypes = [
        ctypes.POINTER(DataBlob), ctypes.POINTER(wt.LPWSTR), ctypes.POINTER(DataBlob),
        wt.LPVOID, wt.LPVOID, wt.DWORD, ctypes.POINTER(DataBlob),
    ]
    crypt32.CryptUnprotectData.restype = wt.BOOL
    kernel32.LocalFree.argtypes = [ctypes.c_void_p]
    kernel32.LocalFree.restype = ctypes.c_void_p
    return ctypes, DataBlob, crypt32, kernel32


def protect(value):
    """Protect a UTF-8 string with Windows user-scoped DPAPI and return base64."""
    ctypes, DataBlob, crypt32, kernel32 = _crypt32()
    raw = value.encode("utf-8")
    source = (ctypes.c_ubyte * len(raw)).from_buffer_copy(raw)
    source_blob = DataBlob(len(raw), source)
    output_blob = DataBlob()
    if not crypt32.CryptProtectData(
        ctypes.byref(source_blob), "SwiftSlate API key", None, None, None, 0,
        ctypes.byref(output_blob)
    ):
        raise SecureConfigError(f"CryptProtectData failed: {ctypes.get_last_error()}")
    try:
        protected = ctypes.string_at(output_blob.pbData, output_blob.cbData)
        return base64.b64encode(protected).decode("ascii")
    finally:
        if output_blob.pbData:
            kernel32.LocalFree(ctypes.cast(output_blob.pbData, ctypes.c_void_p))


def unprotect(encoded):
    """Decrypt one base64 DPAPI value and return its UTF-8 string."""
    ctypes, DataBlob, crypt32, kernel32 = _crypt32()
    try:
        raw = base64.b64decode(encoded, validate=True)
    except (ValueError, TypeError) as exc:
        raise SecureConfigError("invalid protected API key encoding") from exc
    if not raw:
        raise SecureConfigError("empty protected API key")
    source = (ctypes.c_ubyte * len(raw)).from_buffer_copy(raw)
    source_blob = DataBlob(len(raw), source)
    output_blob = DataBlob()
    description = ctypes.c_wchar_p()
    if not crypt32.CryptUnprotectData(
        ctypes.byref(source_blob), ctypes.byref(description), None, None, None, 0,
        ctypes.byref(output_blob)
    ):
        raise SecureConfigError(f"CryptUnprotectData failed: {ctypes.get_last_error()}")
    try:
        value = ctypes.string_at(output_blob.pbData, output_blob.cbData).decode("utf-8")
        return value
    except UnicodeDecodeError as exc:
        raise SecureConfigError("protected API key is not valid UTF-8") from exc
    finally:
        if output_blob.pbData:
            kernel32.LocalFree(ctypes.cast(output_blob.pbData, ctypes.c_void_p))
        if description and description.value:
            kernel32.LocalFree(ctypes.cast(description, ctypes.c_void_p))


def read_config(path):
    """Read JSON and expose decrypted keys only in the returned in-memory object.

    A legacy plaintext ``api_keys`` list is accepted for migration compatibility,
    but callers receive a warning and should run configure.ps1 to protect it.
    """
    with open(path, "r", encoding="utf-8-sig") as handle:
        config = json.load(handle)
    if not isinstance(config, dict):
        return config

    protected = config.get("api_keys_protected")
    if protected is not None:
        if not isinstance(protected, list):
            raise SecureConfigError("api_keys_protected must be a list")
        keys = []
        key_errors = 0
        for item in protected:
            if not isinstance(item, str) or not item:
                continue
            try:
                key = unprotect(item)
            except SecureConfigError:
                # A config copied from another Windows profile must not stop
                # offline styles from starting. Ignore only the affected key.
                key_errors += 1
                continue
            if key.strip():
                keys.append(key)
        config["api_keys"] = keys
        config["_protected_key_errors"] = key_errors
    return config


def write_config(path, config):
    """Atomically write JSON, never serializing a plaintext ``api_keys`` field."""
    if not isinstance(config, dict):
        raise SecureConfigError("configuration must be an object")
    safe = dict(config)
    keys = safe.pop("api_keys", [])
    if not isinstance(keys, list):
        raise SecureConfigError("api_keys must be a list")
    safe["api_keys_protected"] = [protect(key) for key in keys if isinstance(key, str) and key.strip()]

    directory = os.path.dirname(os.path.abspath(path)) or "."
    fd, temporary = tempfile.mkstemp(prefix=".config.", suffix=".tmp", dir=directory)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
            json.dump(safe, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
        os.replace(temporary, path)
    except Exception:
        try:
            os.unlink(temporary)
        except OSError:
            pass
        raise


__all__ = ["SecureConfigError", "protect", "unprotect", "read_config", "write_config"]
