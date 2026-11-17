import unittest
from release_metadata import verify_signers

class ReleaseSignerTests(unittest.TestCase):
    def test_existing_phone_and_watch_certificate_is_accepted(self):
        pinned = 'a' * 64
        verify_signers([pinned, pinned], pinned)

    def test_different_or_mixed_signing_keys_cannot_be_published(self):
        for signers in [['b' * 64] * 2, ['a' * 64, 'b' * 64], []]:
            with self.assertRaises(ValueError):
                verify_signers(signers, 'a' * 64)

    def test_missing_or_malformed_pin_is_not_a_debug_signing_fallback(self):
        for pin in ['', 'not-a-certificate', 'a' * 63]:
            with self.assertRaises(ValueError):
                verify_signers(['a' * 64] * 2, pin)

if __name__ == '__main__':
    unittest.main()
