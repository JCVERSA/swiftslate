import hashlib
from pathlib import Path
import re
import unittest


class DesktopPackagingTest(unittest.TestCase):
    def test_installer_hashes_match_checked_in_runtime_files(self):
        root = Path(__file__).parents[1]
        installer = (root / "install.ps1").read_text(encoding="utf-8")
        expected_files = ("SwiftSlate.pyw", "commands.json", "text_styles.py", "secure_config.py", "configure.ps1")
        for name in expected_files:
            match = re.search(
                rf'"{re.escape(name)}"\s*=\s*"([0-9A-Fa-f]{{64}})"',
                installer,
            )
            self.assertIsNotNone(match, f"missing pinned hash for {name}")
            digest = hashlib.sha256((root / name).read_bytes()).hexdigest()
            self.assertEqual(match.group(1).lower(), digest, name)

    def test_installer_targets_this_repository(self):
        installer = (Path(__file__).parents[1] / "install.ps1").read_text(encoding="utf-8")
        self.assertIn("raw.githubusercontent.com/JCVERSA/swiftslate/main/desktop", installer)
        self.assertNotIn("Musheer360/SwiftSlate-Desktop/master", installer)

    def test_windows_default_prefix_is_a_period(self):
        root = Path(__file__).parents[1]
        source = (root / "SwiftSlate.pyw").read_text(encoding="utf-8")
        installer = (root / "install.ps1").read_text(encoding="utf-8")
        self.assertIn('prefix = "."', source)
        self.assertIn('config.get("prefix", ".")', source)
        self.assertIn('prefix = "."', installer)


if __name__ == "__main__":
    unittest.main()
