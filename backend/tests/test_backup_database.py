import sys
from datetime import datetime, timezone
from pathlib import Path
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / '.engineering/scripts'))
from backup_database import backup_filename, retention_victims


def dumps(stamps):
    return ['daymark-%s.dump' % s for s in stamps]


class BackupFilenameTests(unittest.TestCase):
    def test_utc_format_and_dump_suffix(self):
        self.assertEqual(backup_filename(datetime(2026, 10, 10, 12, 30, 45, tzinfo=timezone.utc)),
                         'daymark-20261010T123045Z.dump')

    def test_naive_or_offset_datetime_is_normalized_to_utc(self):
        from datetime import timedelta
        offset = datetime(2026, 10, 10, 14, 30, 45, tzinfo=timezone(timedelta(hours=2)))
        self.assertEqual(backup_filename(offset), 'daymark-20261010T123045Z.dump')


class RetentionTests(unittest.TestCase):
    def test_keeps_newest_30_and_selects_the_rest(self):
        names = dumps(['202610%02dT000000Z' % day for day in range(1, 33)])  # 32 dumps
        victims = retention_victims(names, keep=30)
        self.assertEqual(len(victims), 2)
        self.assertEqual(set(victims), {'daymark-20261001T000000Z.dump', 'daymark-20261002T000000Z.dump'})

    def test_fewer_than_keep_yields_no_victims(self):
        self.assertEqual(retention_victims(dumps(['20261001T000000Z', '20261002T000000Z']), keep=30), [])

    def test_exactly_keep_yields_no_victims(self):
        names = dumps(['202610%02dT000000Z' % day for day in range(1, 31)])  # 30 dumps
        self.assertEqual(retention_victims(names, keep=30), [])

    def test_non_matching_names_ignored(self):
        names = ['readme.txt', 'daymark-bad.dump', 'daymark-20261001T000000Z.dump',
                 'daymark-20261002T000000Z.dump']
        self.assertEqual(retention_victims(names, keep=1), ['daymark-20261001T000000Z.dump'])

    def test_victims_ordered_oldest_first_regardless_of_input_order(self):
        names = dumps(['20261003T000000Z', '20261001T000000Z', '20261002T000000Z'])
        self.assertEqual(retention_victims(names, keep=1),
                         ['daymark-20261001T000000Z.dump', 'daymark-20261002T000000Z.dump'])


if __name__ == '__main__':
    unittest.main()
