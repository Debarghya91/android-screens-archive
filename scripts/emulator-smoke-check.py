#!/usr/bin/env python3
"""Emulator smoke check for Payments Training Hub (phase 1, adb scripted).

Proves, on a booted emulator, that the debug APK installs, launches,
gets past the first-run introduction, renders Home, opens the first
lesson (ch1s1, a free sample) through the real deep-link route, and
returns, with the process alive throughout and no crash in logcat.

This is the run-before-send gate: the CI job fails if any step fails.
It uses only the Android SDK platform tools (adb, uiautomator) and the
Python standard library, so it needs no extra dependencies on the
runner and no test code in the app.

Steps:
  1. Wait for the emulator, install the APK, clear logcat.
  2. Launch MainActivity, wait for the UI to settle.
  3. Tap "Skip" on the first-run introduction (real tap, driven by
     the UI hierarchy, not fixed coordinates).
  4. Dismiss the analytics / migration dialogs if they appear.
  5. Assert Home rendered: all four bottom-tab labels visible
     (Home, Search, Payment Cases, My Progress).
  6. Open lesson ch1s1 via the app deep link
     (com.paymentstraininghub.app://lesson?id=ch1s1), wait for the
     lesson to load, retry once on a load error.
  7. Press Back, assert the app is still alive and rendering.
  8. Dump logcat and apply the crash gate.

Crash gate: the run FAILS if logcat contains a FATAL EXCEPTION block
that names the app package, a "has died" line for the app process, or
a force-finish of the app activity, or if the app process disappears
while it should be in the foreground. Screenshots are captured at each
stage for the record, but they are NOT a gate: MainActivity sets
FLAG_SECURE, so screencap pixels are blank by design. The UI hierarchy
dumps are the render proof.

Phase 2 (not this script): a Compose instrumentation test that taps
the first lesson row in a chapter list. It needs androidTest
dependencies that the app does not have yet; see the parent report.
"""

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PACKAGE = "com.paymentstraininghub.app"
MAIN_ACTIVITY = PACKAGE + "/.MainActivity"
LESSON_ID = "ch1s1"
DEEP_LINK = "com.paymentstraininghub.app://lesson?id=" + LESSON_ID

HOME_TABS = ["Home", "Search", "Payment Cases", "My Progress"]
LESSON_TITLE = "Lesson"
LESSON_LOADING = "Loading lesson..."
LESSON_ERROR = "Could not load the full lesson"


def find_adb():
    """Resolve the adb binary: PATH first, then the SDK install."""
    found = shutil.which("adb")
    if found:
        return found
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        home = os.environ.get(var)
        if home:
            candidate = os.path.join(home, "platform-tools", "adb")
            if os.path.isfile(candidate):
                return candidate
    return "adb"


ADB = find_adb()


class CheckFailure(Exception):
    """A gate step failed; the run must fail."""


def log(message):
    print("[smoke] " + message, flush=True)


def run(cmd, timeout=120):
    """Run a command, return (returncode, stdout, stderr) as text."""
    proc = subprocess.run(
        cmd, capture_output=True, text=True, timeout=timeout, errors="replace"
    )
    return proc.returncode, proc.stdout, proc.stderr


def adb(*args, timeout=120, check=False):
    """Run an adb command. Returns stdout text. Raises on failure if check."""
    code, out, err = run([ADB] + list(args), timeout=timeout)
    if check and code != 0:
        raise CheckFailure("adb %s failed (%s): %s" % (" ".join(args), code, err.strip()))
    return out


def adb_binary(*args, timeout=120):
    """Run an adb command, return raw stdout bytes (for screencap)."""
    proc = subprocess.run(
        [ADB] + list(args), capture_output=True, timeout=timeout
    )
    return proc.returncode, proc.stdout, proc.stderr


def poll_until(label, predicate, timeout, interval=3.0):
    """Poll predicate() until it returns a truthy value or timeout.

    Returns the last truthy value, or None on timeout. Predicate
    exceptions are treated as "not yet" and retried.
    """
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        try:
            last = predicate()
        except Exception as exc:  # noqa: BLE001 - polling must be resilient
            log("%s: poll error (retrying): %s" % (label, exc))
            last = None
        if last:
            return last
        time.sleep(interval)
    return None


