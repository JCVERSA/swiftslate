import ctypes
import os
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1]))


@unittest.skipUnless(os.name == "nt", "Windows-only integration checks")
class WindowsIntegrationTest(unittest.TestCase):
    def test_raw_input_and_keyboard_apis_are_available(self):
        user32 = ctypes.windll.user32
        self.assertTrue(hasattr(user32, "RegisterRawInputDevices"))
        self.assertTrue(hasattr(user32, "GetKeyboardState"))
        self.assertTrue(hasattr(user32, "SendInput"))

    def test_clipboard_api_is_available_to_the_runner(self):
        user32 = ctypes.windll.user32
        opened = user32.OpenClipboard(None)
        if not opened:
            self.skipTest("The Windows runner has no interactive clipboard session")
        try:
            self.assertGreaterEqual(user32.GetClipboardSequenceNumber(), 0)
        finally:
            user32.CloseClipboard()

    def test_current_user_dpapi_round_trip(self):
        from secure_config import protect, unprotect

        protected = protect("test-key-ä-2026")
        self.assertNotEqual(protected, "test-key-ä-2026")
        self.assertEqual("test-key-ä-2026", unprotect(protected))


if __name__ == "__main__":
    unittest.main()
