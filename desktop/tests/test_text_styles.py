import unittest

from text_styles import TextStyle, style_for_command, transform


class TextStyleTest(unittest.TestCase):
    def test_bold_transforms_ascii_letters_and_digits(self):
        self.assertEqual("𝗛𝗲𝗹𝗹𝗼 𝟮𝟬𝟮𝟲!", transform(TextStyle.BOLD, "Hello 2026!"))

    def test_styles_preserve_accents_emoji_and_other_scripts(self):
        source = "Café déjà vu! 🎉\n你好 مرحبا ไทย"
        self.assertEqual(
            "𝘊𝘢𝘧é 𝘥é𝘫à 𝘷𝘶! 🎉\n你好 مرحبا ไทย",
            transform(TextStyle.ITALIC, source),
        )

    def test_social_styles_transform_letters_and_digits(self):
        self.assertEqual("ⓈⓦⓘⓕⓣⓈⓛⓐⓣⓔ ①②③", transform(TextStyle.BUBBLE, "SwiftSlate 123"))
        self.assertEqual("𝚂𝚠𝚒𝚏𝚝𝚂𝚕𝚊𝚝𝚎 𝟷𝟸𝟹", transform(TextStyle.MONOSPACE, "SwiftSlate 123"))
        self.assertEqual("𝔖𝔴𝔦𝔣𝔱𝔖𝔩𝔞𝔱𝔢", transform(TextStyle.GOTHIC, "SwiftSlate"))

    def test_small_caps_leaves_unsupported_letters_and_non_latin_untouched(self):
        self.assertEqual("ꜱᴡɪꜰᴛꜱʟᴀᴛᴇ x 123! ไทย", transform(TextStyle.SMALL_CAPS, "SwiftSlate x 123! ไทย"))

    def test_normal_reverses_supported_styles_and_keeps_plain_text(self):
        styled = transform(TextStyle.GOTHIC, "SwiftSlate 42")
        self.assertEqual("SwiftSlate 42", transform(TextStyle.NORMAL, styled))
        self.assertEqual("Plain text 42", transform(TextStyle.NORMAL, "Plain text 42"))

    def test_style_commands_are_explicit_and_offline(self):
        self.assertEqual(TextStyle.BOLD, style_for_command("bold"))
        self.assertEqual(TextStyle.SMALL_CAPS, style_for_command("smallcaps"))
        self.assertIsNone(style_for_command("fix"))


if __name__ == "__main__":
    unittest.main()
