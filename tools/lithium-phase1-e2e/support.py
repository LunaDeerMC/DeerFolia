"""Repository paths and optional local JVM settings for the standalone harness."""
import os
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
OUT = ROOT / "build/lithium-phase1"


def java_tool(name):
    """Use the selected JDK, or the tool already available on PATH."""
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        executable = Path(java_home) / "bin" / name
        if not executable.is_file():
            raise SystemExit(f"JAVA_HOME does not provide {name}: {executable}")
        return str(executable)
    executable = shutil.which(name)
    if not executable:
        raise SystemExit(f"Java 25 {name} is required; set JAVA_HOME or add the JDK to PATH.")
    return executable


def jvm_properties():
    """Reuse optional Gradle proxy/trust-store settings without requiring them."""
    configured = os.environ.get("LITHIUM_E2E_GRADLE_PROPERTIES")
    if configured:
        path = Path(configured).expanduser()
        if not path.is_file():
            raise SystemExit(f"LITHIUM_E2E_GRADLE_PROPERTIES does not exist: {path}")
    else:
        gradle_home = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
        path = gradle_home / "gradle.properties"
    if not path.is_file():
        return []
    return [
        "-D" + line.removeprefix("systemProp.")
        for raw_line in path.read_text().splitlines()
        if (line := raw_line.strip()).startswith("systemProp.")
    ]
