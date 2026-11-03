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

    def test_the_model_packages_are_in_their_own_file_and_the_numpy_file_stays_small(self):
        models = (REQUIREMENTS / "models.txt").read_text(encoding="utf-8").lower()
        for name in ("torch", "sentence-transformers", "transformers", "safetensors", "tokenizers", "huggingface-hub", "numpy"):
            self.assertRegex(models, rf"(?m)^{re.escape(name)}==", name)
        numpy_only = (REQUIREMENTS / "numpy.txt").read_text(encoding="utf-8").lower()
        self.assertNotRegex(numpy_only, r"(?m)^torch==")
        self.assertEqual(len(self.requirement_blocks(REQUIREMENTS / "numpy.txt")), 1)

    def test_no_unreviewed_machine_learning_framework_is_listed(self):
        text = "\n".join(p.read_text(encoding="utf-8") for p in REQUIREMENTS.glob("*.txt")).lower()
        for name in ("onnxruntime", "ragatouille", "pylate", "flagembedding"):
            self.assertNotRegex(text, rf"(?m)^{re.escape(name)}==", name)

    def test_the_model_requirements_match_the_wheels_of_one_platform(self):
        text = (REQUIREMENTS / "models.txt").read_text(encoding="utf-8")
        self.assertIn("macOS arm64", text)
        self.assertEqual(len(re.findall(r"(?m)^[a-z0-9-]+==", text)), len(re.findall(r"--hash=sha256:", text)))


class SetupScriptTest(unittest.TestCase):
    def test_the_script_is_valid_shell_and_prints_help(self):
        self.assertEqual(subprocess.run(["bash", "-n", str(SETUP)]).returncode, 0)
        result = subprocess.run([str(SETUP), "--help"], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0)
        self.assertIn("--no-numpy", result.stdout)
        self.assertIn("--models", result.stdout)

    def test_the_script_refuses_an_old_python_and_unknown_options(self):
        old = subprocess.run([str(SETUP), "--python", "/usr/bin/python3", "--venv", "/tmp/ams-never-created"], capture_output=True, text=True)
        self.assertEqual(old.returncode, 2)
        self.assertIn("not a final release of Python 3.11 or newer", old.stderr)
        self.assertFalse(Path("/tmp/ams-never-created").exists())
        self.assertEqual(subprocess.run([str(SETUP), "--bogus"], capture_output=True, text=True).returncode, 2)


if __name__ == "__main__":
    unittest.main()
