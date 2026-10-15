begin;
create or replace function public.analytics_summary(period_days integer default 7, timezone_name text default 'UTC')
returns jsonb language sql stable security invoker set search_path=public,pg_temp as $$
    with settings as (
        select greatest(1,least(period_days,30)) as days,(current_timestamp at time zone timezone_name)::date as today
    ), calendar as (
        select today-(days-1)+i as day from settings,generate_series(0,days-1) i
    ), events as (
        select occurred_at,body from public.todo_history where user_id=auth.uid() and action='complete'
    ), counts as (
        select (to_timestamp(occurred_at/1000.0) at time zone timezone_name)::date as day,count(*) as n from events group by day
    )
    select jsonb_build_object(
        'trend',(select jsonb_agg(coalesce(n,0) order by calendar.day) from calendar left join counts using(day)),
        'withDeadline',(select count(*) from events where jsonb_typeof(body->'todo'->'dueAt')='number'),
        'onTime',(select count(*) from events where jsonb_typeof(body->'todo'->'dueAt')='number' and occurred_at<=(body->'todo'->>'dueAt')::bigint),
        'revision',coalesce((select revision from public.user_totals where id=auth.uid()),0));
$$;
revoke all on function public.analytics_summary(integer,text) from public,anon;
grant execute on function public.analytics_summary(integer,text) to authenticated;
commit;
