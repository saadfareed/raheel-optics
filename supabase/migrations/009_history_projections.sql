-- Keep history reads proportional to the requested page, and lifetime analytics O(1).
-- The immutable ledger remains the source of truth. Apply during a maintenance window:
-- this one-time backfill briefly blocks new history events, not ordinary reads.
begin;
lock table public.todo_history in share row exclusive mode;

create table public.todo_history_latest (
    todo_id uuid primary key,
    user_id uuid not null references auth.users(id) on delete cascade,
    event_id uuid not null,
    todo_revision bigint not null,
    occurred_at bigint not null,
    body jsonb not null
);
create index todo_history_latest_owner_page on public.todo_history_latest(user_id,occurred_at desc,todo_id desc);
create table public.history_analytics_totals (
    user_id uuid primary key references auth.users(id) on delete cascade,
    with_deadline bigint not null default 0 check(with_deadline>=0),
    on_time bigint not null default 0 check(on_time>=0 and on_time<=with_deadline)
);
alter table public.todo_history_latest enable row level security;
alter table public.history_analytics_totals enable row level security;
create policy owned_latest_history on public.todo_history_latest for select to authenticated using(user_id=auth.uid());
create policy owned_history_analytics on public.history_analytics_totals for select to authenticated using(user_id=auth.uid());
revoke all on public.todo_history_latest,public.history_analytics_totals from public,anon,authenticated;
grant select on public.todo_history_latest,public.history_analytics_totals to authenticated;

insert into public.todo_history_latest(todo_id,user_id,event_id,todo_revision,occurred_at,body)
select distinct on (todo_id) todo_id,user_id,id,(body->'todo'->>'revision')::bigint,occurred_at,body
from public.todo_history
order by todo_id,(body->'todo'->>'revision')::bigint desc,occurred_at desc,id desc;
insert into public.history_analytics_totals(user_id,with_deadline,on_time)
select user_id,
    count(*) filter(where jsonb_typeof(body->'todo'->'dueAt')='number'),
    count(*) filter(where jsonb_typeof(body->'todo'->'dueAt')='number'
        and occurred_at<=(body->'todo'->>'dueAt')::bigint)
from public.todo_history where action='complete' group by user_id;

create function public.capture_history_projections() returns trigger
language plpgsql security definer set search_path=public,pg_temp as $$
declare has_deadline boolean := coalesce(jsonb_typeof(new.body->'todo'->'dueAt')='number',false);
begin
    insert into public.todo_history_latest(todo_id,user_id,event_id,todo_revision,occurred_at,body)
    values(new.todo_id,new.user_id,new.id,(new.body->'todo'->>'revision')::bigint,new.occurred_at,new.body)
    on conflict(todo_id) do update set
        event_id=excluded.event_id,todo_revision=excluded.todo_revision,
        occurred_at=excluded.occurred_at,body=excluded.body
    where (excluded.todo_revision,excluded.occurred_at,excluded.event_id)>
        (todo_history_latest.todo_revision,todo_history_latest.occurred_at,todo_history_latest.event_id);
    if new.action='complete' then
        insert into public.history_analytics_totals(user_id,with_deadline,on_time)
        values(new.user_id,case when has_deadline then 1 else 0 end,
            case when has_deadline and new.occurred_at<=(new.body->'todo'->>'dueAt')::bigint then 1 else 0 end)
        on conflict(user_id) do update set
            with_deadline=history_analytics_totals.with_deadline+excluded.with_deadline,
            on_time=history_analytics_totals.on_time+excluded.on_time;
    end if;
    return new;
end $$;
revoke all on function public.capture_history_projections() from public,anon,authenticated;
create trigger capture_history_projections after insert on public.todo_history
for each row execute function public.capture_history_projections();

create or replace function public.history_latest_page(
    before_time bigint default 9223372036854775807,
    before_id uuid default 'ffffffff-ffff-ffff-ffff-ffffffffffff',
    page_size integer default 25)
returns setof jsonb language sql stable security invoker set search_path=public,pg_temp as $$
    select body from public.todo_history_latest
    where user_id=auth.uid() and (occurred_at,todo_id)<(before_time,before_id)
    order by occurred_at desc,todo_id desc limit greatest(1,least(page_size,100));
$$;

create or replace function public.analytics_summary(period_days integer default 7, timezone_name text default 'UTC')
returns jsonb language sql stable security invoker set search_path=public,pg_temp as $$
    -- Inline these stable bounds so the planner can use the event-time index.
    -- Materializing settings turns the date bounds into a broad join filter.
    with settings as not materialized (
        select greatest(1,least(period_days,30)) as days,(current_timestamp at time zone timezone_name)::date as today
    ), calendar as (
        select today-(days-1)+i as day from settings,generate_series(0,days-1) i
    ), window_events as (
        select (to_timestamp(occurred_at/1000.0) at time zone timezone_name)::date as day,
            coalesce(body->'todo'->>'priority','Medium') as priority
        from public.todo_history,settings
        where user_id=auth.uid() and action='complete'
          and occurred_at >= (extract(epoch from ((today-(days-1))::timestamp at time zone timezone_name))*1000)::bigint
          and occurred_at < (extract(epoch from ((today+1)::timestamp at time zone timezone_name))*1000)::bigint
    ), counts as (
        select day,count(*) as n from window_events group by day
    ), priority_counts as (
        select day,priority,count(*) as n from window_events group by day,priority
    ), priority_series as (
        select p.priority,jsonb_agg(coalesce(pc.n,0) order by c.day) as trend
        from (values ('High'),('Medium'),('Low')) p(priority) cross join calendar c
        left join priority_counts pc on pc.day=c.day and pc.priority=p.priority
        group by p.priority
    )
    select jsonb_build_object(
        'trend',(select jsonb_agg(coalesce(n,0) order by calendar.day) from calendar left join counts using(day)),
        'priorityTrends',(select jsonb_object_agg(priority,trend) from priority_series),
        'withDeadline',coalesce((select with_deadline from public.history_analytics_totals where user_id=auth.uid()),0),
        'onTime',coalesce((select on_time from public.history_analytics_totals where user_id=auth.uid()),0),
        'revision',coalesce((select revision from public.user_totals where id=auth.uid()),0));
$$;
revoke all on function public.history_latest_page(bigint,uuid,integer),public.analytics_summary(integer,text) from public,anon;
grant execute on function public.history_latest_page(bigint,uuid,integer),public.analytics_summary(integer,text) to authenticated;
notify pgrst, 'reload schema';
commit;
