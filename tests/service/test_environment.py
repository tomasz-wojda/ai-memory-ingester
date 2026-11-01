import re
import subprocess
import unittest
from pathlib import Path

from helpers import ROOT

REQUIREMENTS = ROOT / "service" / "requirements"
SETUP = ROOT / "service" / "setup-venv.sh"


class RequirementsTest(unittest.TestCase):
    def requirement_blocks(self, path):
        text = path.read_text(encoding="utf-8")
        lines = [line for line in text.splitlines() if line.strip() and not line.lstrip().startswith("#")]
        blocks, current = [], []
        for line in lines:
            current.append(line.strip().rstrip("\\").strip())
            if not line.rstrip().endswith("\\"):
                blocks.append(current)
                current = []
        return blocks

    def test_every_requirement_is_pinned_and_hashed(self):
        files = sorted(REQUIREMENTS.glob("*.txt"))
        self.assertTrue(files)
        for path in files:
            for block in self.requirement_blocks(path):
                self.assertRegex(block[0], r"^[A-Za-z0-9_.-]+==[0-9][A-Za-z0-9_.+-]*$", f"{path.name}: {block[0]}")
                hashes = [part for part in block[1:] if part.startswith("--hash=sha256:")]
                self.assertTrue(hashes, f"{path.name}: {block[0]} has no hash")
                for item in hashes:
                    self.assertRegex(item, r"^--hash=sha256:[0-9a-f]{64}$")

    def test_no_machine_learning_package_is_listed_before_the_decisions(self):
        text = "\n".join(p.read_text(encoding="utf-8") for p in REQUIREMENTS.glob("*.txt")).lower()
        for name in ("torch", "sentence-transformers", "transformers", "onnxruntime", "ragatouille", "pylate"):
            self.assertNotRegex(text, rf"^{re.escape(name)}==", name)


class SetupScriptTest(unittest.TestCase):
    def test_the_script_is_valid_shell_and_prints_help(self):
        self.assertEqual(subprocess.run(["bash", "-n", str(SETUP)]).returncode, 0)
        result = subprocess.run([str(SETUP), "--help"], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0)
        self.assertIn("--no-numpy", result.stdout)

    def test_the_script_refuses_an_old_python_and_unknown_options(self):
        old = subprocess.run([str(SETUP), "--python", "/usr/bin/python3", "--venv", "/tmp/ams-never-created"], capture_output=True, text=True)
        self.assertEqual(old.returncode, 2)
        self.assertIn("not a final release of Python 3.11 or newer", old.stderr)
        self.assertFalse(Path("/tmp/ams-never-created").exists())
        self.assertEqual(subprocess.run([str(SETUP), "--bogus"], capture_output=True, text=True).returncode, 2)


if __name__ == "__main__":
    unittest.main()
