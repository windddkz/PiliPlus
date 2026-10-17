"""Build input and toolchain boundaries must survive Python optimization."""

import ast
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[1]


class PreparationGuardsTest(unittest.TestCase):
    def test_wrong_apk_fails_before_creating_assets_even_with_optimization(self):
        with tempfile.TemporaryDirectory(prefix="pili-prepare-check-") as temporary:
            workspace = Path(temporary)
            repo = workspace / "repo"
            (repo / "scripts").mkdir(parents=True)
            (repo / "android").mkdir()
            script = repo / "scripts/prepare_official_audio.py"
            shutil.copy2(REPO / "scripts/prepare_official_audio.py", script)
            manifest = {"apk_path": "wrong.apk", "apk_sha256": "0" * 64, "files": {}}
            (repo / "android/official-runtime-manifest.json").write_text(
                json.dumps(manifest)
            )
            required = [
                "wrong.apk",
                "execution/toolchain/android-sdk/build-tools/37.0.0/zipalign",
                "execution/toolchain/android-sdk/build-tools/37.0.0/d8",
                "execution/toolchain/android-sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang",
                "execution/toolchain/jdk-17.0.20.1+1/bin/javac",
                "execution/toolchain/ffmpeg-source/ffmpeg-7.1.1/libavutil/avconfig.h",
            ]
            for name in required:
                path = workspace / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b"not the approved input")
            for flags in [[], ["-O"]]:
                with self.subTest(flags=flags):
                    result = subprocess.run(
                        [sys.executable, *flags, str(script)],
                        capture_output=True,
                        text=True,
                    )
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn("Wrong official sample", result.stderr)
                    self.assertFalse((repo / "android/app").exists())

    def test_asset_names_cannot_escape_runtime_directory(self):
        tree = ast.parse((REPO / "scripts/prepare_official_audio.py").read_text())
        function = next(
            node
            for node in tree.body
            if isinstance(node, ast.FunctionDef) and node.name == "asset_path"
        )
        namespace = {"Path": Path, "destination": Path("runtime")}
        exec(
            compile(ast.Module(body=[function], type_ignores=[]), "asset_path", "exec"),
            namespace,
        )
        asset_path = namespace["asset_path"]
        self.assertEqual(asset_path("libijk.so"), Path("runtime/libijk.so"))
        for name in [
            "",
            ".",
            "..",
            "../outside",
            "/outside",
            "nested/file",
            "nested\\file",
        ]:
            with self.subTest(name=name), self.assertRaises(ValueError):
                asset_path(name)

    def test_patch_root_is_enforced_even_with_optimization(self):
        with tempfile.TemporaryDirectory(prefix="pili-patch-check-") as temporary:
            workspace = Path(temporary)
            repo = workspace / "repo"
            (repo / "scripts").mkdir(parents=True)
            (repo / "lib/scripts").mkdir(parents=True)
            script = repo / "scripts/apply_android_patches.py"
            shutil.copy2(REPO / "scripts/apply_android_patches.py", script)
            shutil.copy2(REPO / "lib/scripts/patch.ps1", repo / "lib/scripts/patch.ps1")
            harness = """
import runpy, sys
script, root, outside = sys.argv[1:]
sys.argv = [script, '--toolchain-root', root]
module = runpy.run_path(script)
module['apply'](module['Path'](outside), [])
"""
            for flags in [[], ["-O"]]:
                with self.subTest(flags=flags):
                    result = subprocess.run(
                        [
                            sys.executable,
                            *flags,
                            "-c",
                            harness,
                            str(script),
                            str(workspace / "isolated"),
                            str(workspace / "outside"),
                        ],
                        capture_output=True,
                        text=True,
                    )
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn("outside", result.stderr)


if __name__ == "__main__":
    unittest.main()
