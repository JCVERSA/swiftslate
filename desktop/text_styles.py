"""Offline Unicode text styles shared by the Windows command and selection flows.

SwiftSlate can replace text in another application, but it cannot change that
application's actual font. These mappings therefore use Unicode presentation
characters where they exist. Characters without a safe equivalent are copied
unchanged, which is important for accents, emoji, Arabic, CJK, Thai, and other
scripts.
"""

from enum import Enum


class TextStyle(str, Enum):
    NORMAL = "normal"
    BOLD = "bold"
    ITALIC = "italic"
    MONOSPACE = "mono"
    BUBBLE = "bubble"
    GOTHIC = "gothic"
    SMALL_CAPS = "smallcaps"


# Keep these tables identical to Android's TextStyleTransformer.kt.
_BOLD_UPPER = "𝗔𝗕𝗖𝗗𝗘𝗙𝗚𝗛𝗜𝗝𝗞𝗟𝗠𝗡𝗢𝗣𝗤𝗥𝗦𝗧𝗨𝗩𝗪𝗫𝗬𝗭"
_BOLD_LOWER = "𝗮𝗯𝗰𝗱𝗲𝗳𝗴𝗵𝗶𝗷𝗸𝗹𝗺𝗻𝗼𝗽𝗾𝗿𝘀𝘁𝘂𝘃𝘄𝘅𝘆𝘇"
_BOLD_DIGITS = "𝟬𝟭𝟮𝟯𝟰𝟱𝟲𝟳𝟴𝟵"

_ITALIC_UPPER = "𝘈𝘉𝘊𝘋𝘌𝘍𝘎𝘏𝘐𝘑𝘒𝘓𝘔𝘕𝘖𝘗𝘘𝘙𝘚𝘛𝘜𝘝𝘞𝘟𝘠𝘡"
_ITALIC_LOWER = "𝘢𝘣𝘤𝘥𝘦𝘧𝘨𝘩𝘪𝘫𝘬𝘭𝘮𝘯𝘰𝘱𝘲𝘳𝘴𝘵𝘶𝘷𝘸𝘹𝘺𝘻"

_MONO_UPPER = "𝙰𝙱𝙲𝙳𝙴𝙵𝙶𝙷𝙸𝙹𝙺𝙻𝙼𝙽𝙾𝙿𝚀𝚁𝚂𝚃𝚄𝚅𝚆𝚇𝚈𝚉"
_MONO_LOWER = "𝚊𝚋𝚌𝚍𝚎𝚏𝚐𝚑𝚒𝚓𝚔𝚕𝚖𝚗𝚘𝚙𝚚𝚛𝚜𝚝𝚞𝚟𝚠𝚡𝚢𝚣"
_MONO_DIGITS = "𝟶𝟷𝟸𝟹𝟺𝟻𝟼𝟽𝟾𝟿"

_BUBBLE_UPPER = "ⒶⒷⒸⒹⒺⒻⒼⒽⒾⒿⓀⓁⓂⓃⓄⓅⓆⓇⓈⓉⓊⓋⓌⓍⓎⓏ"
_BUBBLE_LOWER = "ⓐⓑⓒⓓⓔⓕⓖⓗⓘⓙⓚⓛⓜⓝⓞⓟⓠⓡⓢⓣⓤⓥⓦⓧⓨⓩ"
_BUBBLE_DIGITS = "⓪①②③④⑤⑥⑦⑧⑨"

# Fraktur has compatibility characters for a few capital letters.
_GOTHIC_UPPER = "𝔄𝔅ℭ𝔇𝔈𝔉𝔊ℌℑ𝔍𝔎𝔏𝔐𝔑𝔒𝔓𝔔ℜ𝔖𝔗𝔘𝔙𝔚𝔛𝔜ℨ"
_GOTHIC_LOWER = "𝔞𝔟𝔠𝔡𝔢𝔣𝔤𝔥𝔦𝔧𝔨𝔩𝔪𝔫𝔬𝔭𝔮𝔯𝔰𝔱𝔲𝔳𝔴𝔵𝔶𝔷"

# Unicode has no small-cap glyph for every Latin letter. Unsupported letters
# deliberately remain unchanged.
_SMALL_CAPS = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ"