def wait_for_boot(timeout=300):
    adb("wait-for-device", timeout=timeout)
    ok = poll_until(
        "boot",
        lambda: adb("shell", "getprop", "sys.boot_completed", timeout=30).strip() == "1",
        timeout,
    )
    if not ok:
        raise CheckFailure("emulator did not report boot completed in %ss" % timeout)
    log("emulator booted")


def process_alive():
    """True when the app process exists."""
    try:
        return adb("shell", "pidof", PACKAGE, timeout=30).strip() != ""
    except Exception:  # noqa: BLE001 - any adb error means "unknown", not alive
        return False


def require_alive(what):
    if not process_alive():
        raise CheckFailure("app process is gone during: %s" % what)


def dump_hierarchy(path, retries=3):
    """Dump the UI hierarchy to an XML file. Returns the node list."""
    last_error = None
    for attempt in range(retries):
        try:
            adb("shell", "uiautomator", "dump", "/sdcard/window_dump.xml", timeout=60)
            code, out, err = run(
                [ADB, "pull", "/sdcard/window_dump.xml", path], timeout=60
            )
            if code != 0:
                raise CheckFailure("adb pull of hierarchy failed: %s" % err.strip())
            tree = ET.parse(path)
            return list(tree.getroot().iter("node"))
        except Exception as exc:  # noqa: BLE001 - retry transient dump failures
            last_error = exc
            log("hierarchy dump attempt %d failed: %s" % (attempt + 1, exc))
            time.sleep(3)
    raise CheckFailure("uiautomator dump failed after %d tries: %s" % (retries, last_error))


def node_texts(nodes):
    """All visible texts and content descriptions in a hierarchy dump."""
    texts = []
    for node in nodes:
        for attr in ("text", "content-desc"):
            value = (node.get(attr) or "").strip()
            if value:
                texts.append(value)
    return texts


def find_node(nodes, text):
    """First node whose text or content-desc contains the given text."""
    for node in nodes:
        for attr in ("text", "content-desc"):
            if text in (node.get(attr) or ""):
                return node
    return None


def tap_node(node):
    """Tap the center of a hierarchy node, from its bounds."""
    bounds = node.get("bounds") or ""
    try:
        first, second = bounds.strip("[]").split("][")
        x1, y1 = (int(v) for v in first.split(","))
        x2, y2 = (int(v) for v in second.split(","))
    except ValueError:
        raise CheckFailure("cannot parse node bounds: %r" % bounds)
    x, y = (x1 + x2) // 2, (y1 + y2) // 2
    adb("shell", "input", "tap", str(x), str(y), timeout=30, check=True)
    log("tapped at (%d, %d) for bounds %s" % (x, y, bounds))


def tap_text(out_dir, name, text, timeout=90):
    """Poll the hierarchy for text, tap it when found. Returns bool."""
    path = os.path.join(out_dir, name + ".xml")

    def attempt():
        nodes = dump_hierarchy(path)
        node = find_node(nodes, text)
        if node is None:
            return None
        tap_node(node)
        return True

    found = poll_until("tap %r" % text, attempt, timeout)
    if found:
        log("tapped %r" % text)
        time.sleep(2)
    else:
        log("text %r not found within %ss" % (text, timeout))
    return bool(found)


def wait_for_texts(out_dir, name, texts, timeout, require_all=True):
    """Poll until the hierarchy contains the texts. Returns bool."""
    path = os.path.join(out_dir, name + ".xml")

    def attempt():
        nodes = dump_hierarchy(path)
        present = node_texts(nodes)
        joined = "\n".join(present)
        if require_all:
            return all(t in joined for t in texts)
        return any(t in joined for t in texts)

    return bool(poll_until("wait %s" % texts, attempt, timeout))


