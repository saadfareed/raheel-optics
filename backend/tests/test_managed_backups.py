import sys
from datetime import datetime,timezone
from pathlib import Path
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'.engineering/scripts'))
from check_managed_backups import summarize

NOW=datetime(2026,10,10,12,tzinfo=timezone.utc)
class ManagedBackupTests(unittest.TestCase):
    def test_recent_completed_daily_backup_passes(self):
        report=summarize({'pitr_enabled':False,'backups':[{'status':'COMPLETED','inserted_at':'2026-10-09T12:00:00Z'}]},NOW)
        self.assertTrue(report['passed']);self.assertEqual(report['latestBackupAgeHours'],24)
    def test_empty_stale_or_pitr_inventory_does_not_claim_daily_recovery(self):
        for data in [{'pitr_enabled':False,'backups':[]},
                     {'pitr_enabled':False,'backups':[{'status':'COMPLETED','inserted_at':'2026-10-08T00:00:00Z'}]},
                     {'pitr_enabled':True,'backups':[{'status':'COMPLETED','inserted_at':'2026-10-10T00:00:00Z'}]}]:
            with self.subTest(data=data):self.assertFalse(summarize(data,NOW)['passed'])
if __name__=='__main__':unittest.main()
