# SwiftSlate Desktop for Windows

SwiftSlate Desktop is the Windows 10/11 x64 companion to the Android app. It watches keyboard input through the Windows Raw Input API and replaces a trigger typed at the end of a text field, without pretending to change the host application's actual font.

The desktop port is based on the existing [SwiftSlate Desktop project](https://github.com/Musheer360/SwiftSlate-Desktop), adapted into this repository so its local styles, privacy rules, and configuration can evolve with Android.

## Current scope

- **Windows 10 and Windows 11, x64**
- **Offline-first local commands:** styles, clipboard helpers, and shell-free canned replacers work without an account or network
- **AI commands:** Gemini, Groq, and OpenAI-compatible endpoints remain available when configured
- **System-wide input:** Raw Input, clipboard capture, and simulated keyboard input
- **Tray menu:** exit the app or apply a local style to currently selected text
- **No analytics or crash reporting**
- **DPAPI-protected API keys:** keys are encrypted for the current Windows user before being written to `config.json`

## Install

Run this in a normal, non-administrator PowerShell window:

```powershell
irm https://raw.githubusercontent.com/JCVERSA/swiftslate/main/desktop/install.ps1 | iex
```

The installer targets Windows x64, downloads an embedded Python runtime when necessary, installs the application under `%USERPROFILE%\.swiftslate`, and can create a per-user startup shortcut. It does not require Python to be installed system-wide.

The installer is intentionally pinned with SHA-256 checksums for the application files and embedded runtime. It uses the GitHub Raw source first and jsDelivr only as a fallback.

To update, run the same command again. To uninstall, run it again and follow the prompt.

## Local styles

Type one of these commands at the end of text in a supported field:

| Command | Result |
| --- | --- |
| `?bold` | Unicode bold characters |
| `?italic` | Unicode italic characters |
| `?mono` | Unicode monospace characters |
| `?bubble` | Circled or bubble characters |
| `?gothic` | Fraktur characters |
| `?smallcaps` | Small-cap characters |
| `?normal` | Convert the supported style characters back to ordinary text |

Example:

```text
Hello SwiftSlate 2026 ?bold
```

becomes:

```text
𝗛𝗲𝗹𝗹𝗼 𝗦𝘄𝗶𝗳𝘁𝗦𝗹𝗮𝘁𝗲 𝟮𝟬𝟮𝟲
```

Styles are local transformations. They do not call an AI provider, need no API key, and do not change the real font selected by another application.

### Style menu for selected text

Windows does not expose one universal selection-action menu for every application. The desktop equivalent is available from the tray icon:

1. Select text in the target application.
2. Open the SwiftSlate icon's tray menu.
3. Choose **Style selected text**, then choose a style.

SwiftSlate returns focus to the target window, copies only the selection, applies the local transformation, and pastes it back. Clipboard exclusion formats are used during this operation so the temporary text is not added to Windows clipboard history or cloud clipboard synchronization. Applications that block simulated paste or clipboard access may not support this flow.

## Configuration

The installer creates `%USERPROFILE%\.swiftslate\config.json`. Ordinary settings remain JSON, while API keys are stored as `api_keys_protected` DPAPI values. A protected value can only be decrypted by the same Windows user profile on the same machine.

To add, replace, or remove keys without editing them in plaintext, run the bundled PowerShell helper:

```powershell
powershell -ExecutionPolicy Bypass -File "$env:USERPROFILE\.swiftslate\configure.ps1"
```

The application also accepts an older `api_keys` field for migration compatibility, but it logs a warning. Move those keys with `configure.ps1` and remove the plaintext field. Never commit `config.json` or paste its contents into an issue.

Example configuration without API access:

```json
{
  "provider": "gemini",
  "model": "gemini-3.5-flash-lite",
  "api_keys_protected": [],
  "spinner": "animated",
  "key_delay": 200
}
```

With no key, AI commands show a clear error only when invoked. Local styles and local commands continue to work.

## AI commands

The desktop baseline includes:

- `?fix`
- `?improve`
- `?shorten`
- `?expand`
- `?formal`
- `?casual`
- `?emoji`
- `?human`
- `?reply`
- `?translate:XX`

AI commands are opt-in network operations. SwiftSlate does not silently change the selected provider or send text to an unconfigured provider. The current provider and model remain in `config.json`.

## Privacy and limitations

- No telemetry, tracking, or automatic diagnostics are sent.
- Text is sent to the configured AI provider only when an AI command is invoked.
- Local styles never use the network.
- API keys are protected with Windows DPAPI and are not written to the configuration as plaintext by the installer or `configure.ps1`.
- Clipboard operations temporarily use the clipboard because this is the interoperable Windows replacement mechanism. SwiftSlate marks its temporary clipboard data to exclude it from clipboard history and cloud sync where supported.
- Unicode style coverage is best effort. Accents, emoji, Arabic, Chinese, Thai, and unsupported characters are preserved exactly rather than silently removed.
- Unicode presentation characters may render differently across host applications and fonts. SwiftSlate cannot change a host application's actual font.
- Applications with custom editors, elevated integrity levels, blocked clipboard access, or fields that reject simulated input may not support replacement.
- The desktop port does not include Android-only Quick Settings tiles or Android accessibility APIs.

## Development

The runtime is intentionally dependency-free: Python standard library plus Win32 APIs through `ctypes`. The main application is `SwiftSlate.pyw`; the platform-independent style transformer is `text_styles.py`; `secure_config.py` contains the DPAPI bridge.

Run the platform-independent tests from this directory on any platform:

```powershell
python -m unittest discover -s tests -v
```

On a Windows x64 runner, also verify manually in Notepad, a browser text field, a terminal, and a rich editor:

1. type each local style command;
2. select text and apply each tray style;
3. verify accented, Arabic, Chinese, Thai, emoji, and unknown characters survive;
4. verify the original clipboard is restored after a style operation;
5. verify an empty-key configuration still starts and runs local commands;
6. verify AI text is sent only after an AI command is typed;
7. run the installer and confirm the embedded runtime and startup shortcut.

## Files

- `SwiftSlate.pyw` — Raw Input, clipboard integration, tray menu, and command execution
- `text_styles.py` — shared offline Unicode style mappings
- `secure_config.py` — Windows DPAPI encryption/decryption
- `configure.ps1` — secure API-key setup
- `commands.json` — default commands
- `install.ps1` — x64 installer and updater
- `tests/` — platform-independent regression tests

See the repository root [README](../README.md) for the Android application and shared project policies.
