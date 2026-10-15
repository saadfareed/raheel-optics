-- Insert pre-migration history to exercise real backfill, including time/revision ties.
insert into auth.users values('33333333-3333-3333-3333-333333333333');
insert into public.todo_history(id,user_id,todo_id,action,occurred_at,body)
select ('33333333-0000-0000-0000-'||lpad(i::text,12,'0'))::uuid,
    '33333333-3333-3333-3333-333333333333','33333333-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
    case when i in (1,3) then 'complete' else 'reopen' end,
    case when i=1 then 1000 else 500 end,
    jsonb_build_object('id',('33333333-0000-0000-0000-'||lpad(i::text,12,'0'))::uuid,
        'occurredAt',case when i=1 then 1000 else 500 end,
        'action',case when i in (1,3) then 'complete' else 'reopen' end,
        'todo',jsonb_build_object('id','33333333-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
            'revision',case when i=1 then 2 else 3 end,'dueAt',750,'priority','High'))
from generate_series(1,3) i;
