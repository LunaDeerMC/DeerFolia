"""Compile the external test plugin against the already-built local server."""
import argparse
from pathlib import Path
import shutil
import subprocess
import sys

sys.dont_write_bytecode = True
from support import HERE, OUT, ROOT, java_tool


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classpath-file", type=Path, default=OUT / "classpath.txt")
    args = parser.parse_args()
    if not args.classpath_file.is_file():
        raise SystemExit(
            f"Missing runtime classpath: {args.classpath_file}\n"
            "Build the server, then run from the repository root:\n"
            "./gradlew -I tools/lithium-phase1-e2e/classpath.init.gradle :DeerFolia-server:e2eClasspath"
        )
    classpath = args.classpath_file.read_text().strip()
    if not classpath:
        raise SystemExit(f"Empty runtime classpath: {args.classpath_file}")
    classes = OUT / "plugin-classes"
    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir(parents=True)
    subprocess.run([
        java_tool("javac"), "-proc:none", "-encoding", "UTF-8", "-cp", classpath,
        "-d", str(classes), str(HERE / "Phase1Plugin.java"),
    ], cwd=ROOT, check=True)
    shutil.copyfile(HERE / "plugin.yml", classes / "plugin.yml")
    artifact = OUT / "LithiumPhase1E2E.jar"
    subprocess.run([java_tool("jar"), "--create", "--file", str(artifact), "-C", str(classes), "."], check=True)
    print(artifact)


if __name__ == "__main__":
    main()
