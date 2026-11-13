-- Add priority trends without changing the RPC arguments or prior migration files.
begin;
create index if not exists todo_history_owner_action_time on public.todo_history(user_id,action,occurred_at);
create or replace function public.analytics_summary(period_days integer default 7, timezone_name text default 'UTC')
returns jsonb language sql stable security invoker set search_path=public,pg_temp as $$
    with settings as (
        select greatest(1,least(period_days,30)) as days,(current_timestamp at time zone timezone_name)::date as today
    ), calendar as (
        select today-(days-1)+i as day from settings,generate_series(0,days-1) i
    ), events as (
        select occurred_at,body from public.todo_history where user_id=auth.uid() and action='complete'
    ), window_events as (
        select (to_timestamp(occurred_at/1000.0) at time zone timezone_name)::date as day,
            coalesce(body->'todo'->>'priority','Medium') as priority
        from events,settings
        where occurred_at >= (extract(epoch from ((today-(days-1))::timestamp at time zone timezone_name))*1000)::bigint
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
        'withDeadline',(select count(*) from events where jsonb_typeof(body->'todo'->'dueAt')='number'),
        'onTime',(select count(*) from events where jsonb_typeof(body->'todo'->'dueAt')='number' and occurred_at<=(body->'todo'->>'dueAt')::bigint),
        'revision',coalesce((select revision from public.user_totals where id=auth.uid()),0));
$$;
revoke all on function public.analytics_summary(integer,text) from public,anon;
grant execute on function public.analytics_summary(integer,text) to authenticated;
notify pgrst, 'reload schema';
commit;
