import importlib.util
import io
import json
from pathlib import Path
import unittest
from unittest.mock import patch
from urllib.error import HTTPError

spec = importlib.util.spec_from_file_location('navigation_verification', Path(__file__).parents[1] / 'verify-model-navigation.py')
navigation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(navigation)


class Response(io.BytesIO):
    status = 200
    headers = {}

    def __init__(self):
        super().__init__(b'{"routes":[]}')


def rejected(status=429, code='TOO_MANY_REQUESTS', retry_after='1'):
    return HTTPError('http://localhost/test', status, 'synthetic rejection',
                     {} if retry_after is None else {'Retry-After': retry_after},
                     io.BytesIO(json.dumps({'code': code}).encode()))


class NavigationVerificationTest(unittest.TestCase):
    def test_explicit_rate_and_busy_rejections_follow_the_retry_header(self):
        for status, code in [(429, 'TOO_MANY_REQUESTS'), (503, 'ROUTE_SEARCH_BUSY')]:
            with self.subTest(code=code), patch.object(navigation, 'urlopen', side_effect=[rejected(status, code), Response()]) as network, patch.object(navigation.time, 'sleep') as sleep:
                actual, body, _ = navigation.call('http://localhost', '/test', {'mode': 'shade'})
                self.assertEqual(200, actual)
                self.assertEqual({'routes': []}, body)
                self.assertEqual(2, network.call_count)
                sleep.assert_called_once_with(1)

    def test_data_and_algorithm_failures_are_never_retried(self):
        for code in ['PREDICTION_DATA_UNAVAILABLE', 'ROUTE_SEARCH_LIMIT_EXCEEDED']:
            with self.subTest(code=code), patch.object(navigation, 'urlopen', side_effect=[rejected(503, code)]) as network, patch.object(navigation.time, 'sleep') as sleep:
                status, _, _ = navigation.call('http://localhost', '/test')
                self.assertEqual(503, status)
                self.assertEqual(1, network.call_count)
                sleep.assert_not_called()

    def test_missing_invalid_or_excessive_retry_delays_are_not_followed(self):
        for delay in [None, 'date', '0', '-1', '11', '9999']:
            with self.subTest(delay=delay), patch.object(navigation, 'urlopen', side_effect=[rejected(retry_after=delay)]) as network, patch.object(navigation.time, 'sleep') as sleep:
                status, _, _ = navigation.call('http://localhost', '/test')
                self.assertEqual(429, status)
                self.assertEqual(1, network.call_count)
                sleep.assert_not_called()

    def test_retries_stop_after_three_attempts_and_preserve_the_final_failure(self):
        with patch.object(navigation, 'urlopen', side_effect=[rejected() for _ in range(3)]) as network, patch.object(navigation.time, 'sleep') as sleep:
            status, body, _ = navigation.call('http://localhost', '/test')
            self.assertEqual(429, status)
            self.assertEqual('TOO_MANY_REQUESTS', body['code'])
            self.assertEqual(3, network.call_count)
            self.assertEqual(2, sleep.call_count)