def _map_ascii(text, upper, lower, digits=None):
    upper_map = dict(zip("ABCDEFGHIJKLMNOPQRSTUVWXYZ", upper))
    lower_map = dict(zip("abcdefghijklmnopqrstuvwxyz", lower))
    digit_map = dict(zip("0123456789", digits)) if digits is not None else {}
    mapping = {**upper_map, **lower_map, **digit_map}
    return "".join(mapping.get(char, char) for char in text)


def _add_reverse(target, glyphs, plain_start):
    for index, glyph in enumerate(glyphs):
        target[glyph] = chr(ord(plain_start) + index)


_NORMAL_FORMS = {}
_add_reverse(_NORMAL_FORMS, _BOLD_UPPER, "A")
_add_reverse(_NORMAL_FORMS, _BOLD_LOWER, "a")
_add_reverse(_NORMAL_FORMS, _BOLD_DIGITS, "0")
_add_reverse(_NORMAL_FORMS, _ITALIC_UPPER, "A")
_add_reverse(_NORMAL_FORMS, _ITALIC_LOWER, "a")
_add_reverse(_NORMAL_FORMS, _MONO_UPPER, "A")
_add_reverse(_NORMAL_FORMS, _MONO_LOWER, "a")
_add_reverse(_NORMAL_FORMS, _MONO_DIGITS, "0")
_add_reverse(_NORMAL_FORMS, _BUBBLE_UPPER, "A")
_add_reverse(_NORMAL_FORMS, _BUBBLE_LOWER, "a")
_add_reverse(_NORMAL_FORMS, _BUBBLE_DIGITS, "0")
_add_reverse(_NORMAL_FORMS, _GOTHIC_UPPER, "A")
_add_reverse(_NORMAL_FORMS, _GOTHIC_LOWER, "a")
for index, glyph in enumerate(_SMALL_CAPS):
    _NORMAL_FORMS[glyph] = chr(ord("a") + index)


def transform(style, text):
    """Apply *style* to *text* without dropping unsupported characters."""
    style = TextStyle(style)
    if style is TextStyle.NORMAL:
        return "".join(_NORMAL_FORMS.get(char, char) for char in text)
    if style is TextStyle.BOLD:
        return _map_ascii(text, _BOLD_UPPER, _BOLD_LOWER, _BOLD_DIGITS)
    if style is TextStyle.ITALIC:
        return _map_ascii(text, _ITALIC_UPPER, _ITALIC_LOWER)
    if style is TextStyle.MONOSPACE:
        return _map_ascii(text, _MONO_UPPER, _MONO_LOWER, _MONO_DIGITS)
    if style is TextStyle.BUBBLE:
        return _map_ascii(text, _BUBBLE_UPPER, _BUBBLE_LOWER, _BUBBLE_DIGITS)
    if style is TextStyle.GOTHIC:
        return _map_ascii(text, _GOTHIC_UPPER, _GOTHIC_LOWER)
    if style is TextStyle.SMALL_CAPS:
        return "".join(
            _SMALL_CAPS[ord(char) - ord("A")] if "A" <= char <= "Z"
            else _SMALL_CAPS[ord(char) - ord("a")] if "a" <= char <= "z"
            else char
            for char in text
        )
    raise ValueError(f"Unsupported text style: {style}")


DEFINITIONS = (
    ("bold", TextStyle.BOLD, "Apply bold Unicode characters locally."),
    ("italic", TextStyle.ITALIC, "Apply italic Unicode characters locally."),
    ("mono", TextStyle.MONOSPACE, "Apply monospace Unicode characters locally."),
    ("bubble", TextStyle.BUBBLE, "Apply circled Unicode characters locally."),
    ("gothic", TextStyle.GOTHIC, "Apply Fraktur Unicode characters locally."),
    ("smallcaps", TextStyle.SMALL_CAPS, "Apply small-cap Unicode characters locally."),
    ("normal", TextStyle.NORMAL, "Convert supported Unicode styles back to normal text."),
)

STYLE_BY_COMMAND = {name: style for name, style, _ in DEFINITIONS}


def style_for_command(command_name):
    """Return a TextStyle for a built-in command name, or None."""
    return STYLE_BY_COMMAND.get(command_name)


__all__ = ["DEFINITIONS", "STYLE_BY_COMMAND", "TextStyle", "style_for_command", "transform"]
