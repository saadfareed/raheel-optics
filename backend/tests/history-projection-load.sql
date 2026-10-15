-- Optional local workload: 500 tasks with 100 history events each, never production.
begin;
insert into auth.users values('44444444-4444-4444-4444-444444444444');
insert into public.todo_history(id,user_id,todo_id,action,occurred_at,body)
select md5('load-event-'||i)::uuid,'44444444-4444-4444-4444-444444444444',
    md5('load-task-'||((i-1)%500))::uuid,'complete',
    (extract(epoch from now())*1000)::bigint-i*86400000::bigint,
    jsonb_build_object('id',md5('load-event-'||i)::uuid,'action','complete',
        'occurredAt',(extract(epoch from now())*1000)::bigint-i*86400000::bigint,
        'todo',jsonb_build_object('id',md5('load-task-'||((i-1)%500))::uuid,
            'revision',(i-1)/500+1,'priority','Medium','dueAt',(extract(epoch from now())*1000)::bigint))
from generate_series(1,50000) i;
commit;
-- Measure settled data after vacuum; the insert transaction creates many obsolete
-- projection versions as each task/counter is updated repeatedly.
vacuum analyze public.todo_history_latest;
vacuum analyze public.history_analytics_totals;
analyze public.todo_history;
begin;
set local role authenticated;
select set_config('request.jwt.claim.sub','44444444-4444-4444-4444-444444444444',true);
explain (analyze,buffers) select body from public.todo_history_latest
where user_id=auth.uid() and (occurred_at,todo_id)<(9223372036854775807,'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid)
order by occurred_at desc,todo_id desc limit 25;
explain (analyze,buffers) select public.analytics_summary(7,'Asia/Karachi');
explain (analyze,buffers) select with_deadline,on_time from public.history_analytics_totals where user_id=auth.uid();
explain (analyze,buffers) select occurred_at,body from public.todo_history
where user_id=auth.uid() and action='complete'
    and occurred_at >= (extract(epoch from ((current_timestamp at time zone 'Asia/Karachi')::date-6)::timestamp at time zone 'Asia/Karachi')*1000)::bigint
    and occurred_at < (extract(epoch from ((current_timestamp at time zone 'Asia/Karachi')::date+1)::timestamp at time zone 'Asia/Karachi')*1000)::bigint;
-- Measure the same legacy history query against this ledger for comparison.
explain (analyze,buffers) with latest as (
    select distinct on (todo_id) todo_id,occurred_at,body from public.todo_history where user_id=auth.uid()
    order by todo_id,(body->'todo'->>'revision')::bigint desc,occurred_at desc,id desc
)
select body from latest order by occurred_at desc,todo_id desc limit 25;
rollback;
delete from auth.users where id='44444444-4444-4444-4444-444444444444';
