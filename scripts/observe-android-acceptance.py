#!/usr/bin/env python3
"""Test-only host observer: aggregate DB evidence, no tokens or row contents.

The emulator waits for each acknowledgement over adb while keeping its network
state unchanged. Use only with the isolated e2e stack, never a shared database.
"""
import re
import signal
import subprocess


def count_events():
    return int(subprocess.check_output([
        "psql", "-h", "localhost", "-U", "postgres", "-d", "dispatch_e2e",
        "-XAt", "-v", "ON_ERROR_STOP=1", "-c",
        "SELECT count(*) FROM participant_status_events",
    ], text=True, stderr=subprocess.DEVNULL).strip())


class UnexpectedCount(ValueError):
    def __init__(self, operation, expected, actual):
        super().__init__(f"{operation} expected={expected} actual={actual}")
        self.actual = actual


class Observer:
    def __init__(self, count=count_events):
        self.count = count
        self.baselines = {}

    def observe(self, operation, nonce):
        if operation == "START":
            if nonce in self.baselines:
                raise ValueError("duplicate interval")
            self.baselines[nonce] = self.count()
            return 0
        if nonce not in self.baselines:
            raise ValueError("interval not started")
        delta = self.count() - self.baselines[nonce]
        expected = {"OFFLINE": 0, "REPLAYED": 2}[operation]
        if delta != expected:
            raise UnexpectedCount(operation, expected, delta)
        return delta


def acknowledge_observation(observer, operation, nonce, acknowledge):
    try:
        delta = observer.observe(operation, nonce)
    except UnexpectedCount as error:
        # Unblock the instrumentation assertion with the actual count.
        acknowledge(error.actual)
        raise
    acknowledge(delta)
    return delta


def main():
    observer = Observer()
    logcat = subprocess.Popen(["adb", "logcat", "-v", "brief", "DispatchAcceptance:I", "*:S"],
                              stdout=subprocess.PIPE, text=True)
    def stop(_signum, _frame):
        logcat.terminate()
    signal.signal(signal.SIGTERM, stop)
    pattern = re.compile(r"observer (START|OFFLINE|REPLAYED) ([a-f0-9-]{36})$")
    try:
        for line in logcat.stdout:
            print(line.rstrip(), flush=True)
            match = pattern.search(line.rstrip())
            if match:
                operation, nonce = match.groups()
                # Every interpolated component is validated or an integer.
                path = f"/data/local/tmp/dispatch-acceptance-{nonce}-{operation}"
                def acknowledge(delta):
                    subprocess.run(["adb", "shell", f"echo {delta} > {path}"], check=True)
                delta = acknowledge_observation(observer, operation, nonce, acknowledge)
                print(f"server observation={operation} delta={delta}", flush=True)
    finally:
        logcat.terminate()
        logcat.wait()


if __name__ == "__main__":
    main()
