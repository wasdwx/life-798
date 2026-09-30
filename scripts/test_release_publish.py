"""Exercise the workflow's actual publishing block with a shell function, never live gh."""

import os
from pathlib import Path
import subprocess
import sys
import tempfile


def main():
    workflow = (Path(__file__).resolve().parents[1] / ".github/workflows/android.yml").read_text(encoding="utf-8")
    block = workflow.split("      - name: Publish GitHub Release\n", 1)[1].split("        run: |\n", 1)[1]
    body = "\n".join(line[10:] for line in block.splitlines() if line.startswith("          "))
    stub = '''set -euo pipefail
gh() {
    printf '%s\\n' "$*" >> "$CALLS"
    case "$1 $2" in
        "release list") printf '%s\\n' "$PUBLISHED_TAGS" ;;
        "release view") return "$VIEW_STATUS" ;;
        "release upload") return "$UPLOAD_STATUS" ;;
    esac
    return 0
}
'''
    cases = [
        ("v5.4.6", 0, 0, "", True, "--prerelease=false --latest=true"),
        ("v5.4.6-rc.1", 0, 0, "", True, "--prerelease=true --latest=false"),
        ("v5.4.6", 1, 0, "", True, "--prerelease=false --latest=true"),
        ("v5.4.6", 0, 1, "", False, ""),
        ("v5.4.6", 0, 0, "v5.10.0", True, "--prerelease=false --latest=false"),
    ]
    with tempfile.TemporaryDirectory() as folder:
        root = Path(folder)
        script = root / "publish-test.sh"
        script.write_text(stub + body + "\n", encoding="utf-8", newline="\n")
        for index, (tag, view, upload, published, success, flags) in enumerate(cases):
            calls = root / f"calls-{index}.txt"
            env = dict(os.environ, GITHUB_REF_NAME=tag, GITHUB_SHA="test-commit", RUNNER_TEMP=root.as_posix(),
                       CALLS=calls.as_posix(), VIEW_STATUS=str(view), UPLOAD_STATUS=str(upload), PUBLISHED_TAGS=published)
            result = subprocess.run([sys.argv[1] if len(sys.argv) > 1 else "bash", script.as_posix()], env=env,
                                    text=True, capture_output=True)
            assert (result.returncode == 0) == success, result.stderr
            log = calls.read_text(encoding="utf-8")
            if success:
                assert f"release edit {tag} --draft=false {flags}" in log, log
                if view:
                    assert "--target test-commit" in log and "--draft" in log, log
            else:
                assert "release edit" not in log, log
    print(f"PASS: {len(cases)} offline release transition checks; no GitHub requests made.")


if __name__ == "__main__":
    main()
