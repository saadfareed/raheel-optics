-- One list row per todo. The immutable event ledger remains available for analytics.
begin;
create index if not exists todo_history_owner_task_revision on public.todo_history
    (user_id,todo_id,((body->'todo'->>'revision')::bigint) desc);
create or replace function public.history_latest_page(
    before_time bigint default 9223372036854775807,
    before_id uuid default 'ffffffff-ffff-ffff-ffff-ffffffffffff',
    page_size integer default 25)
returns setof jsonb language sql stable security invoker set search_path=public,pg_temp as $$
    with latest as (
        select distinct on (todo_id) todo_id,occurred_at,body
        from public.todo_history where user_id=auth.uid()
        order by todo_id,(body->'todo'->>'revision')::bigint desc,occurred_at desc,id desc
    )
    select body from latest where (occurred_at,todo_id)<(before_time,before_id)
    order by occurred_at desc,todo_id desc limit greatest(1,least(page_size,100));
$$;
revoke all on function public.history_latest_page(bigint,uuid,integer) from public,anon;
grant execute on function public.history_latest_page(bigint,uuid,integer) to authenticated;
commit;
