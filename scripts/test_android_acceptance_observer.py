import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("observer", Path(__file__).with_name("observe-android-acceptance.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ObserverTest(unittest.TestCase):
    def observer(self, counts):
        values = iter(counts)
        return module.Observer(lambda: next(values))

    def test_offline_zero_then_two_effects_including_replays(self):
        observer = self.observer([8, 8, 10])
        self.assertEqual(0, observer.observe("START", "one"))
        self.assertEqual(0, observer.observe("OFFLINE", "one"))
        self.assertEqual(2, observer.observe("REPLAYED", "one"))

    def test_any_offline_acceptance_fails(self):
        observer = self.observer([8, 9])
        observer.observe("START", "one")
        with self.assertRaisesRegex(ValueError, "actual=1"):
            observer.observe("OFFLINE", "one")

    def test_duplicate_business_effect_fails(self):
        observer = self.observer([8, 11])
        observer.observe("START", "one")
        with self.assertRaisesRegex(ValueError, "actual=3"):
            observer.observe("REPLAYED", "one")

    def test_missing_or_duplicate_start_fails(self):
        observer = self.observer([8])
        with self.assertRaises(ValueError):
            observer.observe("OFFLINE", "one")
        observer.observe("START", "one")
        with self.assertRaises(ValueError):
            observer.observe("START", "one")


if __name__ == "__main__":
    unittest.main()
