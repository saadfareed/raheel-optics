"""Exercise generated support quota SQL inside the disposable QA cluster only."""
import importlib.util,json,os,subprocess,sys
from pathlib import Path
root=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(root/'.engineering/scripts'))
socket=Path(os.environ.get('PGHOST',''))
assert socket.name=='socket' and socket.parent.name.startswith('daymark-qa-')
spec=importlib.util.spec_from_file_location('quota',root/'.engineering/scripts/raise_account_quota.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
owner='11111111-1111-1111-1111-111111111111'
def execute(script):return subprocess.run(['psql','-X','-qAt','-v','ON_ERROR_STOP=1'],input=script,capture_output=True,text=True)
try:
    result=execute(module.quota_sql(owner));assert result.returncode==0
    current=json.loads(next(line for line in result.stdout.splitlines() if line.startswith('{')))
    increase=current['maxReceipts']+1
    result=execute(module.quota_sql(owner,receipts=increase,apply=True));assert result.returncode==0,result.stderr
    result=execute(module.quota_sql(owner,receipts=increase,apply=True));assert result.returncode!=0
    result=execute(module.quota_sql(owner));assert result.returncode==0
    assert json.loads(next(line for line in result.stdout.splitlines() if line.startswith('{')))['maxReceipts']==increase
    for bad in ['not-a-uuid',"';delete from auth.users;--"]:
        try:module.quota_sql(bad);raise AssertionError('Invalid identity accepted')
        except ValueError:pass
    print('PASS: support quota inspection, increases, decrease rejection, and validated identity')
finally:
    assert execute("DELETE FROM public.account_resource_overrides WHERE user_id='"+owner+"';").returncode==0
