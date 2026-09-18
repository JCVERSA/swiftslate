import json
from pathlib import Path
import unittest


class DesktopCommandsTest(unittest.TestCase):
    def test_required_local_styles_are_built_in_and_offline(self):
        commands = json.loads((Path(__file__).parents[1] / "commands.json").read_text(encoding="utf-8"))
        by_trigger = {item["trigger"]: item for item in commands}
        for trigger in ("bold", "italic", "mono", "bubble", "gothic", "smallcaps", "normal"):
            self.assertEqual("style", by_trigger[trigger]["type"])
            self.assertEqual(trigger, by_trigger[trigger]["value"])

    def test_ai_and_shell_commands_are_not_used_for_styles(self):
        commands = json.loads((Path(__file__).parents[1] / "commands.json").read_text(encoding="utf-8"))
        styles = [item for item in commands if item.get("type") == "style"]
        self.assertEqual(7, len(styles))
        self.assertFalse(any(item.get("type") == "replacer-shell" for item in styles))


if __name__ == "__main__":
    unittest.main()