def screenshot(out_dir, name):
    """Capture a screenshot PNG. Always attempted; never a gate.

    MainActivity sets FLAG_SECURE, so the pixels are expected to be
    blank. The file is kept for the record and its validity (PNG
    header, non-empty) is checked, nothing more.
    """
    path = os.path.join(out_dir, name + ".png")
    code, data, err = adb_binary("exec-out", "screencap", "-p", timeout=60)
    if code != 0:
        log("WARNING: screencap failed: %s" % err.decode("utf-8", "replace").strip())
        return None
    with open(path, "wb") as handle:
        handle.write(data)
    valid = data[:8] == b"\x89PNG\r\n\x1a\n" and len(data) > 100
    log(
        "screenshot %s: %d bytes, valid PNG: %s "
        "(blank pixels expected: FLAG_SECURE)" % (name, len(data), valid)
    )
    return path


def crash_gate(logcat_text):
    """Scan scoped logcat for app crashes. Returns a list of findings."""
    findings = []
    lines = logcat_text.splitlines()
    for index, line in enumerate(lines):
        if "FATAL EXCEPTION" in line:
            window = "\n".join(lines[index : index + 40])
            if PACKAGE in window:
                findings.append("FATAL EXCEPTION naming the app: %s" % line.strip())
        if "has died" in line and PACKAGE in line:
            findings.append("app process death: %s" % line.strip())
        if "Force finishing activity" in line and PACKAGE in line:
            findings.append("force finish of app activity: %s" % line.strip())
        if "WIN DEATH" in line and PACKAGE in line:
            findings.append("window death for the app: %s" % line.strip())
    return findings


def main(argv=None):
    parser = argparse.ArgumentParser(description="Payments Training Hub emulator smoke check")
    parser.add_argument("--apk", required=True, help="path to the debug APK")
    parser.add_argument("--out", required=True, help="output directory for artifacts")
    args = parser.parse_args(argv)

    out_dir = args.out
    os.makedirs(out_dir, exist_ok=True)
    steps = []
    started = time.time()

    def step(name, fn):
        step_started = time.time()
        log("STEP: %s" % name)
        try:
            result = fn()
        except CheckFailure as exc:
            steps.append({"name": name, "ok": False, "error": str(exc),
                          "seconds": round(time.time() - step_started, 1)})
            raise
        steps.append({"name": name, "ok": True, "detail": result or "",
                      "seconds": round(time.time() - step_started, 1)})
        log("OK: %s (%s)" % (name, result or ""))
        return result

    def write_summary(passed, extra=None):
        summary = {
            "passed": passed,
            "package": PACKAGE,
            "lessonId": LESSON_ID,
            "totalSeconds": round(time.time() - started, 1),
            "steps": steps,
        }
        if extra:
            summary.update(extra)
        with open(os.path.join(out_dir, "summary.json"), "w", encoding="utf-8") as handle:
            json.dump(summary, handle, indent=2)
        return summary

    try:
        if not os.path.isfile(args.apk):
            raise CheckFailure("APK not found: %s" % args.apk)

        step("wait for emulator", lambda: wait_for_boot())

        def install():
            adb("install", "-r", "-g", args.apk, timeout=180, check=True)
            return "installed %s" % os.path.basename(args.apk)

        step("install APK", install)

        adb("logcat", "-c", timeout=30)
        log("logcat cleared (crash gate covers only this run)")

        def launch():
            adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity",
               timeout=120, check=True)
            settled = poll_until(
                "launch settle",
                lambda: process_alive()
                and len(node_texts(dump_hierarchy(os.path.join(out_dir, "launch.xml")))) > 0,
                timeout=120,
            )
            if not settled:
                raise CheckFailure("app did not render any UI within 120s of launch")
            return "process alive, UI rendered"

        step("launch MainActivity", launch)
        screenshot(out_dir, "01-launch")

        def dismiss_onboarding():
            if tap_text(out_dir, "onboarding", "Skip", timeout=90):
                return "tapped Skip"
            # No Skip: onboarding may already be done on a reused
            # emulator, or the introduction may not have appeared.
            # Home assertion below is the real gate.
            return "no Skip button found; continuing to Home check"

        step("dismiss introduction", dismiss_onboarding)
        require_alive("after introduction")

        def dismiss_dialogs():
            dismissed = []
            # Analytics consent (shown once after the introduction).
            if tap_text(out_dir, "dialog-analytics", "Keep it off", timeout=15):
                dismissed.append("analytics")
            # Migration offer (only when a legacy install exists).
            if tap_text(out_dir, "dialog-migration", "Not now", timeout=15):
                dismissed.append("migration")
            return "dismissed: %s" % (", ".join(dismissed) or "none shown")

        step("dismiss first-run dialogs", dismiss_dialogs)
        require_alive("after dialogs")

        def home():
            if not wait_for_texts(out_dir, "home", HOME_TABS, timeout=120):
                raise CheckFailure(
                    "Home bottom tabs not all visible within 120s: %s" % HOME_TABS
                )
            return "tabs visible: %s" % ", ".join(HOME_TABS)

        step("Home rendered", home)
        screenshot(out_dir, "02-home")

        def open_lesson():
            adb("shell", "am", "start", "-a", "android.intent.action.VIEW",
                "-d", DEEP_LINK, PACKAGE, timeout=60, check=True)

            def loaded():
                path = os.path.join(out_dir, "lesson.xml")
                nodes = dump_hierarchy(path)
                joined = "\n".join(node_texts(nodes))
                if LESSON_ERROR in joined:
                    return "error"
                if LESSON_TITLE in joined and LESSON_LOADING not in joined:
                    return "loaded"
                return None

            state = poll_until("lesson load", loaded, timeout=180)
            if state == "error":
                log("lesson load errored; tapping Retry once")
                tap_text(out_dir, "lesson-retry", "Retry", timeout=30)
                state = poll_until("lesson reload", loaded, timeout=120)
            if state != "loaded":
                raise CheckFailure(
                    "lesson %s did not reach Content state (last state: %s)"
                    % (LESSON_ID, state)
                )
            nodes = dump_hierarchy(os.path.join(out_dir, "lesson.xml"))
            count = len(set(node_texts(nodes)))
            if count < 8:
                raise CheckFailure(
                    "lesson screen shows only %d distinct texts; content did not render"
                    % count
                )
            return "lesson %s open, %d distinct texts" % (LESSON_ID, count)

        step("open first lesson", open_lesson)
        screenshot(out_dir, "03-lesson")
        require_alive("on lesson screen")

        def go_back():
            adb("shell", "input", "keyevent", "4", timeout=30, check=True)
            time.sleep(3)
            require_alive("after Back")
            nodes = dump_hierarchy(os.path.join(out_dir, "after-back.xml"))
            if not node_texts(nodes):
                raise CheckFailure("no UI rendered after Back")
            return "Back pressed, app alive and rendering"

        step("Back navigation", go_back)
        screenshot(out_dir, "04-after-back")

        logcat = adb("logcat", "-d", timeout=120)
        with open(os.path.join(out_dir, "logcat.txt"), "w", encoding="utf-8") as handle:
            handle.write(logcat)
        log("logcat dumped: %d lines" % len(logcat.splitlines()))

        findings = crash_gate(logcat)
        if findings:
            for finding in findings:
                log("CRASH GATE: " + finding)
            raise CheckFailure("crash gate failed: %d finding(s)" % len(findings))
        if not process_alive():
            raise CheckFailure("app process is gone at the end of the run")
        steps.append({"name": "crash gate (logcat)", "ok": True,
                      "detail": "no FATAL EXCEPTION, process death, or force finish",
                      "seconds": 0})
        log("OK: crash gate passed")

        summary = write_summary(True)
        log("SMOKE CHECK PASSED in %ss" % summary["totalSeconds"])
        return 0
    except CheckFailure as exc:
        log("SMOKE CHECK FAILED: %s" % exc)
        try:
            logcat = adb("logcat", "-d", timeout=60)
            with open(os.path.join(out_dir, "logcat.txt"), "w", encoding="utf-8") as handle:
                handle.write(logcat)
        except Exception as dump_exc:  # noqa: BLE001 - failure path must not mask the cause
            log("WARNING: could not dump logcat on failure: %s" % dump_exc)
        write_summary(False, {"failure": str(exc)})
        return 1
    except subprocess.TimeoutExpired as exc:
        log("SMOKE CHECK FAILED: timed out waiting for: %s" % exc)
        write_summary(False, {"failure": "timeout: %s" % exc})
        return 1


if __name__ == "__main__":
    sys.exit(main())
